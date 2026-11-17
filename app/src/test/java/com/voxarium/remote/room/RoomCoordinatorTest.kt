package com.voxarium.remote.room

import com.voxarium.remote.data.documents.*
import com.voxarium.remote.data.room.*
import com.voxarium.remote.data.transport.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Protocol §§4–8,13: real repository, ordered fake editor, explicit clock and transport events.
 * No Android runner, sockets, servers, threads, sleeps or auto-ACKs. Network ENQUEUED and remote
 * ACK are deliberately separate driver steps. Assertions observe only public APIs/effects.
 */
class RoomCoordinatorTest {
    private companion object {
        const val PATH = "notes/example.md"
        const val SOURCE = "* original\r\n"
        const val SEED = "AA=="
        const val WINNER = "AQ=="
        const val UPDATE_A = "Ag=="
        const val UPDATE_B = "Aw=="
        const val AWARENESS = "BA=="
        val USER = RemoteUser("account", "Alice", "#123456")
    }

    private class MemoryStorage : DocumentStorage {
        private val cache = mutableMapOf<String, ByteArray>()
        private val drafts = mutableMapOf<String, ByteArray>()
        var failCache = false
        var failDrafts = false
        override fun readCache(namespace: String) = cache[namespace]?.copyOf()
        override fun readDrafts(namespace: String) = drafts[namespace]?.copyOf()
        override fun writeCache(namespace: String, bytes: ByteArray) {
            if (failCache) throw IOException("injected cache failure")
            cache[namespace] = bytes.copyOf()
        }
        override fun writeDrafts(namespace: String, bytes: ByteArray) {
            if (failDrafts) throw IOException("injected journal failure")
            drafts[namespace] = bytes.copyOf()
        }
    }

    private data class Recovery(
        val namespace: DocumentNamespace, val path: String, val context: EditorContext,
        val snapshot: RoomSnapshot?, val raw: List<String>,
    )

    /** Commands are submitted in effect order, but asynchronous replies are controlled by tests. */
    private class OrderedEditor : RoomEditorPort {
        val commands = mutableListOf<EditorCommand>()
        val pending = mutableListOf<EditorCommand>()
        var snapshot = RoomSnapshot(SOURCE, 0)
        override fun submit(command: EditorCommand) {
            commands += command
            pending += command
        }
        inline fun <reified T : EditorCommand> take(): T {
            val index = pending.indexOfFirst { it is T }
            check(index >= 0) { "Expected ${T::class.java.simpleName}" }
            return pending.removeAt(index) as T
        }
    }

    private class Driver(writes: Boolean? = true, connectInitially: Boolean = true, maxBufferedBytes: Int = 1_024) {
        var now = 1_000L
        var generation = 1L
        val storage = MemoryStorage()
        val repository = DocumentsRepository(storage) { now }
        val owner = repository.select(RemoteEndpoint.create("https://example.invalid", "game"))
        val editor = OrderedEditor()
        val recoveries = mutableListOf<Recovery>()
        var failRecovery = false
        private val sink = RoomRecoverySink { namespace, path, context, snapshot, raw ->
            if (failRecovery) throw IOException("injected recovery failure")
            recoveries += Recovery(namespace, path, context, snapshot, raw.toList())
        }
        // Null deliberately omits writesEnabled: this exercises the production default gate.
        val coordinator = if (writes == null) RoomCoordinator(
            repository, owner, sink, nowMillis = { now }, closeTimeoutMillis = 2_000,
            maxBufferedBytes = maxBufferedBytes,
        ) else RoomCoordinator(
            repository, owner, sink, writesEnabled = writes, nowMillis = { now },
            closeTimeoutMillis = 2_000, maxBufferedBytes = maxBufferedBytes,
        )
        val effects = mutableListOf<RoomEffect>()
        lateinit var context: EditorContext
        init { if (connectInitially) connect() }

        fun record(values: List<RoomEffect>): List<RoomEffect> {
            for (effect in values) {
                effects += effect
                if (effect is RoomEffect.Editor) editor.submit(effect.command)
            }
            return values
        }
        fun frame(value: ServerFrame, gen: Long = generation) =
            record(coordinator.onConnectionEvent(ConnectionEvent.Frame(gen, value)))
        fun state(phase: ConnectionPhase, gen: Long = generation) =
            record(coordinator.onConnectionEvent(ConnectionEvent.State(gen, phase)))
        fun event(value: EditorEvent) = record(coordinator.onEditorEvent(value))
        fun connect(text: String = SOURCE, rev: Long = 1) {
            state(ConnectionPhase.READY)
            frame(ServerFrame.Hello(1, USER, RemoteSpace("space", "game"), rev))
            frame(change(text, rev))
            frame(ServerFrame.Synced(rev))
        }
        fun open(path: String = PATH) = record(coordinator.open(path))
        fun prepare(): EditorCommand.Prepare = editor.take<EditorCommand.Prepare>().also {
            context = it.context
            event(EditorEvent.Prepared(it.context, it.requestId, 17, SEED))
        }
        fun joined(seeded: Boolean = true, scribe: Boolean = true, dirty: Boolean = false, rev: Long = 1) =
            frame(ServerFrame.Joined(context.sid, PATH, "epoch-$rev", seeded,
                if (seeded) WINNER else null, scribe, dirty, rev))
        fun ready(markdown: String = SOURCE, editorGeneration: Long = 0): EditorCommand.Initialize =
            editor.take<EditorCommand.Initialize>().also {
                editor.snapshot = RoomSnapshot(markdown, editorGeneration)
                event(EditorEvent.Ready(it.context, it.requestId, editor.snapshot))
            }
        fun mount(markdown: String = SOURCE, scribe: Boolean = true, dirty: Boolean = false,
                  editorGeneration: Long = 0) {
            open(); prepare(); joined(scribe = scribe, dirty = dirty)
            ready(markdown, editorGeneration)
        }
        fun local(markdown: String, enqueue: Boolean = true): RoomEffect.Network {
            val update = event(EditorEvent.LocalUpdate(context, UPDATE_A)).networks().single()
            editor.snapshot = RoomSnapshot(markdown, editor.snapshot.generation + 1)
            event(EditorEvent.Changed(context, editor.snapshot))
            if (enqueue) sent(update)
            return update
        }
        fun applied(markdown: String): EditorCommand.ApplyUpdate = editor.take<EditorCommand.ApplyUpdate>().also {
            editor.snapshot = RoomSnapshot(markdown, editor.snapshot.generation + 1)
            event(EditorEvent.Applied(it.context, it.requestId, editor.snapshot))
        }
        fun tick(afterMillis: Long = 0): List<RoomEffect> {
            now += afterMillis
            return record(coordinator.tick())
        }
        fun close() = record(coordinator.close())
        fun flush(): EditorCommand.Flush = editor.take<EditorCommand.Flush>().also {
            event(EditorEvent.Flushed(it.context, it.requestId, editor.snapshot))
        }
        fun disposed(): EditorCommand.Dispose = editor.take<EditorCommand.Dispose>().also {
            event(EditorEvent.Disposed(it.context, it.requestId))
        }
        fun sent(effect: RoomEffect.Network, result: SendResult = SendResult.ENQUEUED) =
            record(coordinator.onSendResult(effect, result))
        fun ack(effect: RoomEffect.Network, rev: Long = 2): List<RoomEffect> {
            val put = effect.frame as ClientFrame.Put
            return frame(ServerFrame.Ack(FrameId.Integer(put.id), put.path, rev, documentHash(put.content), false))
        }
        fun puts() = effects.networks().filter { it.frame is ClientFrame.Put }
        fun leaves() = effects.networks().filter { it.frame is ClientFrame.Leave }
        fun statuses() = effects.filterIsInstance<RoomEffect.Status>().map { it.value }
        fun assertNoWrites() = assertTrue(effects.networks().none {
            it.frame is ClientFrame.Put || it.frame is ClientFrame.Delete
        })
        fun assertNoLeaveOrDisposeSince(mark: Int) {
            assertTrue(effects.drop(mark).networks().none { it.frame is ClientFrame.Leave })
            assertTrue(effects.drop(mark).filterIsInstance<RoomEffect.Editor>().none { it.command is EditorCommand.Dispose })
        }
    }

