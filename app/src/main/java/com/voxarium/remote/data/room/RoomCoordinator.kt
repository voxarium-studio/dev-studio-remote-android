package com.voxarium.remote.data.room

import com.voxarium.remote.data.documents.*
import com.voxarium.remote.data.transport.*
import java.util.UUID

/** One selected-document intent per transport/repository binding; native only (protocol §§4–8,13).
 * All methods/effects/callbacks/ticks run on ONE ordered IO worker, never concurrently/main thread.
 * This owns repository.onEvent for the binding: do not apply a frame twice in an outer adapter.
 * An instance is permanently bound to owner; profile replacement needs a new instance, after stop.
 * Writes default OFF until the integrating application has met the §13 schema/interop gate.
 */
class RoomCoordinator(
    private val repository: DocumentsRepository,
    private val owner: Long,
    private val recovery: RoomRecoverySink,
    private val writesEnabled: Boolean = false,
    private val nowMillis: () -> Long = android.os.SystemClock::elapsedRealtime,
    private val closeTimeoutMillis: Long = 5_000,
    private val maxBufferedBytes: Int = 16 * 1024 * 1024,
) {
    init { require(closeTimeoutMillis > 0 && maxBufferedBytes > 0) }
    private enum class Phase { PREPARING, JOINING, SEEDING, MOUNTING, READY, RETIRING, DISPOSING }
    private class Session(
        val context: EditorContext, val path: String, val source: DocumentVersion,
        val namespace: DocumentNamespace, val scope: DocumentScope,
    ) {
        var phase = Phase.PREPARING
        var request = 0L
        var seed: String? = null
        var winner: String? = null
        var epoch: String? = null
        var scribe = false
        var inheritedDirty = false
        var queue: ScribeQueue? = null
        var token: RoomWriteToken? = null
        var latest: RoomSnapshot? = null
        var lastEditorGeneration = -1L
        val buffered = ArrayDeque<ServerFrame>()
        var bytes = 0
        var applying: Long? = null
        var appliedRaw: String? = null
        val updateSends = mutableSetOf<Long>()
        var unmatchedLocalUpdate = false
        var closeRequested = false
        var flushRequested: Long? = null
        var flushed = false
        var deadline: Long? = null
        var finalStatus: RoomStatus? = null
        var rejoin = false
        var holdMembership = false
        var recoveredSignature: String? = null
        var unconfirmedSnapshot = false
    }
    private var generation = -1L
    private var online = false
    private var documentGeneration = 0L
    private var requestId = 0L
    private var serial = 0L
    private var intent: String? = null
    private var session: Session? = null
    private var held: EditorContext? = null
    private var staleRetries = 0
    private var failed = false
    private val out = mutableListOf<RoomEffect>()
    val hasActiveEditor: Boolean get() = session != null
    val hasOpenIntent: Boolean get() = intent != null

    private fun number(value: Long): Long { check(value < FrameCodec.MAX_INTEGER); return value + 1 }
    private fun status(value: RoomStatus) { out += RoomEffect.Status(value) }
    private inline fun effects(block: () -> Unit): List<RoomEffect> {
        check(!failed) { "Coordinator failed; retain editor/recovery and stop binding" }
        check(out.isEmpty()) { "Reentrant coordinator" }
        try { block(); return out.toList() }
        catch(error: Exception) {
            // No network effects may escape a failed storage/schema/application transition.
            failed=true;online=false;session?.token=null;session?.queue?.stop()
            throw error
        } finally { out.clear() }
    }
    private fun command(s: Session, create: (Long) -> EditorCommand): Long {
        requestId = number(requestId); s.request=requestId
        out += RoomEffect.Editor(create(requestId)); return requestId
    }
    private fun send(frame: ClientFrame, context: EditorContext? = session?.context, document: DocumentSend? = null): RoomEffect.Network {
        FrameCodec.encode(frame)
        serial=number(serial)
        return RoomEffect.Network(document?.connectionGeneration ?: generation,frame,context,document,serial).also { out += it }
    }
    private fun docSend(value: DocumentSend) { send(value.frame,null,value) }
    private fun roomSid(frame: ServerFrame): String? = when(frame) {
        is ServerFrame.Joined -> frame.sid; is ServerFrame.JoinFailed -> frame.sid
        is ServerFrame.Seeded -> frame.sid; is ServerFrame.Update -> frame.sid
        is ServerFrame.Awareness -> frame.sid; is ServerFrame.PeerJoined -> frame.sid
        is ServerFrame.PeerLeft -> frame.sid; is ServerFrame.Scribe -> frame.sid
        is ServerFrame.Reset -> frame.sid
        is ServerFrame.Error -> (frame.sid as? FrameId.Text)?.value
        else -> null
    }

    fun open(path: String): List<RoomEffect> = effects {
        check(intent == null && session == null) { "Close current intent first" }
        // Validate before retaining any intent; never create missing paths implicitly.
        FrameCodec.encode(ClientFrame.Join("validate",path,0,0))
        require(path == path.trim())
        intent=path;staleRetries=0;start()
    }

    private fun start() {
        val path=intent ?: return
        if(session != null) return
        val view=repository.view()
        if(!online || !view.synced || path in view.pending) { status(RoomStatus.WAITING_SYNC);return }
        val source=view.documents[path]
        if(source == null || source.deleted) { intent=null;releaseHeld();status(RoomStatus.MISSING);return }
        documentGeneration=number(documentGeneration)
        val c=EditorContext(documentGeneration,generation,UUID.randomUUID().toString())
        val s=Session(c,path,source,view.namespace!!,repository.commandScope(owner))
        session=s
        command(s) { EditorCommand.Prepare(c,it,source.content!!) }
        status(RoomStatus.JOINING)
    }

    fun onConnectionEvent(event: ConnectionEvent): List<RoomEffect> = effects {
        if(event.generation < generation) return@effects
        val frame=(event as? ConnectionEvent.Frame)?.frame
        if(event.generation > generation) {
            session?.let { retire(it,rejoin=intent != null,hold=false) }
            generation=event.generation;online=false;held=null
        }
        if(frame != null) {
            val sid=roomSid(frame)
            if(sid != null && sid != session?.context?.sid) return@effects // BEFORE repository!
        }
        if(event is ConnectionEvent.State && event.phase != ConnectionPhase.READY) {
            session?.let { retire(it,rejoin=intent != null,hold=false) }
            online=false;held=null
        }
        val s=session
        if(frame is ServerFrame.Reset && frame.path == s?.path) {
            // Checkpoint before repository reset/reconciliation can replace the old draft/base.
            retire(s,rejoin=intent != null && !s.closeRequested,hold=false)
        }
        // A Change has no epoch. Fence/checkpoint before the repository can replace its base.
        val changing=frame is ServerFrame.Change && frame.path == s?.path
        if(changing && s.phase !in setOf(Phase.RETIRING,Phase.DISPOSING)) {
            persist(s)
            s.token=null
        }
        repository.onEvent(owner,event).forEach(::docSend)
        if(frame is ServerFrame.Hello) online=true
        if(!online) return@effects
        if(frame is ServerFrame.Synced) { start();return@effects }
        if(s == null) { if(frame is ServerFrame.Ack) start();return@effects }
        if(event.generation != s.context.connectionGeneration) return@effects
        if(frame is ServerFrame.Change && frame.path == s.path) {
            // A tombstone is NOT a saved empty file. Also use the newest durable mirror,
            // not a potentially older raw Change that the repository correctly ignored.
            repository.view().documents[s.path]?.takeUnless { it.deleted }?.content?.let { s.queue?.materialized(it) }
            // Protocol §§7–8: matching Markdown cannot bypass outstanding update enqueues
            // or repository pending. Change revoked the grant: if the full barrier cannot
            // finish now, recover/retire rather than leaving READY for a later save callback.
            if(!barrier(s)) retire(s,rejoin=!s.closeRequested && intent != null,hold=!s.closeRequested)
            return@effects
        }
        if(frame is ServerFrame.Ack) {
            if(s.phase in setOf(Phase.RETIRING,Phase.DISPOSING)) return@effects
            val q=s.queue
            val id=(frame.id as? FrameId.Integer)?.value
            if(id != null && q?.view()?.inFlight?.requestId == id && frame.path == s.path) {
                val pending=q.view().inFlight!!
                val view=repository.view()
                if(view.pending[s.path] == null && view.documents[s.path]?.content == pending.snapshot.markdown) {
                    q.acknowledge(id)
                    status(if(q.view().dirty) RoomStatus.ROOM_ONLY else RoomStatus.REMOTE_SAVED)
                    save(s,force=s.closeRequested);barrier(s)
                } else retire(s,rejoin=intent != null,hold=true)
            }
            return@effects
        }
        val pendingId=s.queue?.view()?.inFlight?.requestId
        if(frame is ServerFrame.Nack && frame.path == s.path && pendingId != null && (frame.id as? FrameId.Integer)?.value == pendingId ||
            frame is ServerFrame.Error && ((pendingId != null && (frame.id as? FrameId.Integer)?.value == pendingId) || roomSid(frame) == s.context.sid)) {
            intent=null // Explicit reopen/recovery decision, not an automatic retry of rejected bytes.
            retire(s,rejoin=false,hold=false);status(RoomStatus.RECOVERY_UNCONFIRMED);return@effects
        }
        if(s.phase in setOf(Phase.RETIRING,Phase.DISPOSING)) return@effects
        when(frame) {
            is ServerFrame.Joined -> if(s.phase == Phase.JOINING && frame.path == s.path) {
                if(frame.rev != s.source.rev || frame.epoch.isEmpty()) {
                    retire(s,true,false);online=false;status(RoomStatus.RECONNECT_REQUIRED);return@effects
                }
                s.epoch=frame.epoch;s.scribe=s.scribe || frame.scribe;s.inheritedDirty=frame.dirty
                s.queue=ScribeQueue(s.source.content!!,frame.dirty,s.scribe)
                // New joined session keeps the old ephemeral room alive before old membership leaves.
                releaseHeld()
                if(frame.seeded) initialize(s,requireNotNull(frame.state))
                else { s.phase=Phase.SEEDING;send(ClientFrame.Seed(s.context.sid,s.seed!!));status(RoomStatus.SEEDING) }
            }
            is ServerFrame.Seeded -> if(s.phase == Phase.SEEDING) {
                val winner=if(frame.ok) frame.state ?: s.seed!! else requireNotNull(frame.state)
                s.seed=null // NEVER apply a losing independently built seed.
                initialize(s,winner)
            }
            is ServerFrame.JoinFailed -> if(s.phase == Phase.JOINING && frame.path == s.path) {
                if(frame.reason == JoinFailure.MISSING) {
                    intent=null;retire(s,false,false);status(RoomStatus.MISSING)
                } else {
                    staleRetries++
                    val caughtUp=repository.view().documents[s.path]?.rev != s.source.rev
                    retire(s,staleRetries <= 2 && caughtUp,false)
                    if(staleRetries <= 2 && !caughtUp) status(RoomStatus.RECONNECT_REQUIRED)
                    else if(staleRetries > 2) { intent=null;status(RoomStatus.STALE) }
                }
            }
            is ServerFrame.Scribe -> { s.scribe=true;s.queue?.setScribe(true);save(s);barrier(s) }
            is ServerFrame.Reset -> if(frame.path == s.path) retire(s,intent != null && !s.closeRequested,false)
            is ServerFrame.Update, is ServerFrame.Awareness, is ServerFrame.PeerJoined, is ServerFrame.PeerLeft -> {
                val size=when(frame) { is ServerFrame.Update -> frame.update.length;is ServerFrame.Awareness -> frame.update.length;else -> 32 }
                if(s.buffered.size >= 256 || size > maxBufferedBytes-s.bytes) {
                    if(frame is ServerFrame.Update)
                        recovery.persist(s.namespace,s.path,s.context,s.latest,listOf(frame.update))
                    retire(s,true,false);online=false;status(RoomStatus.RECONNECT_REQUIRED)
                } else { s.buffered += frame;s.bytes+=size;pump(s) }
            }
            else -> Unit
        }
    }

    private fun initialize(s: Session, winner: String) {
        s.phase=Phase.MOUNTING;s.winner=winner;s.seed=null
        command(s) { EditorCommand.Initialize(s.context,it,s.source.content!!,winner,!writesEnabled) }
        status(RoomStatus.MOUNTING)
    }

    fun onEditorEvent(event: EditorEvent): List<RoomEffect> = effects {
        val s=session ?: return@effects
        if(event.context != s.context) return@effects
        if(event is EditorEvent.Disposed) {
            if(s.phase != Phase.DISPOSING || event.requestId != s.request) return@effects
            val restart=s.rejoin
            session=null
            s.finalStatus?.let(::status)
            if(restart) start()
            return@effects
        }
        if(event is EditorEvent.Flushed) {
            if(event.requestId != s.flushRequested || s.phase == Phase.DISPOSING) return@effects
            accept(s,event.snapshot,allowRetired=true)
            s.flushed=true
            if(s.phase == Phase.RETIRING) { persist(s);dispose(s);return@effects }
            // VS-72 can emit a semantic no-op update with no Changed. Flush drained callbacks.
            s.unmatchedLocalUpdate=false
            s.queue?.changed(event.snapshot.markdown,nowMillis())
            if(s.queue?.view()?.dirty == true) checkpoint(s)
            save(s,force=true);pump(s);barrier(s);return@effects
        }
        if(event is EditorEvent.Blocked) {
            persist(s,force=true) // Includes pending/buffered incoming bytes, not just port diagnostics.
            event.snapshot?.let { s.latest=it }
            recovery.persist(s.namespace,s.path,s.context,s.latest,event.raw)
            s.queue?.stop();s.token=null;s.rejoin=false;intent=null
            repository.revokeRoom(owner,s.context.connectionGeneration,s.context.sid)
            s.finalStatus=RoomStatus.SCHEMA_BLOCKED
            dispose(s);return@effects
        }
        if(s.phase in setOf(Phase.RETIRING,Phase.DISPOSING)) {
            // Earlier model work/IME may complete during an offline/reset flush; recover it only.
            if(event is EditorEvent.Changed) { accept(s,event.snapshot,true);persist(s) }
            return@effects
        }
        when(event) {
            is EditorEvent.Prepared -> if(s.phase == Phase.PREPARING && event.requestId == s.request) {
                require(event.clientId in 0..FrameCodec.MAX_INTEGER)
                FrameCodec.encode(ClientFrame.Seed(s.context.sid,event.seed))
                s.seed=event.seed;s.phase=Phase.JOINING
                send(ClientFrame.Join(s.context.sid,s.path,event.clientId,s.source.rev))
            }
            is EditorEvent.Ready -> if(s.phase == Phase.MOUNTING && event.requestId == s.request) {
                s.phase=Phase.READY
                s.queue!!.initialize(event.snapshot.markdown,nowMillis())
                accept(s,event.snapshot)
                // The native port normally returns gen=0 (queued updates have not been sent).
                // A model reporting pre-render work MUST retain debt, not silently rebaseline it.
                if(event.snapshot.generation > 0 && !s.queue!!.view().dirty) {
                    s.queue=ScribeQueue(s.source.content!!,true,s.scribe).also { it.initialize(event.snapshot.markdown,nowMillis()) }
                }
                if(s.queue!!.view().dirty) checkpoint(s)
                status(if(writesEnabled) RoomStatus.EDITING else RoomStatus.WRITE_GATE_CLOSED)
                pump(s)
                if(s.closeRequested) requestFlush(s)
            }
            is EditorEvent.Applied -> if(s.phase == Phase.READY && s.applying == event.requestId) {
                s.applying=null;s.appliedRaw=null
                val old=s.latest
                accept(s,event.snapshot)
                if(old?.markdown != event.snapshot.markdown) { s.queue!!.changed(event.snapshot.markdown,nowMillis());checkpoint(s) }
                pump(s);barrier(s)
            }
            is EditorEvent.LocalUpdate -> if(s.phase == Phase.READY && writesEnabled && online && !s.flushed) {
                val effect=send(ClientFrame.Update(s.context.sid,event.update))
                s.updateSends+=effect.serial;s.unmatchedLocalUpdate=true
            }
            is EditorEvent.Changed -> if(s.phase == Phase.READY) {
                if(!writesEnabled || !s.unmatchedLocalUpdate) {
                    intent=null;retire(s,false,false);s.finalStatus=RoomStatus.SCHEMA_BLOCKED
                    status(RoomStatus.SCHEMA_BLOCKED);return@effects
                }
                accept(s,event.snapshot);s.unmatchedLocalUpdate=false
                s.queue!!.changed(event.snapshot.markdown,nowMillis());checkpoint(s)
                status(RoomStatus.ROOM_ONLY);save(s)
            }
            is EditorEvent.Awareness -> if(s.phase == Phase.READY && online) send(ClientFrame.Awareness(s.context.sid,event.update))
        }
    }

    private fun accept(s: Session, snapshot: RoomSnapshot, allowRetired: Boolean = false) {
        require(snapshot.generation in 0..FrameCodec.MAX_INTEGER && snapshot.generation >= s.lastEditorGeneration)
        FrameCodec.encode(ClientFrame.Put(0,s.path,snapshot.markdown,0))
        check(snapshot.generation != s.lastEditorGeneration || s.latest?.markdown == snapshot.markdown)
        if(allowRetired && s.phase == Phase.RETIRING && s.latest?.markdown != snapshot.markdown) s.unconfirmedSnapshot=true
        s.lastEditorGeneration=snapshot.generation;s.latest=snapshot
        if(!allowRetired && s.phase == Phase.READY && s.queue?.view()?.initialized == true && s.flushed)
            s.queue?.changed(snapshot.markdown,nowMillis())
    }

    private fun checkpoint(s: Session) {
        val snapshot=s.latest ?: return
        repository.checkpointRoom(s.scope,s.context.sid,snapshot.markdown)
    }
    private fun persist(s: Session, force: Boolean = false) {
        val updates=listOfNotNull(s.appliedRaw) + s.buffered.mapNotNull { (it as? ServerFrame.Update)?.update }
        val dirty=s.queue?.view()?.dirty == true || s.unconfirmedSnapshot || s.unmatchedLocalUpdate || s.updateSends.isNotEmpty() ||
            repository.view().pending[s.path] != null
        val raw=(if(force || dirty || s.latest == null || updates.isNotEmpty()) listOfNotNull(s.winner) else emptyList()) + updates
        if(!force && !dirty && raw.isEmpty()) return
        val signature=documentHash((s.latest?.let { "${it.generation}:${it.markdown.length}:${it.markdown}" } ?: "none") + raw.joinToString(":") { "${it.length}:$it" })
        if(signature == s.recoveredSignature) return
        recovery.persist(s.namespace,s.path,s.context,s.latest,raw)
        s.recoveredSignature=signature
    }

    private fun pump(s: Session) {
        if(s.phase != Phase.READY || s.applying != null) return
        while(s.buffered.isNotEmpty()) {
            when(val f=s.buffered.removeFirst()) {
                is ServerFrame.Update -> {
                    s.bytes-=f.update.length;s.appliedRaw=f.update
                    s.applying=command(s) { EditorCommand.ApplyUpdate(s.context,it,f.update) }
                    return
                }
                is ServerFrame.Awareness -> { s.bytes-=f.update.length;command(s) { EditorCommand.Awareness(s.context,it,f.update) } }
                is ServerFrame.PeerJoined -> { s.bytes-=32;command(s) { EditorCommand.PublishAwareness(s.context,it) } }
                is ServerFrame.PeerLeft -> { s.bytes-=32;command(s) { EditorCommand.PeerLeft(s.context,it,f.clientId) } }
                else -> Unit
            }
        }
        save(s,force=s.closeRequested && s.flushed)
    }

    private fun save(s: Session, force: Boolean = false) {
        if(!writesEnabled || !online || s.phase != Phase.READY || s.applying != null || s.buffered.isNotEmpty() ||
            s.updateSends.isNotEmpty() || s.unmatchedLocalUpdate) return
        val q=s.queue ?: return
        val proposal=q.nextSnapshot(nowMillis(),force) ?: return
        val token=s.token ?: repository.confirmRoomReady(owner,generation,s.context.sid,s.epoch!!).also { s.token=it }
        repository.queueRoomSnapshot(token,proposal.markdown)
        val effect=repository.nextWrite(s.scope,s.path)
        if(effect == null) {
            if(repository.view().pending[s.path] == null && repository.view().documents[s.path]?.content == proposal.markdown)
                q.materialized(proposal.markdown)
            return
        }
        q.sent(proposal,(effect.frame as ClientFrame.Put).id)
        send(effect.frame,s.context,effect);status(RoomStatus.SAVING)
    }

    fun close(): List<RoomEffect> = effects {
        intent=null
        val s=session ?: run { releaseHeld();status(RoomStatus.CLOSED_CONFIRMED);return@effects }
        s.closeRequested=true;s.deadline=nowMillis()+closeTimeoutMillis
        status(RoomStatus.CLOSING)
        if(s.phase in setOf(Phase.RETIRING,Phase.DISPOSING)) {
            s.rejoin=false;s.holdMembership=false
            if(s.finalStatus != RoomStatus.CLOSED_CONFIRMED) s.finalStatus=RoomStatus.CLOSED_RECOVERY_UNCONFIRMED
            releaseHeld()
        }
        if(s.phase == Phase.READY) requestFlush(s)
        else if(s.phase in setOf(Phase.PREPARING,Phase.JOINING)) finish(s,RoomStatus.CLOSED_CONFIRMED)
    }

    /** The renderer no longer exists, so Flush cannot truthfully be acknowledged. Preserve the
     * newest native checkpoint and raw pending bytes BEFORE releasing membership. Never invent
     * Flushed/Disposed or replay this old epoch into its replacement (protocol §§8–9). */
    fun editorLost(context: EditorContext?, snapshot: RoomSnapshot? = null): List<RoomEffect> = effects {
        val s=session ?: return@effects
        if(context != null && context != s.context) return@effects
        snapshot?.takeIf { it.generation >= s.lastEditorGeneration }?.let { accept(s,it,true) }
        intent=null
        s.queue?.stop();s.token=null
        repository.revokeRoom(owner,s.context.connectionGeneration,s.context.sid)
        persist(s,force=true)
        repository.retireRoom(s.scope,s.context.sid,discardCheckpoint=false)
        releaseHeld()
        if(online && s.context.connectionGeneration == generation) send(ClientFrame.Leave(s.context.sid),s.context)
        session=null
        status(RoomStatus.CLOSED_RECOVERY_UNCONFIRMED)
    }
    /** Host queue overflow is a recovery-only flush, never a reason to save a partial update set. */
    fun editorOverloaded(context: EditorContext): List<RoomEffect> = effects {
        val s=session ?: return@effects
        if(s.context != context) return@effects
        intent=null;s.closeRequested=true
        retire(s,rejoin=false,hold=false)
        status(RoomStatus.RECOVERY_UNCONFIRMED)
    }
    private fun requestFlush(s: Session) {
        if(s.flushRequested != null) return
        s.flushRequested=command(s) { EditorCommand.Flush(s.context,it) }
    }
    private fun barrier(s: Session): Boolean {
        if(!s.closeRequested || !s.flushed || s.phase != Phase.READY || s.applying != null || s.buffered.isNotEmpty() ||
            s.updateSends.isNotEmpty() || s.unmatchedLocalUpdate) return false
        s.queue?.nextSnapshot(nowMillis(),true) // settles exact byte-identical reverts without put
        if(s.queue?.view()?.canLeave != true || repository.view().pending[s.path] != null) return false
        finish(s,RoomStatus.CLOSED_CONFIRMED)
        return true
    }
    private fun finish(s: Session, result: RoomStatus) {
        s.finalStatus=result;s.rejoin=false;s.holdMembership=false
        repository.retireRoom(s.scope,s.context.sid,discardCheckpoint=result == RoomStatus.CLOSED_CONFIRMED)
        dispose(s)
    }
    private fun retire(s: Session, rejoin: Boolean, hold: Boolean) {
        if(s.phase in setOf(Phase.RETIRING,Phase.DISPOSING)) { if(!hold) s.holdMembership=false;return }
        val hadBinding=s.phase in setOf(Phase.MOUNTING,Phase.READY)
        s.queue?.stop();s.token=null;s.rejoin=rejoin;s.holdMembership=hold && online
        s.phase=Phase.RETIRING
        repository.revokeRoom(owner,s.context.connectionGeneration,s.context.sid)
        persist(s) // disk failures propagate; no disposal/replacement/false success
        s.finalStatus=if(s.closeRequested) RoomStatus.CLOSED_RECOVERY_UNCONFIRMED else RoomStatus.RECOVERY_UNCONFIRMED
        if(hadBinding) {
            // A previous close flush may predate later remote applies; checkpoint again.
            if(s.flushed) { s.flushed=false;s.flushRequested=null }
            requestFlush(s)
        } else dispose(s)
    }
    private fun dispose(s: Session) {
        if(s.phase == Phase.DISPOSING) return
        if(!s.rejoin) releaseHeld()
        if(s.holdMembership && online && s.context.connectionGeneration == generation && s.epoch != null) held=s.context
        else if(online && s.context.connectionGeneration == generation) send(ClientFrame.Leave(s.context.sid),s.context)
        s.phase=Phase.DISPOSING
        command(s) { EditorCommand.Dispose(s.context,it) }
    }
    private fun releaseHeld() {
        held?.takeIf { online && it.connectionGeneration == generation }?.let { send(ClientFrame.Leave(it.sid),it) }
        held=null
    }

    fun tick(): List<RoomEffect> = effects {
        val s=session ?: return@effects
        if(s.closeRequested && s.deadline?.let { nowMillis() >= it } == true && s.phase != Phase.DISPOSING) {
            // Never destroy a live binding before its final composition/checkpoint returns.
            retire(s,rejoin=false,hold=false)
            status(RoomStatus.RECOVERY_UNCONFIRMED)
        } else { save(s);barrier(s) }
    }

    fun isCurrent(effect: RoomEffect.Network): Boolean {
        if(!online || effect.generation != generation) return false
        effect.documentSend?.let {
            if(effect.frame is ClientFrame.Put) {
                val s=session ?: return false
                if(effect.context != s.context || s.phase != Phase.READY) return false
            }
            return repository.isCurrent(it)
        }
        if(effect.frame is ClientFrame.Leave) return true // retiring membership, same connection only
        val s=session ?: return false
        return effect.context == s.context && s.phase !in setOf(Phase.RETIRING,Phase.DISPOSING)
    }
    fun onSendResult(effect: RoomEffect.Network, result: SendResult): List<RoomEffect> = effects {
        if(effect.generation != generation) return@effects
        if(effect.frame is ClientFrame.Leave) return@effects
        if(effect.context != null && effect.context != session?.context) return@effects
        effect.documentSend?.let { repository.onSendResult(owner,effect.generation,(effect.frame as? ClientFrame.Put)?.id,result) }
        val s=session
        if(result != SendResult.ENQUEUED) {
            s?.let { retire(it,intent != null,false) };online=false;status(RoomStatus.RECONNECT_REQUIRED)
        } else if(s != null && effect.context == s.context && s.updateSends.remove(effect.serial)) {
            save(s,force=s.closeRequested && s.flushed);barrier(s)
        }
    }
}
