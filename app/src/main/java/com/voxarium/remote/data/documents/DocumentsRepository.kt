package com.voxarium.remote.data.documents

import com.voxarium.remote.data.transport.*
import com.voxarium.remote.data.security.SavedProfile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Native, synchronous single-owner repository (protocol §§3,6–8).
 * Call on an ordered worker, NOT the UI thread; returned sends are effects, never implicit IO.
 * One instance owns its storage. Reads and mutations are synchronized; persist-before-send.
 */
class DocumentsRepository(
    private val storage: DocumentStorage,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private var selection = 0L
    private var endpoint: RemoteEndpoint? = null
    private var namespace: DocumentNamespace? = null
    private var journalKey = ""
    private var journal = Journal()
    private var mirror = Mirror()
    private var connection = -1L
    private var connectionFence = -1L
    private var retiredThrough = -1L
    private var lastHello = -1L
    private var syncing = false
    private var ready = false
    private var userLabel = "user"
    private var journalLoaded = false
    private var expectedAccount: String? = null
    private var partition: DocumentPartition? = null
    private var advertisedSeq = 0L
    private var profileBindingKey: String? = null
    private val rooms = mutableMapOf<String, Room>()
    private val eligible = mutableMapOf<String, String>()
    private val earlyScribes = mutableSetOf<String>()
    private data class Room(
        val sid: String, val path: String, val epoch: String, val rev: Long,
        var seeded: Boolean, var scribe: Boolean, var token: RoomWriteToken? = null,
        var checkpointGeneration: Long? = null,
    )
    private fun failure(reason: DocumentFailure): Nothing = throw DocumentRepositoryException(reason)
    private fun activeSpace() = namespace?.spaceId ?: failure(DocumentFailure.NOT_SYNCED)
    private fun writable() { if(!journalLoaded) failure(DocumentFailure.CORRUPT_JOURNAL) }
    private fun owner(expected: Long) { if(expected != selection) failure(DocumentFailure.STALE_ROOM) }
    private fun scope(scope: DocumentScope) {
        owner(scope.owner);writable()
        if(scope.namespace != namespace) failure(DocumentFailure.STALE_ROOM)
    }
    private fun space(j: Journal = journal) = j.spaces[activeSpace()] ?: SpaceJournal()
    private fun withSpace(s: SpaceJournal, j: Journal = journal) = j.copy(spaces = j.spaces + (activeSpace() to s))
    private fun save(j: Journal) {
        writable()
        storage.writeDrafts(journalKey, DocumentStateCodec.journal(j))
        journal = j
    }
    private fun cache(m: Mirror) {
        storage.writeCache(namespace!!.storageKey(), DocumentStateCodec.mirror(m))
        mirror = m
    }
    private fun counter(n: Long): Long = if(n < FrameCodec.MAX_INTEGER) n + 1 else failure(DocumentFailure.STORAGE)
    private fun validate(path: String, content: String) {
        try { require(path == path.trim()); FrameCodec.encode(ClientFrame.Put(0,path,content,0)) }
        catch (_: Exception) { failure(DocumentFailure.INVALID_FRAME) }
    }
    private fun same(v: DocumentVersion?, text: String) = v != null && !v.deleted && v.hash == documentHash(text) && v.content == text
    private fun newest(old: DocumentVersion?, incoming: DocumentVersion): DocumentVersion {
        if(old != null && old.rev == incoming.rev && old != incoming) failure(DocumentFailure.INVALID_FRAME)
        return if(old != null && old.rev > incoming.rev) old else incoming
    }
    private fun verify(v: DocumentVersion) {
        try {
            require(v.rev in 0..FrameCodec.MAX_INTEGER)
            require(if(v.deleted) v.hash == null && v.content == null else v.content != null && v.hash == documentHash(v.content))
        } catch (_: Exception) { failure(DocumentFailure.INVALID_FRAME) }
    }
    private fun retireRooms(path: String? = null) {
        if(path == null) { rooms.clear(); eligible.clear(); earlyScribes.clear() }
        else { rooms.entries.removeAll { it.value.path == path }; eligible.remove(path) }
    }

    /** Call BEFORE connect/profile switch. Capture this token in ALL transport callbacks. */
    @Synchronized fun select(endpoint: RemoteEndpoint, offlineNamespace: DocumentNamespace? = null): Long {
        if(offlineNamespace?.storageEpoch != null) failure(DocumentFailure.INVALID_FRAME)
        if(offlineNamespace != null && offlineNamespace.server != endpoint.serverUrl) failure(DocumentFailure.INVALID_FRAME)
        selection = counter(selection)
        this.endpoint = endpoint
        namespace = null; journalLoaded=false
        profileBindingKey=null; partition=null; advertisedSeq=0
        expectedAccount=offlineNamespace?.accountId
        journal = Journal(); mirror = Mirror(); connection = -1; connectionFence = -1; retiredThrough = -1; lastHello = -1
        ready = false; syncing = false; retireRooms()
        if(offlineNamespace != null) loadOffline(offlineNamespace)
        return selection
    }

    /** Android identity design §§3,5.3: exact immutable partition, no implicit migration.
     * Selection only loads local data; fresh authenticated hello + sync are still required. */
    @Synchronized fun selectPartition(partition: DocumentPartition): Long {
        select(RemoteEndpoint.create(partition.canonicalServerUrl)) // fence before any failing IO
        try {
            storage.exclusive {
                val recordKey=listOf("identity-migration.v2",partition.canonicalServerUrl,partition.accountId)
                    .joinToString("") { "${it.length}:$it" }
                val claimed=storage.readDrafts(recordKey)?.let {
                    DocumentStateCodec.migrationClaimsPartition(it,partition)
                } ?: false
                val metadataBytes=storage.readDrafts("partition-metadata.v2:" + partition.namespace.storageKey())
                metadataBytes?.let { bytes ->
                    val metadata=DocumentStateCodec.strictObject(bytes)
                    require(metadata.get("v") == 2 && metadata.get("canonicalizer") == 1 &&
                        metadata.get("server") == partition.canonicalServerUrl && metadata.get("project") == partition.projectId &&
                        metadata.get("account") == partition.accountId && metadata.get("epoch") == partition.storageEpoch)
                }
                // The PREPARED record reserves imported queues even when a crash interrupts
                // the copy before metadata. No copied bytes are read/adopted before activation.
                if(claimed || metadataBytes != null) {
                    if(!IdentityMigration.isActivated(storage,partition)) failure(DocumentFailure.NOT_SYNCED)
                    require(metadataBytes != null)
                }
                val loadedJournal=storage.readDrafts(partition.journalKey())?.let(DocumentStateCodec::journal) ?: Journal()
                // A v2 journal is never an account-wide legacy journal, even when labels match.
                require(loadedJournal.spaces.keys.all { it == partition.projectId } &&
                    loadedJournal.spaces.values.all { it.recoveries.all { r -> r.spaceId == partition.projectId } })
                val loadedMirror=storage.readCache(partition.namespace.storageKey())?.let {
                    try { DocumentStateCodec.mirror(it) } catch (_: Exception) { null }
                } ?: Mirror()
                // Publish only a fully loaded selection. Every failed load keeps the prior
                // owner fenced but leaves neither a callback route nor staged readable state.
                this.partition=partition; namespace=partition.namespace; expectedAccount=partition.accountId
                journalKey=partition.journalKey(); journal=loadedJournal; mirror=loadedMirror
                journalLoaded=true
            }
        } catch(error: Exception) {
            endpoint=null; this.partition=null; namespace=null; journalKey=""
            journal=Journal(); mirror=Mirror(); journalLoaded=false; ready=false; syncing=false; retireRooms()
            if(error is DocumentRepositoryException) throw error
            if(error is DocumentStorageException && error.reason != DocumentStorageException.Reason.CORRUPT)
                failure(DocumentFailure.STORAGE)
            failure(DocumentFailure.CORRUPT_JOURNAL)
        }
        return selection
    }

    /** Foundation read-only v2 adapter. No hello, room grant, sync, or send can revive a
     * migrated queue. A5 must add fresh exact-epoch verification/reconciliation explicitly. */
    @Synchronized fun selectPartitionOffline(partition: DocumentPartition): Long {
        select(RemoteEndpoint.create(partition.canonicalServerUrl, partition.projectId))
        endpoint = null // all legacy transport callbacks fail closed for this selection
        try {
            if (!IdentityMigration.isActivated(storage, partition)) failure(DocumentFailure.NOT_SYNCED)
        } catch (e: DocumentRepositoryException) { throw e }
        catch (_: Exception) { failure(DocumentFailure.CORRUPT_JOURNAL) }
        namespace = partition.namespace
        journalKey = partition.journalKey()
        journal = storage.readDrafts(journalKey)?.let {
            try { DocumentStateCodec.journal(it) } catch (_: Exception) { failure(DocumentFailure.CORRUPT_JOURNAL) }
        } ?: Journal()
        mirror = storage.readCache(partition.namespace.storageKey())?.let {
            try { DocumentStateCodec.mirror(it) } catch (_: Exception) { null }
        } ?: Mirror()
        expectedAccount = partition.accountId
        // Reader/recovery only: do not allow offline checkpoints to overwrite the imported queue.
        journalLoaded = false
        return selection
    }

    private fun loadOffline(offlineNamespace: DocumentNamespace) {
            // Explicit last-authenticated identity supplied by the native profile owner. Never
            // guess an account from a server/space name, nor grant any network write offline.
            namespace=offlineNamespace
            journalKey=DocumentNamespace(endpoint!!.serverUrl,offlineNamespace.accountId,"@journal").storageKey()
            journal=storage.readDrafts(journalKey)?.let {
                try { DocumentStateCodec.journal(it) } catch (_: Exception) { failure(DocumentFailure.CORRUPT_JOURNAL) }
            } ?: Journal()
            mirror=storage.readCache(offlineNamespace.storageKey())?.let {
                try { DocumentStateCodec.mirror(it) } catch (_: Exception) { null }
            } ?: Mirror()
            expectedAccount=offlineNamespace.accountId
            journalLoaded=true
    }

    /** Persistent native profile→last-authenticated namespace association, no credential contents.
     * Changing URL/space/credential reference cannot select a previous account's offline cache.
     * useOfflineCache=false is an explicit fresh identity selection, never a destructive reset. */
    @Synchronized fun selectProfile(profile: SavedProfile, useOfflineCache: Boolean = true): Long {
        val target=RemoteEndpoint.create(profile.canonicalServerUrl,profile.spaceName)
        select(target) // fence the previous binding BEFORE any disk read can fail
        val key=listOf("profile-binding.v1",target.serverUrl,target.space,profile.id,profile.credentialId)
            .joinToString("") { "${it.length}:$it" }
        profileBindingKey=key
        if(useOfflineCache) {
            val saved=storage.readDrafts(key)?.let {
                try { DocumentStateCodec.identity(it) } catch (_: Exception) { failure(DocumentFailure.CORRUPT_JOURNAL) }
            }
            if(saved != null) {
                if(saved.server != target.serverUrl) failure(DocumentFailure.CORRUPT_JOURNAL)
                loadOffline(saved)
            }
        }
        return selection
    }

    /** Generation/profile fencing includes already queued callbacks outside RemoteConnection. */
    @Synchronized fun onEvent(owner: Long, event: ConnectionEvent): List<DocumentSend> {
        if(owner != selection || endpoint == null || (partition != null && !journalLoaded) ||
            event.generation < connectionFence) return emptyList()
        return try { handleEvent(event) } catch(error: Exception) {
            // Failed load/application is fail-closed, including semantically corrupt journal JSON.
            ready=false;syncing=false;journalLoaded=false;retireRooms()
            throw error
        }
    }

    private fun handleEvent(event: ConnectionEvent): List<DocumentSend> {
        when(event) {
            is ConnectionEvent.State -> {
                if(event.generation > connectionFence) {
                    connectionFence = event.generation; ready = false; syncing = false; retireRooms()
                }
                if(event.phase != ConnectionPhase.READY) {
                    ready = false; syncing = false; retireRooms()
                    if(event.phase == ConnectionPhase.BACKOFF || event.phase == ConnectionPhase.STOPPED) {
                        connection = -1; retiredThrough = maxOf(retiredThrough,event.generation)
                    }
                }
            }
            is ConnectionEvent.Frame -> {
                if(event.frame is ServerFrame.Hello) return hello(event.generation,event.frame)
                if(event.generation != connection || !ready) return emptyList()
                apply(event.frame)
            }
        }
        return emptyList()
    }

    private fun hello(generation: Long, h: ServerFrame.Hello): List<DocumentSend> {
        if(partition != null) return partitionHello(generation,h)
        if(h.protocol != FrameCodec.PROTOCOL_VERSION.toLong() || h.space.storageEpoch != null) failure(DocumentFailure.INVALID_FRAME)
        if(generation <= lastHello || generation <= retiredThrough) return emptyList()
        if(expectedAccount != null && expectedAccount != h.user.id) failure(DocumentFailure.INVALID_FRAME)
        require(h.user.id.isNotEmpty() && h.space.id.isNotEmpty() && h.space.name == endpoint!!.space)
        expectedAccount=h.user.id
        connectionFence = generation; connection = generation; lastHello = generation; ready = false; syncing = false; retireRooms()
        journalLoaded=false
        namespace = DocumentNamespace(endpoint!!.serverUrl,h.user.id,h.space.id)
        mirror=Mirror()
        // Account journal holds separate space.id entries plus a name→id history. It never crosses accounts.
        journalKey = DocumentNamespace(endpoint!!.serverUrl,h.user.id,"@journal").storageKey()
        journal = storage.readDrafts(journalKey)?.let {
            try { DocumentStateCodec.journal(it) } catch (_: Exception) { failure(DocumentFailure.CORRUPT_JOURNAL) }
        } ?: Journal()
        journalLoaded=true
        val oldId = journal.lastSpace[h.space.name]
        var next = journal
        if(oldId != null && oldId != h.space.id) {
            val old = next.spaces[oldId] ?: SpaceJournal()
            var recovered = old.recoveries
            // Retain BOTH old in-flight snapshot and a newer local generation.
            val variants = old.drafts.values.map { Triple(it.path,it.content,it.generation) } +
                old.pending.values.map { Triple(it.path,it.content,it.generation) }
            for((path,text,gen) in variants.distinct()) {
                recovered = recovered + DocumentRecovery(next.nextId,oldId,path,text,gen,null,RecoveryReason.SPACE_REPLACED,null)
                next = next.copy(nextId=counter(next.nextId))
            }
            next = next.copy(spaces=next.spaces + (oldId to SpaceJournal(recoveries=recovered)))
        }
        next = next.copy(lastSpace=next.lastSpace + (h.space.name to h.space.id))
        val changedIdentity = oldId != null && oldId != h.space.id
        // Invalidate a reused namespace BEFORE recording the new binding. On crash the old
        // history remains and repeats the invalidation; old drafts are still in the journal.
        if(changedIdentity) cache(Mirror())
        save(next) // Recovery/history first; no server action before this succeeds.
        mirror = if(changedIdentity) Mirror() else storage.readCache(namespace!!.storageKey())?.let {
            try { DocumentStateCodec.mirror(it) } catch (_: Exception) { null }
        } ?: Mirror()
        if(!mirror.complete) cache(Mirror())
        userLabel = h.user.name.filter { it.isLetterOrDigit() || it in "_-" }.take(24).ifEmpty { "user" }
        profileBindingKey?.let { storage.writeDrafts(it,DocumentStateCodec.identity(namespace!!)) }
        ready = true; syncing = true
        // hello.seq is advertised, NOT applied. Only a durable Synced establishes the watermark.
        return listOf(DocumentSend(connection,ClientFrame.Sync(if(mirror.complete) mirror.cursor else 0),selection))
    }

    private fun partitionHello(generation: Long, h: ServerFrame.Hello): List<DocumentSend> {
        if(generation <= lastHello || generation <= retiredThrough) return emptyList()
        val target = partition!!
        if(h.protocol != FrameCodec.LIVE_PROTOCOL_VERSION.toLong() || h.user.id != target.accountId ||
            h.space.id != target.projectId || h.space.storageEpoch != target.storageEpoch ||
            h.seq !in 0..FrameCodec.MAX_INTEGER) failure(DocumentFailure.INVALID_FRAME)
        connectionFence=generation; connection=generation; lastHello=generation
        ready=false; syncing=false; retireRooms()
        advertisedSeq=h.seq
        if(h.seq < maxOf(mirror.cursor,mirror.files.values.maxOfOrNull { it.rev } ?: 0)) {
            // Recovery first, replaceable mirror second. A crash at either boundary can only
            // repeat full reconciliation; it can never confirm a pending hash from old cache.
            val variants = space().drafts.values.map { Triple(it.path,it.content,it.generation) } +
                space().pending.values.map { Triple(it.path,it.content,it.generation) } +
                // An ACK/change can be durable ahead of the last sync cursor. Preserve its
                // now-missing confirmed bytes too; generation 0 denotes no surviving draft.
                mirror.files.filter { (path,v) -> v.rev > mirror.cursor && v.rev > h.seq && !v.deleted &&
                    path !in space().drafts && path !in space().pending }.map { (path,v) -> Triple(path,v.content!!,0L) }
            var next=journal
            var recoveries=space().recoveries
            for((path,text,gen) in variants.distinct()) {
                if(recoveries.none { it.reason == RecoveryReason.SEQUENCE_ROLLBACK && it.path == path && it.content == text && it.generation == gen }) {
                    recoveries=recoveries+DocumentRecovery(next.nextId,target.projectId,path,text,gen,null,RecoveryReason.SEQUENCE_ROLLBACK,null)
                    next=next.copy(nextId=counter(next.nextId))
                }
            }
            save(withSpace(space().copy(recoveries=recoveries),next))
            cache(Mirror())
        }
        if(!mirror.complete) cache(Mirror())
        userLabel=h.user.name.filter { it.isLetterOrDigit() || it in "_-" }.take(24).ifEmpty { "user" }
        ready=true; syncing=true
        return listOf(DocumentSend(connection,ClientFrame.Sync(if(mirror.complete) mirror.cursor else 0),selection,namespace=namespace))
    }

    private fun apply(f: ServerFrame) {
        when(f) {
            is ServerFrame.Change -> {
                validate(f.path,f.content ?: "")
                val v=DocumentVersion(f.rev,f.hash,f.deleted,f.content); verify(v)
                val incoming=newest(mirror.files[f.path],v)
                val d=space().drafts[f.path]
                if(d != null && f.path !in space().pending && !syncing) {
                    if(same(incoming,d.content)) cache(mirror.copy(files=mirror.files + (f.path to incoming)))
                    reconcileDraft(d,incoming,RecoveryReason.CONFLICT)
                }
                cache(mirror.copy(files=mirror.files + (f.path to incoming)))
                // Change has no room incarnation/sid. Conservatively fence a writer now,
                // not one callback later at Reset, while preserving any in-flight draft.
                retireRooms(f.path)
            }
            is ServerFrame.Synced -> {
                if(!syncing || f.seq < mirror.cursor || (partition != null && f.seq < advertisedSeq) || f.seq !in 0..FrameCodec.MAX_INTEGER) failure(DocumentFailure.INVALID_FRAME)
                if(mirror.files.values.any { it.rev > f.seq }) failure(DocumentFailure.INVALID_FRAME)
                // Reconcile UNKNOWN sends through current hash; never replay a live/sid from disk.
                for(p in space().pending.values.toList()) {
                    val remote=mirror.files[p.path]
                    if(same(remote,p.content)) finishKnown(p,remote!!)
                    else reject(p,remote,RecoveryReason.OFFLINE_OR_RESTART)
                }
                for(d in space().drafts.values.toList()) {
                    if(d.kind == DraftKind.CONFLICT_COPY && mirror.files[d.path] == null) continue
                    val remote=mirror.files[d.path]
                    if(remote != null || d.base != null || d.kind == DraftKind.ROOM)
                        reconcileDraft(d,remote,RecoveryReason.OFFLINE_OR_RESTART)
                }
                cache(mirror.copy(complete=true,cursor=f.seq))
                syncing = false
            }
            is ServerFrame.Ack -> {
                if(syncing) return
                val p=matching(f.id,f.path) ?: return
                if(f.deleted || f.hash != p.hash) failure(DocumentFailure.INVALID_FRAME)
                finishKnown(p,DocumentVersion(f.rev,f.hash,false,p.content))
            }
            is ServerFrame.Nack -> {
                if(syncing) return
                val p=matching(f.id,f.path) ?: return
                f.current?.let(::verify)
                val remote=f.current?.let { newest(mirror.files[f.path],it) } ?: mirror.files[f.path]
                reject(p,remote,RecoveryReason.REJECTED)
                if(remote != null) cache(mirror.copy(files=mirror.files + (f.path to remote)))
            }
            is ServerFrame.Error -> {
                if(syncing) return
                val p=space().pending.values.firstOrNull { (f.id as? FrameId.Integer)?.value == it.id } ?: return
                reject(p,mirror.files[p.path],RecoveryReason.REJECTED)
            }
            is ServerFrame.Joined -> {
                if(!syncing && f.path in mirror.files && !mirror.files.getValue(f.path).deleted && f.rev == mirror.files.getValue(f.path).rev)
                    rooms[f.sid]=Room(f.sid,f.path,f.epoch,f.rev,f.seeded,f.scribe || earlyScribes.remove(f.sid))
            }
            // A losing CAS also establishes the winning room state (protocol §4.1).
            is ServerFrame.Seeded -> if(f.ok || f.state != null) rooms[f.sid]?.seeded=true
            is ServerFrame.Scribe -> rooms[f.sid]?.let { it.scribe=true } ?: run { earlyScribes.add(f.sid) }
            is ServerFrame.Reset -> {
                val d=space().drafts[f.path]
                if(d != null) preserve(d,mirror.files[f.path],RecoveryReason.REMOTE_RESET)
                retireRooms(f.path)
            }
            is ServerFrame.JoinFailed -> retireRooms(f.path)
            else -> Unit // Room/Yjs bodies belong to the coordinator, not this mirror.
        }
    }

    private fun matching(id: FrameId, path: String): PendingDocumentWrite? {
        val p=space().pending[path] ?: return null
        return p.takeIf { (id as? FrameId.Integer)?.value == p.id && p.connectionGeneration == connection }
    }

    private fun finishKnown(p: PendingDocumentWrite, confirmed: DocumentVersion) {
        verify(confirmed)
        val remote=newest(mirror.files[p.path],confirmed)
        if(!same(remote,p.content)) {
            reject(p,remote,RecoveryReason.CONFLICT)
            cache(mirror.copy(files=mirror.files + (p.path to remote)))
            return
        }
        // Cache/base first, THEN discard only the confirmed generation from the durable journal.
        // A crash between the files leaves an unknown snapshot, resolvable by sync/hash.
        cache(mirror.copy(files=mirror.files + (p.path to remote)))
        val s=space();val d=s.drafts[p.path]
        val drafts=if(d?.generation == p.generation) s.drafts-p.path else s.drafts
        save(withSpace(s.copy(drafts=drafts,pending=s.pending-p.path)))
    }

    private fun reconcileDraft(d: DocumentDraft, remote: DocumentVersion?, reason: RecoveryReason) {
        if(same(remote,d.content)) {
            val s=space();save(withSpace(s.copy(drafts=s.drafts-d.path)))
        } else { preserve(d,remote,reason); retireRooms(d.path) }
    }

    private fun reject(p: PendingDocumentWrite, remote: DocumentVersion?, reason: RecoveryReason) {
        val s=space();val d=s.drafts[p.path]
        // Do not lose a superseded in-flight variant when its newer draft is different.
        if(d != null && d.generation != p.generation) {
            val r=DocumentRecovery(journal.nextId,activeSpace(),p.path,p.content,p.generation,remote,reason,null)
            save(withSpace(s.copy(recoveries=s.recoveries+r),journal.copy(nextId=counter(journal.nextId))))
        }
        if(d != null) preserve(d,remote,reason)
        else preserve(DocumentDraft(p.path,p.content,p.generation,null,DraftKind.LOCAL),remote,reason)
        val latest=space();save(withSpace(latest.copy(pending=latest.pending-p.path)))
        retireRooms(p.path)
    }

    private fun occupied(path: String): Boolean {
        val names=mirror.files.keys + space().drafts.keys + space().pending.keys + space().recoveries.mapNotNull { it.copyPath }
        return names.any { it.equals(path,ignoreCase=true) }
    }

    private fun copyName(path: String): String {
        var source=path
        val visited=mutableSetOf<String>()
        while(visited.add(source)) {
            source=space().recoveries.lastOrNull { it.copyPath == source }?.path ?: break
        }
        val dir=source.substringBeforeLast('/',"").let { if(it.isEmpty()) "" else "$it/" }
        val name=source.substringAfterLast('/');val ext=name.substringAfterLast('.');val stem=name.substringBeforeLast('.')
        val fmt=SimpleDateFormat("yyyy-MM-dd HHmmss",Locale.ROOT).apply { timeZone=TimeZone.getTimeZone("UTC") }
        val tag=" (conflict $userLabel ${fmt.format(Date(nowMillis()))})"
        for(i in 1..10_000) {
            val suffix=if(i==1) "" else " $i"
            // Leave room for the conflict suffix; validate Unicode length through FrameCodec.
            var base=stem
            while(base.isNotEmpty()) {
                val candidate="$dir$base$tag$suffix.$ext"
                try { validate(candidate,"");if(!occupied(candidate)) return candidate;break }
                catch(e: DocumentRepositoryException) { if(e.reason != DocumentFailure.INVALID_FRAME) throw e }
                base=base.dropLast(if(base.last().isLowSurrogate() && base.length>1) 2 else 1)
            }
            if(base.isEmpty()) break
        }
        // A valid 512-codepoint source may leave no room for a suffix in its directory.
        // Keep the original path in recovery, use a short root-level hash-qualified fallback.
        val fallback="recovery-${documentHash(source).take(16)}"
        for(i in 1..10_000) {
            val suffix=if(i==1) "" else " $i"
            val candidate="$fallback$tag$suffix.$ext"
            validate(candidate,"")
            if(!occupied(candidate)) return candidate
        }
        failure(DocumentFailure.NAME_EXHAUSTED)
    }

    private fun preserve(d: DocumentDraft, remote: DocumentVersion?, reason: RecoveryReason): String {
        val name=copyName(d.path) // If exhausted, old draft/pending remain durable; NO network effects.
        val r=DocumentRecovery(journal.nextId,activeSpace(),d.path,d.content,d.generation,remote,reason,name)
        val copy=d.copy(path=name,base=null,kind=DraftKind.CONFLICT_COPY)
        val s=space()
        save(withSpace(s.copy(drafts=(s.drafts-d.path)+(name to copy),recoveries=s.recoveries+r),journal.copy(nextId=counter(journal.nextId))))
        eligible.remove(d.path)
        return name
    }

    /** Persistent local checkpoint only, online or offline. Never arms an original-path write. */
    @Synchronized fun commandScope(owner: Long): DocumentScope {
        owner(owner);writable()
        return DocumentScope(owner,namespace ?: failure(DocumentFailure.NOT_SYNCED))
    }

    @Synchronized fun saveDraft(scope: DocumentScope, path: String, content: String): Long {
        scope(scope);activeSpace();validate(path,content)
        val gen=journal.nextGeneration;val s=space()
        val d=DocumentDraft(path,content,gen,s.drafts[path]?.base ?: mirror.files[path],DraftKind.LOCAL)
        save(withSpace(s.copy(drafts=s.drafts+(path to d)),journal.copy(nextGeneration=counter(gen))))
        retireRooms(path)
        return gen
    }

    /** Explicit coordinator confirmation AFTER a compatible merged room has rendered. */
    @Synchronized fun confirmRoomReady(owner: Long, generation: Long, sid: String, epoch: String): RoomWriteToken {
        if(owner != selection || generation != connection || !ready || syncing) failure(DocumentFailure.STALE_ROOM)
        val r=rooms[sid] ?: failure(DocumentFailure.STALE_ROOM)
        if(r.epoch != epoch || !r.seeded || mirror.files[r.path]?.deleted != false) failure(DocumentFailure.STALE_ROOM)
        if(!r.scribe) failure(DocumentFailure.NOT_SCRIBE)
        r.token?.let { return it }
        // Never attach an offline/restart draft to a newly joined original. Preserve separately.
        space().drafts[r.path]?.takeIf { it.generation != r.checkpointGeneration }
            ?.let { preserve(it,mirror.files[r.path],RecoveryReason.OFFLINE_OR_RESTART) }
        return RoomWriteToken(UUID.randomUUID().toString()).also { r.token=it }
    }

    /** VS-76: persist the current merged room, including a non-scribe, without arming a put.
     * Unlike saveDraft this retains the current incarnation. Never adopts an offline draft.
     * Caller has already applied/preflighted the snapshot on its ordered editor port. */
    @Synchronized fun checkpointRoom(scope: DocumentScope, sid: String, content: String): Long {
        scope(scope)
        val r=rooms[sid] ?: failure(DocumentFailure.STALE_ROOM)
        if(!ready || syncing || !r.seeded) failure(DocumentFailure.STALE_ROOM)
        validate(r.path,content)
        space().drafts[r.path]?.takeIf { it.generation != r.checkpointGeneration }
            ?.let { preserve(it,mirror.files[r.path],RecoveryReason.OFFLINE_OR_RESTART) }
        val gen=journal.nextGeneration;val s=space()
        val d=DocumentDraft(r.path,content,gen,mirror.files[r.path],DraftKind.ROOM)
        save(withSpace(s.copy(drafts=s.drafts+(r.path to d)),journal.copy(nextGeneration=counter(gen))))
        r.checkpointGeneration=gen
        return gen
    }

    /** Only a real dirty materialized room snapshot (including inherited dirty debt), not open/seed. */
    @Synchronized fun queueRoomSnapshot(token: RoomWriteToken, content: String): Long {
        val r=rooms.values.firstOrNull { it.token === token } ?: failure(DocumentFailure.STALE_ROOM)
        if(!ready || syncing || !r.scribe || !r.seeded) failure(DocumentFailure.STALE_ROOM)
        validate(r.path,content)
        val gen=journal.nextGeneration;val s=space()
        val d=DocumentDraft(r.path,content,gen,mirror.files[r.path],DraftKind.ROOM)
        save(withSpace(s.copy(drafts=s.drafts+(r.path to d)),journal.copy(nextGeneration=counter(gen))))
        r.checkpointGeneration=gen
        eligible[r.path]=token.nonce
        return gen
    }

    /** discardCheckpoint is only for a coordinator's confirmed, fully flushed clean close. */
    @Synchronized fun retireRoom(scope: DocumentScope, sid: String, discardCheckpoint: Boolean = false) {
        scope(scope)
        rooms.remove(sid)?.let { r ->
            eligible.remove(r.path)
            val s=space()
            if(discardCheckpoint && r.path !in s.pending && s.drafts[r.path]?.generation == r.checkpointGeneration)
                save(withSpace(s.copy(drafts=s.drafts-r.path)))
        }
    }

    /** Coordinator terminal fence; safe even after namespace/connection replacement. No disk IO. */
    @Synchronized fun revokeRoom(owner: Long, generation: Long, sid: String) {
        if(owner != selection || generation != connection) return
        earlyScribes.remove(sid)
        rooms.remove(sid)?.let { eligible.remove(it.path) }
    }

    /** Explicit CAS create only, never restore/overwrite an existing original (even a tombstone). */
    @Synchronized fun queueCreate(scope: DocumentScope, path: String, content: String): Long {
        scope(scope)
        if(!ready || syncing || !mirror.complete) failure(DocumentFailure.NOT_SYNCED)
        validate(path,content)
        if(occupied(path)) failure(DocumentFailure.PATH_OCCUPIED)
        val gen=journal.nextGeneration;val s=space()
        val d=DocumentDraft(path,content,gen,null,DraftKind.CONFLICT_COPY)
        save(withSpace(s.copy(drafts=s.drafts+(path to d)),journal.copy(nextGeneration=counter(gen))))
        return gen
    }

    /** First save both versions locally; publishing the returned copy is a separate explicit action. */
    @Synchronized fun makeConflictCopy(scope: DocumentScope, path: String): String {
        scope(scope)
        if(path in space().pending) failure(DocumentFailure.IN_FLIGHT)
        val d=space().drafts[path] ?: failure(DocumentFailure.NO_DRAFT)
        return preserve(d,mirror.files[path],RecoveryReason.CONFLICT)
    }

    /** One immutable in-flight snapshot/path. Returns null for unchanged, unarmed or blocked drafts. */
    @Synchronized fun nextWrite(scope: DocumentScope, path: String): DocumentSend? {
        scope(scope)
        if(!ready || syncing || !mirror.complete) return null
        val s=space();if(path in s.pending) return null
        val d=s.drafts[path] ?: return null
        val remote=mirror.files[path]
        val room=rooms.values.firstOrNull { it.path==path && it.token?.nonce==eligible[path] && it.scribe && it.seeded }
        if(d.kind == DraftKind.ROOM && room == null) return null
        if(d.kind == DraftKind.LOCAL) return null
        if(same(remote,d.content)) {
            // A user may edit and then revert. Exact bytes already durable need no put/ack.
            save(withSpace(s.copy(drafts=s.drafts-path)));eligible.remove(path)
            return null
        }
        if(d.kind == DraftKind.CONFLICT_COPY && (remote != null || mirror.files.keys.any { it.equals(path,true) })) {
            preserve(d,remote,RecoveryReason.CONFLICT);return null
        }
        val p=PendingDocumentWrite(journal.nextId,path,d.content,d.generation,remote?.rev ?: 0,room?.sid,connection)
        val frame=ClientFrame.Put(p.id,path,p.content,p.baseRev,p.live)
        FrameCodec.encode(frame)
        save(withSpace(s.copy(pending=s.pending+(path to p)),journal.copy(nextId=counter(journal.nextId))))
        return DocumentSend(connection,frame,selection,room?.token?.nonce,if(partition != null) namespace else null)
    }

    /** Check immediately before send on the SAME ordered binding worker; never redirect effects. */
    @Synchronized fun isCurrent(effect: DocumentSend): Boolean {
        if(effect.owner != selection || effect.connectionGeneration != connection || !ready ||
            (partition != null && effect.namespace != namespace)) return false
        return when(val f=effect.frame) {
            is ClientFrame.Sync -> syncing && f.since == (if(mirror.complete) mirror.cursor else 0L)
            is ClientFrame.Put -> space().pending[f.path]?.let {
                it.id == f.id && it.content == f.content && it.baseRev == f.baseRev && it.live == f.live &&
                    (it.live == null || rooms[it.live]?.token?.nonce == effect.roomNonce && effect.roomNonce != null)
            } == true
            else -> false
        }
    }

    /** A local enqueue is NOT ack. Rejected sends remain UNKNOWN until a new sync/hash handshake. */
    @Synchronized fun onSendResult(owner: Long, generation: Long, id: Long?, result: SendResult) {
        if(owner != selection || generation != connection || result == SendResult.ENQUEUED) return
        if(id == null || space().pending.values.any { it.id == id }) {
            ready=false;syncing=false;retireRooms()
        }
    }

    @Synchronized fun view(): DocumentsView {
        val s=if(namespace==null) SpaceJournal() else space()
        val collision=mirror.files.keys.groupBy { it.lowercase(Locale.ROOT) }.values.filter { it.size>1 }.flatten().toSet()
        return DocumentsView(namespace,ready && !syncing && mirror.complete,mirror.cursor,mirror.files.toMap(),s.drafts.toMap(),s.pending.toMap(),journal.spaces.values.flatMap { it.recoveries },collision)
    }
}