    @Test fun intentWaitsForDurableSyncBeforePreparingOrJoining() {
        val d = Driver(connectInitially = false)
        d.open()
        assertEquals(listOf(RoomStatus.WAITING_SYNC), d.statuses())
        assertTrue(d.editor.commands.isEmpty())
        d.state(ConnectionPhase.READY)
        d.frame(ServerFrame.Hello(1, USER, RemoteSpace("space", "game"), 1))
        d.frame(change(SOURCE, 1))
        assertTrue(d.editor.commands.isEmpty())
        d.frame(ServerFrame.Synced(1))
        assertEquals(SOURCE, d.prepare().markdown)
        assertEquals(1, d.effects.networks().count { it.frame is ClientFrame.Join })
        d.assertNoWrites()
    }

    @Test fun missingDocumentIsNotImplicitlyCreated() {
        val d = Driver()
        d.open("missing.md")
        assertEquals(RoomStatus.MISSING, d.statuses().last())
        assertTrue(d.editor.commands.isEmpty())
        d.assertNoWrites()
    }

    @Test fun successfulSeedCasInitializesOnlyAcceptedSeedWithoutLeaving() {
        val d = Driver()
        d.open(); d.prepare(); d.joined(seeded = false)
        assertEquals(SEED, (d.effects.networks().last().frame as ClientFrame.Seed).update)
        assertTrue(d.editor.commands.none { it is EditorCommand.Initialize })
        d.frame(ServerFrame.Seeded(d.context.sid, true, null))
        assertEquals(SEED, d.ready().state)
        assertTrue(d.leaves().isEmpty())
        d.tick(5_000); d.assertNoWrites()
    }

    @Test fun losingSeedCasNeverAppliesLosingSeedOrDropsMembership() {
        val d = Driver()
        d.open(); d.prepare(); d.joined(seeded = false)
        d.frame(ServerFrame.Seeded(d.context.sid, false, WINNER))
        assertEquals(WINNER, d.ready().state)
        assertEquals(listOf(WINNER), d.editor.commands.filterIsInstance<EditorCommand.Initialize>().map { it.state })
        assertTrue(d.editor.commands.none { it is EditorCommand.ApplyUpdate && it.update == SEED })
        assertTrue(d.leaves().isEmpty())
        d.assertNoWrites()
    }

    @Test fun alreadySeededJoinUsesWinnerWithoutSendingSeed() {
        val d = Driver()
        d.mount()
        assertEquals(WINNER, d.editor.commands.filterIsInstance<EditorCommand.Initialize>().single().state)
        assertTrue(d.effects.networks().none { it.frame is ClientFrame.Seed })
        d.assertNoWrites()
    }

    @Test fun scribeBeforeSeedAndReadyKeepsInheritedDirtyDebt() {
        val d = Driver()
        d.open(); d.prepare(); d.joined(seeded = false, scribe = false, dirty = true)
        d.frame(ServerFrame.Scribe(d.context.sid))
        d.tick(600)
        d.assertNoWrites()
        d.frame(ServerFrame.Seeded(d.context.sid, false, WINNER))
        d.ready("inherited merged text")
        assertEquals("inherited merged text", d.repository.view().drafts[PATH]?.content)
        d.tick(500)
        assertEquals("inherited merged text", (d.puts().single().frame as ClientFrame.Put).content)
    }

    @Test fun scribeBeforeJoinedIsNotLostWhenJoinedCarriesEarlierNonScribeRole() {
        val d = Driver()
        d.open(); d.prepare()
        d.frame(ServerFrame.Scribe(d.context.sid))
        d.joined(scribe = false, dirty = true)
        d.ready("early role debt")
        d.tick(500)
        assertEquals("early role debt", (d.puts().single().frame as ClientFrame.Put).content)
    }

    @Test fun preReadyFramesDrainFifoAndOnlyOneRemoteApplyMayBeOutstanding() {
        val d = Driver()
        d.open(); d.prepare(); d.joined(scribe = false)
        val mark = d.editor.commands.size
        d.frame(ServerFrame.Update(d.context.sid, UPDATE_A))
        d.frame(ServerFrame.Awareness(d.context.sid, AWARENESS))
        d.frame(ServerFrame.PeerJoined(d.context.sid))
        d.frame(ServerFrame.Update(d.context.sid, UPDATE_B))
        d.frame(ServerFrame.PeerLeft(d.context.sid, 99))
        assertEquals(mark, d.editor.commands.size)
        d.ready()
        assertEquals(1, d.editor.commands.filterIsInstance<EditorCommand.ApplyUpdate>().size)
        assertEquals(UPDATE_A, d.applied("first merged").update)
        assertEquals(listOf("ApplyUpdate", "Awareness", "PublishAwareness", "ApplyUpdate"),
            d.editor.commands.drop(mark).map { it.javaClass.simpleName })
        assertEquals(UPDATE_B, d.applied("second merged").update)
        assertEquals(99L, d.editor.take<EditorCommand.PeerLeft>().clientId)
        assertEquals("second merged", d.repository.view().drafts[PATH]?.content)
        d.tick(5_000); d.assertNoWrites()
    }

    @Test fun readyReportingPreRenderGenerationCannotRebaselineRealWorkAsClean() {
        val d = Driver()
        d.mount(markdown = "work before first render", editorGeneration = 2)
        d.tick(500)
        assertEquals("work before first render", (d.puts().single().frame as ClientFrame.Put).content)
    }

    @Test fun dirtyAtJoinIsCheckpointedAndSavedWithoutAnyLocalTyping() {
        val d = Driver()
        d.mount(markdown = "remote dirty", dirty = true)
        assertEquals("remote dirty", d.repository.view().drafts[PATH]?.content)
        d.tick(499); d.assertNoWrites()
        d.tick(1)
        val put = d.puts().single().frame as ClientFrame.Put
        assertEquals("remote dirty", put.content)
        assertEquals(d.context.sid, put.live)
        assertEquals(1L, put.baseRev)
    }

    @Test fun dirtyNonScribeHandoffSavesLatestMergedSnapshot() {
        val d = Driver()
        d.mount(scribe = false)
        d.frame(ServerFrame.Update(d.context.sid, UPDATE_A)); d.applied("peer work")
        d.tick(1_000); d.assertNoWrites()
        d.frame(ServerFrame.Scribe(d.context.sid))
        assertEquals("peer work", (d.puts().single().frame as ClientFrame.Put).content)
    }

    @Test fun cleanScribeHandoffAndPresenceNeverCreateDocumentWrites() {
        val d = Driver()
        d.mount(markdown = "- original\r\n", scribe = false)
        d.frame(ServerFrame.Scribe(d.context.sid))
        d.frame(ServerFrame.Awareness(d.context.sid, AWARENESS))
        d.frame(ServerFrame.PeerJoined(d.context.sid))
        d.frame(ServerFrame.PeerLeft(d.context.sid, 23))
        d.event(EditorEvent.Awareness(d.context, AWARENESS))
        d.tick(10_000)
        d.assertNoWrites()
        assertTrue(d.repository.view().drafts.isEmpty())
    }

    @Test fun updateMustBeEnqueuedBeforeAnyPutCanBeProposed() {
        val d = Driver()
        d.mount()
        val update = d.local("new text", enqueue = false)
        d.tick(500); d.assertNoWrites()
        assertTrue(d.coordinator.isCurrent(update))
        val afterEnqueue = d.sent(update)
        val put = afterEnqueue.networks().single { it.frame is ClientFrame.Put }
        assertTrue(d.effects.indexOf(update) < d.effects.indexOf(put))
        assertEquals("new text", d.repository.view().pending[PATH]?.content)
        assertTrue(d.coordinator.isCurrent(put))
        assertFalse(d.statuses().contains(RoomStatus.REMOTE_SAVED))
    }

    @Test fun allLocalUpdateEnqueuesMustCompleteBeforePut() {
        val d = Driver()
        d.mount()
        val first = d.local("one", enqueue = false)
        val second = d.local("two", enqueue = false)
        d.tick(500)
        d.sent(second); d.assertNoWrites()
        d.sent(first)
        assertEquals("two", (d.puts().single().frame as ClientFrame.Put).content)
    }

    @Test fun typingDuringPutSurvivesItsAckAndStaleAckCannotConfirmNewGeneration() {
        val d = Driver()
        d.mount(); d.local("first")
        d.tick(500)
        val first = d.puts().single()
        d.sent(first)
        d.local("second")
        d.tick(500)
        assertEquals(1, d.puts().size)
        d.ack(first)
        assertEquals("second", d.repository.view().drafts[PATH]?.content)
        assertEquals(2, d.puts().size)
        val second = d.puts().last()
        assertEquals("second", (second.frame as ClientFrame.Put).content)
        assertEquals(2L, (second.frame as ClientFrame.Put).baseRev)
        d.ack(first)
        assertEquals("second", d.repository.view().pending[PATH]?.content)
        d.close(); d.flush()
        assertTrue(d.leaves().isEmpty())
        d.ack(second, 3); d.disposed()
        assertEquals(RoomStatus.CLOSED_CONFIRMED, d.statuses().last())
        assertTrue(d.repository.view().pending.isEmpty())
        assertTrue(d.repository.view().drafts.isEmpty())
    }

    @Test fun closeFlushesThenWaitsForRemoteAckBeforeLeaveAndDispose() {
        val d = Driver()
        d.mount(); d.local("last composition")
        val mark = d.effects.size
        d.close()
        d.assertNoLeaveOrDisposeSince(mark)
        d.flush()
        val put = d.puts().single()
        d.sent(put)
        d.assertNoLeaveOrDisposeSince(mark)
        assertFalse(d.statuses().contains(RoomStatus.CLOSED_CONFIRMED))
        d.ack(put)
        val tail = d.effects.drop(mark)
        val flushIndex = tail.indexOfFirst { it is RoomEffect.Editor && it.command is EditorCommand.Flush }
        val putIndex = tail.indexOfFirst { it is RoomEffect.Network && it.frame is ClientFrame.Put }
        val leaveIndex = tail.indexOfFirst { it is RoomEffect.Network && it.frame is ClientFrame.Leave }
        val disposeIndex = tail.indexOfFirst { it is RoomEffect.Editor && it.command is EditorCommand.Dispose }
        assertTrue(flushIndex < putIndex && putIndex < leaveIndex && leaveIndex < disposeIndex)
        assertFalse(d.statuses().contains(RoomStatus.CLOSED_CONFIRMED))
        d.disposed()
        assertEquals(RoomStatus.CLOSED_CONFIRMED, d.statuses().last())
    }

    @Test fun closeWaitsForOutstandingRemoteApplyAfterFlush() {
        val d = Driver()
        d.mount()
        d.frame(ServerFrame.Update(d.context.sid, UPDATE_A))
        d.close(); d.flush()
        assertTrue(d.leaves().isEmpty())
        d.applied("remote work during flush")
        val put = d.puts().single()
        d.ack(put); d.disposed()
        assertEquals(RoomStatus.CLOSED_CONFIRMED, d.statuses().last())
    }

    @Test fun nonScribeCloseWaitsForExactMaterializedBytesWithoutOwnPut() {
        val d = Driver()
        d.mount(scribe = false)
        d.local("exact merged\r\n")
        d.close(); d.flush(); d.tick(500)
        assertTrue(d.leaves().isEmpty())
        assertFalse(d.statuses().contains(RoomStatus.CLOSED_CONFIRMED))
        d.frame(change("exact merged\r\n", 2))
        d.disposed()
        assertEquals(RoomStatus.CLOSED_CONFIRMED, d.statuses().last())
        assertEquals("exact merged\r\n", d.repository.view().documents[PATH]?.content)
        d.assertNoWrites()
    }

    @Test fun nonScribeCloseTimeoutPersistsRecoveryWithoutClaimingDurability() {
        val d = Driver()
        d.mount(scribe = false); d.local("unsaved non-scribe")
        d.close(); d.flush()
        d.tick(1_999)
        assertTrue(d.leaves().isEmpty())
        d.tick(1)
        assertEquals(2, d.editor.commands.filterIsInstance<EditorCommand.Flush>().size)
        assertTrue(d.leaves().isEmpty())
        d.flush(); d.disposed()
        assertEquals("unsaved non-scribe", d.recoveries.last().snapshot?.markdown)
        assertEquals(RoomStatus.CLOSED_RECOVERY_UNCONFIRMED, d.statuses().last())
        assertFalse(d.statuses().contains(RoomStatus.CLOSED_CONFIRMED))
        d.assertNoWrites()
    }

    @Test fun merelyNormalizedMaterializationCannotConfirmNonScribeClose() {
        val d = Driver()
        d.mount(scribe = false); d.local("merged\r\n")
        d.close(); d.flush()
        d.frame(change("merged\n", 2))
        assertFalse(d.statuses().contains(RoomStatus.CLOSED_CONFIRMED))
        d.tick(2_000); d.flush(); d.disposed()
        assertEquals(RoomStatus.CLOSED_RECOVERY_UNCONFIRMED, d.statuses().last())
        assertTrue(d.recoveries.any { it.snapshot?.markdown == "merged\r\n" })
        d.assertNoWrites()
    }

    @Test fun timeoutStillCannotDestroyBindingBeforeCompositionFlushReply() {
        val d = Driver()
        d.mount(scribe = false); d.local("composing")
        val mark = d.effects.size
        d.close(); d.tick(2_000)
        d.assertNoLeaveOrDisposeSince(mark)
        d.editor.snapshot = RoomSnapshot("composition completed", 2)
        d.flush(); d.disposed()
        assertEquals("composition completed", d.recoveries.last().snapshot?.markdown)
        assertEquals(RoomStatus.CLOSED_RECOVERY_UNCONFIRMED, d.statuses().last())
    }

    @Test fun resetRecoversBufferedAndApplyingUpdatesButNeverReplaysThemIntoNewEpoch() {
        val d = Driver()
        d.mount(scribe = false)
        val old = d.context
        d.frame(ServerFrame.Update(old.sid, UPDATE_A))
        d.frame(ServerFrame.Update(old.sid, UPDATE_B))
        d.frame(ServerFrame.Reset(old.sid, PATH, ResetReason.REPLACED))
        assertTrue(d.recoveries.last().raw.containsAll(listOf(UPDATE_A, UPDATE_B)))
        d.event(EditorEvent.Changed(old, RoomSnapshot("late old model work", 1)))
        d.editor.snapshot = RoomSnapshot("late old model work", 1)
        d.flush(); d.disposed()
        val mark = d.editor.commands.size
        d.prepare()
        assertNotEquals(old.sid, d.context.sid)
        assertTrue(d.context.documentGeneration > old.documentGeneration)
        d.joined(); d.ready()
        d.event(EditorEvent.Applied(old, 999, RoomSnapshot("must not leak", 2)))
        assertTrue(d.editor.commands.drop(mark).none { it is EditorCommand.ApplyUpdate })
        assertTrue(d.recoveries.any { it.snapshot?.markdown == "late old model work" })
        d.tick(1_000); d.assertNoWrites()
    }

    @Test fun externalChangeKeepsLastMembershipUntilReplacementJoinedAndUsesNewSource() {
        val d = Driver()
        d.mount(scribe = false); d.local("old local work")
        val old = d.context
        d.frame(change("replacement file", 2))
        assertEquals("old local work", d.recoveries.first().snapshot?.markdown)
        d.flush(); d.disposed()
        assertTrue(d.leaves().isEmpty())
        assertEquals("replacement file", d.prepare().markdown)
        val newContext = d.context
        assertNotEquals(old.sid, newContext.sid)
        val join = d.effects.networks().last { it.frame is ClientFrame.Join }
        assertTrue(d.leaves().isEmpty())
        d.joined(scribe = false, rev = 2)
        assertEquals(old.sid, (d.leaves().single().frame as ClientFrame.Leave).sid)
        assertTrue(d.effects.indexOf(join) < d.effects.indexOf(d.leaves().single()))
        d.ready("replacement file")
        assertTrue(d.editor.commands.none { it.context == newContext && it is EditorCommand.ApplyUpdate })
        d.assertNoWrites()
    }

    @Test fun reconnectFencesOldNetworkAndEditorEventsAndDoesNotReplayOldBytes() {
        val d = Driver()
        d.mount(); val old = d.context
        val oldUpdate = d.local("offline candidate", enqueue = false)
        d.state(ConnectionPhase.BACKOFF)
        assertFalse(d.coordinator.isCurrent(oldUpdate))
        d.flush(); d.disposed()
        d.generation = 2
        d.connect("new server source", 2)
        assertEquals("new server source", d.prepare().markdown)
        assertEquals(2L, d.context.connectionGeneration)
        assertNotEquals(old.sid, d.context.sid)
        val mark = d.effects.size
        d.frame(ServerFrame.Update(old.sid, UPDATE_B), gen = 1)
        d.event(EditorEvent.LocalUpdate(old, UPDATE_A))
        d.event(EditorEvent.Changed(old, RoomSnapshot("stale callback", 20)))
        d.sent(oldUpdate)
        assertEquals(mark, d.effects.size)
        d.joined(rev = 2); d.ready("new server source")
        d.tick(1_000); d.assertNoWrites()
        assertTrue(d.recoveries.any { it.snapshot?.markdown == "offline candidate" })
    }

    @Test fun oldSidResetIsFilteredBeforeRepositoryCanRetireCurrentWriter() {
        assertOldSidCannotRetireWriter { ServerFrame.Reset(it, PATH, ResetReason.REPLACED) }
    }

    @Test fun oldSidJoinFailedIsFilteredBeforeRepositoryCanRetireCurrentWriter() {
        assertOldSidCannotRetireWriter { ServerFrame.JoinFailed(it, PATH, JoinFailure.STALE) }
    }

    private fun assertOldSidCannotRetireWriter(stale: (String) -> ServerFrame) {
        val d = Driver()
        d.mount(); d.close(); d.flush(); d.disposed()
        val old = d.context
        d.mount()
        assertNotEquals(old.sid, d.context.sid)
        d.local("current writer remains authorized")
        val before = d.repository.view()
        val mark = d.effects.size
        d.frame(stale(old.sid))
        assertEquals(mark, d.effects.size)
        assertEquals(before.drafts, d.repository.view().drafts)
        assertEquals(before.recoveries, d.repository.view().recoveries)
        d.tick(500)
        assertEquals("current writer remains authorized", (d.puts().single().frame as ClientFrame.Put).content)
        assertTrue(d.coordinator.isCurrent(d.puts().single()))
    }

    @Test fun writeGateIsOffByDefaultEvenForDirtyScribeAndLocalUpdate() {
        val d = Driver(writes = null)
        d.mount(markdown = "inherited dirty", dirty = true)
        assertTrue(d.editor.commands.filterIsInstance<EditorCommand.Initialize>().single().readOnly)
        assertTrue(d.statuses().contains(RoomStatus.WRITE_GATE_CLOSED))
        assertTrue(d.event(EditorEvent.LocalUpdate(d.context, UPDATE_A)).networks().isEmpty())
        d.tick(10_000)
        d.assertNoWrites()
    }

    @Test fun unpairedChangedIsSchemaBlockedInsteadOfBypassingUpdateBeforePut() {
        val d = Driver()
        d.mount()
        d.event(EditorEvent.Changed(d.context, RoomSnapshot("no Yjs update", 1)))
        assertTrue(d.statuses().contains(RoomStatus.SCHEMA_BLOCKED))
        d.tick(1_000); d.assertNoWrites()
    }

    @Test fun tenCleanNormalizedOpensIncludingAwarenessProduceZeroPutOrDelete() {
        val d = Driver()
        repeat(10) {
            d.mount(markdown = "- original\r\n", scribe = false)
            d.frame(ServerFrame.Scribe(d.context.sid))
            d.frame(ServerFrame.Awareness(d.context.sid, AWARENESS))
            d.frame(ServerFrame.PeerJoined(d.context.sid))
            d.frame(ServerFrame.PeerLeft(d.context.sid, 42))
            d.event(EditorEvent.Awareness(d.context, AWARENESS))
            d.tick(5_000)
            d.close(); d.flush(); d.disposed()
            assertEquals(RoomStatus.CLOSED_CONFIRMED, d.statuses().last())
        }
        d.assertNoWrites()
        assertEquals(10, d.leaves().size)
        assertEquals(10, d.editor.commands.filterIsInstance<EditorCommand.Prepare>().map { it.context.sid }.toSet().size)
        assertEquals(DocumentVersion(1, documentHash(SOURCE), false, SOURCE), d.repository.view().documents[PATH])
        assertTrue(d.repository.view().drafts.isEmpty())
        assertTrue(d.repository.view().pending.isEmpty())
        assertTrue(d.recoveries.isEmpty())
    }

    @Test fun blockedEditorPreservesMarkdownAndRawStateWithoutRepairOrRejoin() {
        val d = Driver()
        d.mount()
        d.event(EditorEvent.Blocked(d.context, RoomSnapshot("last valid", 1), listOf(UPDATE_B)))
        assertEquals("last valid", d.recoveries.last().snapshot?.markdown)
        assertEquals(listOf(UPDATE_B), d.recoveries.last().raw)
        d.disposed(); d.tick(1_000)
        assertEquals(RoomStatus.SCHEMA_BLOCKED, d.statuses().last())
        assertEquals(1, d.editor.commands.filterIsInstance<EditorCommand.Prepare>().size)
        d.assertNoWrites()
    }

    @Test fun checkpointStorageFailureProducesNoSendDisposeOrFalseSuccess() {
        val d = Driver()
        d.mount(); d.local("durable previous snapshot")
        val previous = d.repository.view().drafts
        d.event(EditorEvent.LocalUpdate(d.context, UPDATE_B))
        d.storage.failDrafts = true
        val mark = d.effects.size
        expectIo { d.event(EditorEvent.Changed(d.context, RoomSnapshot("cannot persist", 2))) }
        assertEquals(previous, d.repository.view().drafts)
        assertEquals(mark, d.effects.size)
        d.assertNoLeaveOrDisposeSince(mark)
        d.assertNoWrites()
        assertFalse(d.statuses().contains(RoomStatus.REMOTE_SAVED))
    }

    @Test fun recoveryStorageFailureStopsResetBeforeRepositoryOrReplacementEffects() {
        val d = Driver()
        d.mount(scribe = false); d.local("must retain")
        val before = d.repository.view()
        d.failRecovery = true
        val mark = d.effects.size
        expectIo { d.frame(ServerFrame.Reset(d.context.sid, PATH, ResetReason.REPLACED)) }
        assertEquals(mark, d.effects.size)
        assertEquals(before.drafts, d.repository.view().drafts)
        assertEquals(before.recoveries, d.repository.view().recoveries)
        d.assertNoLeaveOrDisposeSince(mark)
        d.assertNoWrites()
    }

    @Test fun putReservationStorageFailureCannotProduceNetworkWrite() {
        val d = Driver()
        d.mount(); d.local("durably checkpointed")
        val before = d.repository.view().drafts
        d.storage.failDrafts = true
        val mark = d.effects.size
        expectIo { d.tick(500) }
        assertEquals(before, d.repository.view().drafts)
        assertTrue(d.repository.view().pending.isEmpty())
        assertEquals(mark, d.effects.size)
        d.assertNoLeaveOrDisposeSince(mark)
        d.assertNoWrites()
    }

    @Test fun ackCacheStorageFailureRetainsPendingAndCannotConfirmClose() {
        val d = Driver()
        d.mount(); d.local("pending bytes")
        d.close(); d.flush()
        val put = d.puts().single()
        d.storage.failCache = true
        val mark = d.effects.size
        expectIo { d.ack(put) }
        assertEquals("pending bytes", d.repository.view().pending[PATH]?.content)
        assertFalse(d.repository.view().synced)
        assertFalse(d.coordinator.isCurrent(put))
        d.assertNoLeaveOrDisposeSince(mark)
        assertFalse(d.statuses().contains(RoomStatus.REMOTE_SAVED))
        assertFalse(d.statuses().contains(RoomStatus.CLOSED_CONFIRMED))
    }

    @Test fun failedUpdateEnqueueRecoversInsteadOfPuttingOrReportingSaved() {
        val d = Driver()
        d.mount()
        val update = d.local("not delivered", enqueue = false)
        d.sent(update, SendResult.BACKPRESSURE)
        assertTrue(d.statuses().contains(RoomStatus.RECONNECT_REQUIRED))
        assertTrue(d.recoveries.any { it.snapshot?.markdown == "not delivered" })
        assertFalse(d.coordinator.isCurrent(update))
        d.flush(); d.disposed(); d.tick(1_000)
        d.assertNoWrites()
        assertFalse(d.statuses().contains(RoomStatus.REMOTE_SAVED))
    }

    @Test fun wrongEditorRequestCannotPrepareReadyFlushOrDisposeCurrentSession() {
        val d = Driver()
        d.open()
        val prepare = d.editor.take<EditorCommand.Prepare>()
        val c = prepare.context
        val mark = d.effects.size
        d.event(EditorEvent.Prepared(c, prepare.requestId + 1, 17, SEED))
        assertEquals(mark, d.effects.size)
        d.context = c
        d.event(EditorEvent.Prepared(c, prepare.requestId, 17, SEED))
        d.joined()
        val initialize = d.editor.pending.filterIsInstance<EditorCommand.Initialize>().single()
        d.event(EditorEvent.Ready(c, initialize.requestId + 1, RoomSnapshot("wrong", 0)))
        assertFalse(d.statuses().contains(RoomStatus.EDITING))
        d.ready(); d.close()
        val flush = d.editor.pending.filterIsInstance<EditorCommand.Flush>().single()
        d.event(EditorEvent.Flushed(c, flush.requestId + 1, RoomSnapshot(SOURCE, 0)))
        assertTrue(d.leaves().isEmpty())
        d.flush()
        val dispose = d.editor.pending.filterIsInstance<EditorCommand.Dispose>().single()
        d.event(EditorEvent.Disposed(c, dispose.requestId + 1))
        assertFalse(d.statuses().contains(RoomStatus.CLOSED_CONFIRMED))
        d.disposed()
        assertEquals(RoomStatus.CLOSED_CONFIRMED, d.statuses().last())
    }

    @Test fun preReadyBufferOverflowRecoversRawStateAndDoesNotMountOrWrite() {
        val d = Driver(maxBufferedBytes = 4)
        d.open(); d.prepare(); d.joined()
        d.frame(ServerFrame.Update(d.context.sid, UPDATE_A))
        d.frame(ServerFrame.Update(d.context.sid, UPDATE_B))
        assertTrue(d.statuses().contains(RoomStatus.RECONNECT_REQUIRED))
        assertTrue(d.recoveries.any { UPDATE_A in it.raw })
        assertTrue(d.recoveries.any { it.raw == listOf(UPDATE_B) })
        d.editor.snapshot = RoomSnapshot(SOURCE, 0)
        d.flush(); d.disposed()
        d.assertNoWrites()
        assertFalse(d.statuses().contains(RoomStatus.EDITING))
    }

    @Test fun cleanDisconnectRecoversFinalImeChangedAndNewerFlushedSnapshot() {
        assertCleanRetirementRecoversFinalIme(disconnect = true, changedBeforeFlush = true)
    }

    @Test fun cleanResetRecoversFinalImeChangedAndNewerFlushedSnapshot() {
        assertCleanRetirementRecoversFinalIme(disconnect = false, changedBeforeFlush = true)
    }

    @Test fun cleanDisconnectRecoversFinalFlushEvenWithoutChangedCallback() {
        assertCleanRetirementRecoversFinalIme(disconnect = true, changedBeforeFlush = false)
    }

    @Test fun cleanResetRecoversFinalFlushEvenWithoutChangedCallback() {
        assertCleanRetirementRecoversFinalIme(disconnect = false, changedBeforeFlush = false)
    }

    private fun assertCleanRetirementRecoversFinalIme(disconnect: Boolean, changedBeforeFlush: Boolean) {
        val d = Driver()
        d.mount()
        val old = d.context
        if (disconnect) d.state(ConnectionPhase.BACKOFF)
        else d.frame(ServerFrame.Reset(old.sid, PATH, ResetReason.REPLACED))
        // A clean stopped queue alone must not create a recovery, but it must not suppress IME work.
        assertTrue(d.recoveries.isEmpty())
        if (changedBeforeFlush) {
            d.event(EditorEvent.Changed(old, RoomSnapshot("late IME transaction", 1)))
            assertEquals("late IME transaction", d.recoveries.last().snapshot?.markdown)
        }
        d.editor.snapshot = RoomSnapshot("final composition checkpoint", 2)
        d.flush()
        val recovered = d.recoveries.last()
        assertEquals(old, recovered.context)
        assertEquals(PATH, recovered.path)
        assertEquals(d.repository.view().namespace, recovered.namespace)
        assertEquals(RoomSnapshot("final composition checkpoint", 2), recovered.snapshot)
        d.disposed()
        assertTrue(d.statuses().contains(RoomStatus.RECOVERY_UNCONFIRMED))
        assertFalse(d.statuses().contains(RoomStatus.REMOTE_SAVED))
        assertFalse(d.statuses().contains(RoomStatus.CLOSED_CONFIRMED))
        assertEquals(SOURCE, d.repository.view().documents[PATH]?.content)
        d.assertNoWrites()
    }

    @Test fun blockedEditorRecoversApplyingBufferedAndReportedRawBytesTogether() {
        val d = Driver()
        d.mount()
        d.frame(ServerFrame.Update(d.context.sid, UPDATE_A))
        d.frame(ServerFrame.Update(d.context.sid, UPDATE_B))
        assertEquals(1, d.editor.commands.filterIsInstance<EditorCommand.ApplyUpdate>().size)
        d.event(EditorEvent.Blocked(d.context, RoomSnapshot("last schema-valid snapshot", 1), listOf(AWARENESS)))
        assertTrue(d.recoveries.any { it.raw.containsAll(listOf(UPDATE_A, UPDATE_B)) })
        assertEquals(listOf(AWARENESS), d.recoveries.last().raw)
        assertEquals("last schema-valid snapshot", d.recoveries.last().snapshot?.markdown)
        assertTrue(d.recoveries.flatMap { it.raw }.containsAll(listOf(UPDATE_A, UPDATE_B, AWARENESS)))
        d.disposed()
        assertEquals(RoomStatus.SCHEMA_BLOCKED, d.statuses().last())
        assertEquals(1, d.editor.commands.filterIsInstance<EditorCommand.Prepare>().size)
        d.assertNoWrites()
    }

    @Test fun overflowPreservesTriggeringUpdateSeparatelyFromApplyingAndBufferedBytes() {
        val d = Driver(maxBufferedBytes = 4)
        d.mount()
        val overflow = "BQ=="
        d.frame(ServerFrame.Update(d.context.sid, UPDATE_A)) // Applying: no buffer capacity used.
        d.frame(ServerFrame.Update(d.context.sid, UPDATE_B)) // Fills the buffer.
        d.frame(ServerFrame.Update(d.context.sid, overflow)) // Must be saved despite not fitting.
        val trigger = d.recoveries.single { it.raw == listOf(overflow) }
        val backlog = d.recoveries.single { it.raw.containsAll(listOf(UPDATE_A, UPDATE_B)) }
        assertEquals(d.context, trigger.context)
        assertEquals(PATH, trigger.path)
        assertFalse(overflow in backlog.raw)
        assertTrue(d.statuses().contains(RoomStatus.RECONNECT_REQUIRED))
        d.flush(); d.disposed()
        d.assertNoWrites()
    }

    @Test fun semanticNoOpLocalUpdateWithoutChangedCanCloseAfterEnqueueAndFlush() {
        for (enqueueBeforeFlush in listOf(false, true)) {
            val d = Driver()
            d.mount()
            val update = d.event(EditorEvent.LocalUpdate(d.context, UPDATE_A)).networks().single()
            if (enqueueBeforeFlush) d.sent(update)
            d.close()
            d.editor.snapshot = RoomSnapshot(SOURCE, 1) // CRDT transaction, identical Markdown.
            d.flush()
            if (!enqueueBeforeFlush) {
                assertTrue(d.leaves().isEmpty())
                assertFalse(d.statuses().contains(RoomStatus.CLOSED_CONFIRMED))
                d.sent(update)
            }
            assertEquals(1, d.leaves().size)
            d.disposed()
            assertEquals(RoomStatus.CLOSED_CONFIRMED, d.statuses().last())
            assertTrue(d.repository.view().pending.isEmpty())
            assertTrue(d.repository.view().drafts.isEmpty())
            assertTrue(d.recoveries.isEmpty())
            d.assertNoWrites()
        }
    }

    @Test fun sameTextChangeCannotConfirmCloseBeforeSemanticNoOpEnqueueResult() {
        for (scribe in listOf(false, true)) for (result in listOf(SendResult.ENQUEUED, SendResult.BACKPRESSURE)) {
            val (d, update) = semanticNoOpCloseWithPendingEnqueueAndChange(scribe)
            // Change revoked the room grant. Even a late successful enqueue cannot resume
            // this incarnation's save/close path; a refusal must retain the same recovery.
            d.sent(update, result)
            d.tick()
            d.frame(change(SOURCE, 2)) // Repeated materialization cannot upgrade retirement.
            assertTrue(d.leaves().isEmpty())
            assertTrue(d.editor.commands.none { it is EditorCommand.Dispose })
            assertTrue(d.recoveries.any { it.snapshot == RoomSnapshot(SOURCE, 1) })
            d.flush(); d.disposed()
            assertEquals(RoomStatus.CLOSED_RECOVERY_UNCONFIRMED, d.statuses().last())
            assertEquals(if (result == SendResult.ENQUEUED) 1 else 0, d.leaves().size)
            if (result == SendResult.BACKPRESSURE) assertTrue(d.statuses().contains(RoomStatus.RECONNECT_REQUIRED))
            assertFalse(d.statuses().contains(RoomStatus.CLOSED_CONFIRMED))
            assertFalse(d.statuses().contains(RoomStatus.REMOTE_SAVED))
            d.assertNoWrites()
        }
    }

    @Test fun staleSemanticNoOpEnqueueCallbacksAfterChangeCannotAffectNewBinding() {
        val (d, update) = semanticNoOpCloseWithPendingEnqueueAndChange(scribe = true)
        d.flush(); d.disposed()
        assertEquals(RoomStatus.CLOSED_RECOVERY_UNCONFIRMED, d.statuses().last())
        d.open(); d.prepare(); d.joined(rev = 2); d.ready()
        assertNotEquals(update.context, d.context)
        val recoveries = d.recoveries.toList()
        val mark = d.effects.size
        d.sent(update, SendResult.ENQUEUED)
        d.sent(update, SendResult.BACKPRESSURE)
        assertEquals(mark, d.effects.size)
        assertEquals(recoveries, d.recoveries)
        d.local("new binding remains writable"); d.tick(500)
        val put = d.puts().single()
        assertEquals("new binding remains writable", d.repository.view().pending[PATH]?.content)
        assertTrue(d.coordinator.isCurrent(put))
        assertEquals(recoveries, d.recoveries)
    }

    @Test fun sameTextChangeBeforeFlushCannotConfirmUnmatchedSemanticNoOpUpdate() {
        val d = Driver()
        d.mount()
        val update = d.event(EditorEvent.LocalUpdate(d.context, UPDATE_A)).networks().single()
        d.close()
        d.editor.snapshot = RoomSnapshot(SOURCE, 1)
        val mark = d.effects.size
        d.frame(change(SOURCE, 2))
        d.sent(update)
        d.assertNoLeaveOrDisposeSince(mark)
        d.flush(); d.disposed()
        assertEquals(RoomStatus.CLOSED_RECOVERY_UNCONFIRMED, d.statuses().last())
        assertFalse(d.statuses().contains(RoomStatus.CLOSED_CONFIRMED))
        assertTrue(d.recoveries.any { it.snapshot == RoomSnapshot(SOURCE, 1) })
        assertEquals(1, d.leaves().size)
        d.assertNoWrites()
    }

    @Test fun sameTextChangeCannotBypassRepositoryPendingEvenWhenRoomQueueIsClean() {
        val d = Driver()
        d.mount()
        // Reserve through the public repository seam without queue-local inFlight debt:
        // repository pending is an independent close condition, not implied by canLeave.
        val token = d.repository.confirmRoomReady(d.owner, d.generation, d.context.sid, "epoch-1")
        d.repository.queueRoomSnapshot(token, "independent pending bytes")
        val pending = d.repository.nextWrite(d.repository.commandScope(d.owner), PATH)!!
        d.close(); d.flush()
        val mark = d.effects.size
        d.frame(change(SOURCE, 2))
        d.assertNoLeaveOrDisposeSince(mark)
        assertFalse(d.repository.isCurrent(pending))
        d.flush(); d.disposed()
        assertEquals(RoomStatus.CLOSED_RECOVERY_UNCONFIRMED, d.statuses().last())
        assertFalse(d.statuses().contains(RoomStatus.CLOSED_CONFIRMED))
        assertEquals("independent pending bytes", d.repository.view().pending[PATH]?.content)
        assertTrue(d.recoveries.any { it.snapshot?.markdown == SOURCE })
        assertEquals(1, d.leaves().size)
        d.assertNoWrites()
    }

    private fun semanticNoOpCloseWithPendingEnqueueAndChange(scribe: Boolean): Pair<Driver, RoomEffect.Network> {
        val d = Driver()
        d.mount(scribe = scribe)
        val update = d.event(EditorEvent.LocalUpdate(d.context, UPDATE_A)).networks().single()
        d.close()
        d.editor.snapshot = RoomSnapshot(SOURCE, 1) // No Changed callback for this CRDT transaction.
        val firstFlush = d.flush()
        assertTrue(d.leaves().isEmpty())
        val mark = d.effects.size
        d.frame(change(SOURCE, 2))
        assertEquals(2L, d.repository.view().documents[PATH]?.rev)
        d.assertNoLeaveOrDisposeSince(mark)
        assertFalse(d.statuses().contains(RoomStatus.CLOSED_CONFIRMED))
        assertFalse(d.coordinator.isCurrent(update))
        val freshFlush = d.editor.pending.filterIsInstance<EditorCommand.Flush>().single()
        assertTrue(freshFlush.requestId > firstFlush.requestId)
        d.event(EditorEvent.Flushed(firstFlush.context, firstFlush.requestId, d.editor.snapshot))
        d.assertNoLeaveOrDisposeSince(mark)
        return d to update
    }

    @Test fun timeoutRequestsFreshFlushAfterEarlierCloseFlushAndLaterRemoteApply() {
        val d = Driver()
        d.mount(scribe = false); d.local("first close checkpoint")
        d.close()
        val firstFlush = d.flush()
        d.frame(ServerFrame.Update(d.context.sid, UPDATE_A))
        d.applied("remote work after first flush")
        val mark = d.effects.size
        d.tick(2_000)
        d.assertNoLeaveOrDisposeSince(mark)
        val fresh = d.editor.pending.filterIsInstance<EditorCommand.Flush>().single()
        assertTrue(fresh.requestId > firstFlush.requestId)
        // A duplicate reply to the old flush is not the final checkpoint.
        d.event(EditorEvent.Flushed(firstFlush.context, firstFlush.requestId, RoomSnapshot("first close checkpoint", 1)))
        d.assertNoLeaveOrDisposeSince(mark)
        d.editor.snapshot = RoomSnapshot("final remote and IME checkpoint", 3)
        d.flush(); d.disposed()
        assertEquals("final remote and IME checkpoint", d.recoveries.last().snapshot?.markdown)
        assertEquals(RoomStatus.CLOSED_RECOVERY_UNCONFIRMED, d.statuses().last())
        d.assertNoWrites()
    }

    @Test fun staleNackForPreviousDocumentCannotRetireOrRejectNewPendingWrite() {
        val (d, oldPut, currentPut) = replacedSessionWithPendingWrite()
        val mark = d.effects.size
        val old = oldPut.frame as ClientFrame.Put
        d.frame(ServerFrame.Nack(FrameId.Integer(old.id), PATH, DocumentVersion(2, documentHash(old.content), false, old.content)))
        assertEquals(mark, d.effects.size)
        assertCurrentPending(d, currentPut)
        d.ack(currentPut, 3)
        assertEquals("new document work", d.repository.view().documents[PATH]?.content)
    }

    @Test fun staleErrorsByOldIdOrOldSidCannotAffectNewDocument() {
        val (d, oldPut, currentPut) = replacedSessionWithPendingWrite()
        val mark = d.effects.size
        val old = oldPut.frame as ClientFrame.Put
        val current = currentPut.frame as ClientFrame.Put
        d.frame(ServerFrame.Error("late failure", "put", FrameId.Integer(old.id), null, FrameId.Text(PATH)))
        // Even an ID matching the new pending write is powerless when the sid is stale.
        d.frame(ServerFrame.Error("stale sid", "put", FrameId.Integer(current.id), FrameId.Text(old.live!!), FrameId.Text(PATH)))
        assertEquals(mark, d.effects.size)
        assertCurrentPending(d, currentPut)
    }

    @Test fun staleSameConnectionSendResultsCannotDisconnectNewDocument() {
        val (d, oldPut, currentPut) = replacedSessionWithPendingWrite()
        val oldContext = oldPut.context!!
        val oldUpdate = d.effects.networks().first { it.context == oldContext && it.frame is ClientFrame.Update }
        val oldLeave = d.effects.networks().first { it.context == oldContext && it.frame is ClientFrame.Leave }
        val mark = d.effects.size
        for (stale in listOf(oldPut, oldUpdate, oldLeave)) {
            d.sent(stale, SendResult.BACKPRESSURE)
            d.sent(stale, SendResult.ENQUEUED)
        }
        assertEquals(mark, d.effects.size)
        assertCurrentPending(d, currentPut)
        d.ack(currentPut, 3)
        assertEquals(RoomStatus.REMOTE_SAVED, d.statuses().last())
    }

    private fun replacedSessionWithPendingWrite(): Triple<Driver, RoomEffect.Network, RoomEffect.Network> {
        val d = Driver()
        d.mount(); d.local("old document saved")
        d.tick(500)
        val oldPut = d.puts().single()
        d.ack(oldPut, 2)
        d.close(); d.flush(); d.disposed()
        val oldContext = d.context
        d.open(); d.prepare(); d.joined(rev = 2); d.ready("old document saved")
        assertNotEquals(oldContext, d.context)
        assertEquals(oldContext.connectionGeneration, d.context.connectionGeneration)
        d.local("new document work"); d.tick(500)
        val currentPut = d.puts().last()
        assertNotEquals((oldPut.frame as ClientFrame.Put).id, (currentPut.frame as ClientFrame.Put).id)
        return Triple(d, oldPut, currentPut)
    }

    private fun assertCurrentPending(d: Driver, effect: RoomEffect.Network) {
        val put = effect.frame as ClientFrame.Put
        assertEquals(put.id, d.repository.view().pending[PATH]?.id)
        assertEquals(put.content, d.repository.view().pending[PATH]?.content)
        assertTrue(d.repository.view().synced)
        assertTrue(d.coordinator.isCurrent(effect))
        assertTrue(d.recoveries.isEmpty())
    }

    @Test fun blockedImmediatelyRevokesRepositoryGrantAndPreviouslyProducedDocumentSend() {
        assertRetirementRevokesRepositoryGrant(blocked = true)
    }

    @Test fun retireImmediatelyRevokesRepositoryGrantBeforeFlushOrDispose() {
        assertRetirementRevokesRepositoryGrant(blocked = false)
    }

    private fun assertRetirementRevokesRepositoryGrant(blocked: Boolean) {
        val d = Driver()
        d.mount(); d.local("pending original snapshot"); d.tick(500)
        val pending = d.puts().single()
        val documentSend = pending.documentSend!!
        val token = d.repository.confirmRoomReady(d.owner, d.generation, d.context.sid, "epoch-1")
        assertTrue(d.repository.isCurrent(documentSend))
        if (blocked) d.event(EditorEvent.Blocked(d.context, d.editor.snapshot, listOf(UPDATE_B)))
        else d.event(EditorEvent.Changed(d.context, RoomSnapshot("unpaired model callback", 2)))
        assertTrue(d.repository.view().synced) // Revocation is not merely an offline/readiness fence.
        assertFalse(d.repository.isCurrent(documentSend))
        assertFalse(d.coordinator.isCurrent(pending))
        expectDocumentFailure(DocumentFailure.STALE_ROOM) { d.repository.queueRoomSnapshot(token, "must not be authorized") }
        expectDocumentFailure(DocumentFailure.STALE_ROOM) {
            d.repository.confirmRoomReady(d.owner, d.generation, d.context.sid, "epoch-1")
        }
        assertEquals("pending original snapshot", d.repository.view().pending[PATH]?.content)
        assertEquals(1, d.puts().size)
    }

    @Test fun tombstoneCannotConfirmNonScribeEmptyMarkdown() {
        val d=Driver()
        d.mount(scribe=false);d.local("");d.close();d.flush()
        d.frame(ServerFrame.Change(PATH,2,null,true,null,null))
        assertFalse(d.statuses().contains(RoomStatus.CLOSED_CONFIRMED))
        d.flush();d.disposed()
        assertEquals(RoomStatus.CLOSED_RECOVERY_UNCONFIRMED,d.statuses().last())
        assertTrue(d.recoveries.any { it.snapshot?.markdown == "" })
        d.assertNoWrites()
    }

    @Test fun staleRawChangeCannotConfirmTextDifferentFromNewestDurableMirror() {
        val d=Driver()
        d.mount(scribe=false);d.local("old match");d.close();d.flush()
        // A newer document-layer version has already been durably applied by another
        // repository consumer; the coordinator must not trust this subsequent old raw frame.
        d.repository.onEvent(d.owner,ConnectionEvent.Frame(d.generation,change("newest remote",3)))
        d.frame(change("old match",2))
        assertEquals("newest remote",d.repository.view().documents[PATH]?.content)
        assertFalse(d.statuses().contains(RoomStatus.CLOSED_CONFIRMED))
        d.flush();d.disposed()
        assertEquals(RoomStatus.CLOSED_RECOVERY_UNCONFIRMED,d.statuses().last())
        d.assertNoWrites()
    }

    @Test fun closeDuringRolloverFlushCancelsRejoinAndReleasesMembership() {
        val d=Driver()
        d.mount(scribe=false);d.local("unmaterialized")
        val old=d.context
        d.frame(change("external file",2))
        d.close();d.flush();d.disposed()
        assertEquals(RoomStatus.CLOSED_RECOVERY_UNCONFIRMED,d.statuses().last())
        assertEquals(listOf(old.sid),d.leaves().map { (it.frame as ClientFrame.Leave).sid })
        assertEquals(1,d.editor.commands.filterIsInstance<EditorCommand.Prepare>().size)
        d.assertNoWrites()
    }

    @Test fun closeWhileRolloverDisposalPendingReleasesAlreadyHeldMembership() {
        val d=Driver()
        d.mount(scribe=false);d.local("unmaterialized")
        val old=d.context
        d.frame(change("external file",2));d.flush()
        assertTrue(d.leaves().isEmpty())
        d.close();d.disposed()
        assertEquals(RoomStatus.CLOSED_RECOVERY_UNCONFIRMED,d.statuses().last())
        assertEquals(listOf(old.sid),d.leaves().map { (it.frame as ClientFrame.Leave).sid })
        assertEquals(1,d.editor.commands.filterIsInstance<EditorCommand.Prepare>().size)
        d.assertNoWrites()
    }

    private fun expectDocumentFailure(reason: DocumentFailure, block: () -> Unit) {
        try { block(); fail("Expected repository grant rejection") }
        catch (error: DocumentRepositoryException) { assertEquals(reason, error.reason) }
    }

    private fun expectIo(block: () -> Unit) {
        try { block(); fail("Expected injected storage failure") } catch (_: IOException) { }
    }
}

private fun List<RoomEffect>.networks() = filterIsInstance<RoomEffect.Network>()

private fun change(content: String, rev: Long) =
    ServerFrame.Change("notes/example.md", rev, documentHash(content), false, content, null)
