package com.voxarium.remote.ui

import com.voxarium.remote.RemoteError
import com.voxarium.remote.RemoteStatus
import com.voxarium.remote.RemoteUiState
import com.voxarium.remote.RemoteViewModel
import com.voxarium.remote.VerificationStatus
import com.voxarium.remote.CatalogStatus
import com.voxarium.remote.data.documents.*
import com.voxarium.remote.data.security.*
import com.voxarium.remote.data.transport.*
import com.voxarium.remote.ui.connection.*
import java.util.ArrayDeque
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Test
import androidx.lifecycle.ViewModelStore

/** Deterministic actor/IO tests. Real stores/repository, fake atomic blobs and fake wire only. */
class RemoteSessionTest {
    private class ManualWorker : AbstractExecutorService(), Executor {
        private val jobs = ArrayDeque<Runnable>()
        private var stopped = false
        override fun execute(command: Runnable) {
            check(!stopped)
            jobs.add(command)
        }
        fun drain() { while (jobs.isNotEmpty()) jobs.removeFirst().run() }
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> { stopped = true; return jobs.toMutableList().also { jobs.clear() } }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && jobs.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
    }

    private class Blob : BlobStorage {
        var bytes: ByteArray? = null
        var failRead = false
        var failWrite = false
        override fun read(): ByteArray? {
            if (failRead) error("secret exception must not reach state")
            return bytes?.clone()
        }
        override fun write(bytes: ByteArray) {
            if (failWrite) error("secret exception must not reach state")
            this.bytes = bytes.clone()
        }
    }

    private class Disk : DocumentStorage {
        val cache = mutableMapOf<String, ByteArray>()
        val drafts = mutableMapOf<String, ByteArray>()
        val roomNamespaces = mutableSetOf<DocumentNamespace>()
        override fun migrationRoomNamespaces(): Set<DocumentNamespace> = roomNamespaces.toSet()
        var fail = false
        override fun readCache(namespace: String) = cache[namespace]?.clone()
        override fun readDrafts(namespace: String) = drafts[namespace]?.clone()
        override fun writeCache(namespace: String, bytes: ByteArray) {
            if (fail) throw DocumentStorageException(DocumentStorageException.Reason.STORAGE)
            cache[namespace] = bytes.clone()
        }
        override fun writeDrafts(namespace: String, bytes: ByteArray) {
            if (fail) throw DocumentStorageException(DocumentStorageException.Reason.STORAGE)
            drafts[namespace] = bytes.clone()
        }
    }

    private class Wire(val observer: (ConnectionEvent) -> Unit) : SessionConnection {
        var closed = false
        var connects = 0
        var tokenMatched = false
        var duringConnect: (() -> Unit)? = null
        var sendResult = SendResult.ENQUEUED
        var deferResults = false
        val results = mutableListOf<(SendResult) -> Unit>()
        val sent = mutableListOf<Pair<Long, ClientFrame>>()
        override fun connect(endpoint: RemoteEndpoint, bearer: String, projectId: String, expectedAccountId: String, storageEpoch: String): Long {
            assertFalse(closed)
            tokenMatched = bearer == TOKEN
            connects++
            duringConnect?.invoke()
            return 1
        }
        override fun send(generation: Long, frame: ClientFrame, result: (SendResult) -> Unit) {
            assertFalse(closed)
            sent.add(generation to frame)
            if (deferResults) results.add(result) else result(sendResult)
        }
        override fun close() { closed = true }
        fun state(phase: ConnectionPhase, problem: ConnectionProblem? = null, generation: Long = 1) =
            observer(ConnectionEvent.State(generation, phase, problem))
        fun frame(frame: ServerFrame, generation: Long = 1) = observer(ConnectionEvent.Frame(generation, frame))
        fun hello(generation: Long = 1, account: String = "account-a", space: String = "space-a", protocol: Long = 2, epoch: String = "a".repeat(32)) =
            frame(ServerFrame.Hello(protocol, RemoteUser(account, "Alice", "#123456"), RemoteSpace(space, "game", epoch), 1), generation)
        fun change(path: String = "note.md", text: String = "remote text", rev: Long = 1, generation: Long = 1) =
            frame(ServerFrame.Change(path, rev, documentHash(text), false, text, null), generation)
        fun synced(seq: Long = 1, generation: Long = 1) = frame(ServerFrame.Synced(seq), generation)
    }

    private class Harness {
        val worker = ManualWorker()
        val main = ManualWorker()
        val profileBlob = Blob()
        val identityBlob = Blob()
        val createBlob = Blob()
        val identities = ServerProfileStore(identityBlob)
        val creates = CreateIntentStore(createBlob)
        val http = SessionTestHttp()
        val credentialBlob = Blob()
        val profiles = ProfileStore(profileBlob)
        val credentials = CredentialStore(credentialBlob, CredentialKeyProvider {
            cryptoKey
        })
        val disk = Disk()
        val legacyDisk = Disk()
        val migration = IdentityMigration(legacyDisk, disk)
        val repository = DocumentsRepository(disk)
        val wires = mutableListOf<Wire>()
        val states = mutableListOf<RemoteUiState>()
        var onWire: (Wire) -> Unit = {}
        val session = RemoteSession({
            SessionDependencies(profiles, credentials, repository, serverProfiles = identities, creates = creates, http = http, migration = migration, legacyStorage = legacyDisk) { callback ->
                Wire(callback).also { onWire(it); wires.add(it) }
            }
        }, worker, main) { states.add(it) }
        val state get() = states.last()
        val wire get() = wires.last()
        fun drain() { worker.drain(); main.drain() }
        fun profile(label: String = "Work", id: String = "profile-a", credentialId: String? = null): SavedProfile {
            val ref = credentialId ?: credentials.create(Credential.fromToken(TOKEN))
            return SavedProfile.create(label, "https://remote.example", "game", ref, id).also {
                identities.upsert(ServerProfile.create(it.label, it.canonicalServerUrl, it.credentialId, it.id))
            }
        }
        fun selected(): SavedProfile {
            val p = profile()
            drain()
            session.selectProfile(p.id); drain(); authorize()
            return p
        }
        fun authorize() {
            session.verifyServer(); drain(); session.refreshProjects(); drain(); session.selectProject("space-a"); drain()
        }
        fun online(): Harness {
            selected(); session.connect(); drain()
            wire.state(ConnectionPhase.READY); wire.hello(); wire.change(); wire.synced(); drain()
            return this
        }
    }

    @Test fun initializationIsOrderedOffTheCallerAndNeverConnects() {
        val h = Harness()
        val p = h.profile()
        assertTrue(h.states.isEmpty())
        h.worker.drain()
        assertTrue(h.states.isEmpty())
        h.main.drain()
        assertEquals(listOf(p.id), h.state.serverProfiles.map { it.localProfileId })
        assertNull(h.state.selectedServer)
        assertFalse(h.state.busy)
        assertTrue(h.wires.isEmpty())
    }

    @Test fun saveEncryptsBeforeCallbackAndSelectsLocallyWithoutNetwork() {
        val h = Harness(); h.drain(); val legacy = h.profileBlob.bytes?.clone()
        var saved = false
        h.session.saveServerProfile(" New ", "remote.example/", TOKEN) { saved = true }
        h.worker.drain(); assertFalse(saved); assertNotNull(h.credentialBlob.bytes)
        assertFalse(h.credentialBlob.bytes!!.toString(Charsets.ISO_8859_1).contains(TOKEN))
        h.main.drain(); assertTrue(saved)
        assertEquals("New", h.state.serverProfiles.single().name)
        assertEquals("https://remote.example", h.state.serverProfiles.single().canonicalUrl)
        assertNull(h.state.selectedServer); assertNull(h.state.selectedProject)
        assertTrue(h.wires.isEmpty()); assertEquals(0, h.http.verifies); assertEquals(0, h.http.lists)
        assertTrue(h.http.posts.isEmpty()); assertArrayEquals(legacy, h.profileBlob.bytes)
        assertFalse(h.identityBlob.bytes!!.toString(Charsets.ISO_8859_1).contains(TOKEN))
    }

    @Test fun profileCommitFailureDoesNotCallSavedEvenWhenVaultSucceeded() {
        val h = Harness(); h.drain()
        h.identityBlob.failWrite = true
        var saved = false
        h.session.saveProfile("Work", "remote.example", "game", TOKEN) { saved = true }; h.drain()
        assertFalse(saved)
        assertNotNull(h.credentialBlob.bytes)
        assertEquals(RemoteError.CREDENTIAL_STORAGE, h.state.error)
        assertTrue(h.state.serverProfiles.isEmpty())
        assertTrue(h.wires.isEmpty())
    }

    @Test fun invalidProfileAndCredentialDoNotWriteOrCallSuccess() {
        for ((server, token) in listOf("http://remote.example" to TOKEN, "remote.example" to "invalid key")) {
            val h = Harness(); var saved = false
            h.session.saveProfile("Work", server, "game", token) { saved = true }; h.drain()
            assertEquals(RemoteError.INVALID_PROFILE, h.state.error)
            assertNull(h.credentialBlob.bytes)
            assertNull(h.profileBlob.bytes)
            assertFalse(saved)
        }
    }

    @Test fun vaultFailureDoesNotCreateDanglingProfileOrExposeException() {
        val h = Harness(); h.drain(); h.credentialBlob.failWrite = true
        var saved = false
        h.session.saveProfile("Work", "remote.example", "game", TOKEN) { saved = true }; h.drain()
        assertEquals(RemoteError.CREDENTIAL_STORAGE, h.state.error)
        assertFalse(saved); assertNull(h.profileBlob.bytes)
        assertFalse(h.state.toString().contains("secret"))
    }

    @Test fun explicitConnectUsesVaultAndOnlySendsSyncAfterHello() {
        val h = Harness(); h.selected()
        assertEquals(RemoteStatus.OFFLINE, h.state.status)
        h.session.connect(); h.drain()
        assertTrue(h.wire.tokenMatched)
        assertEquals(RemoteStatus.CONNECTING, h.state.status)
        assertTrue(h.wire.sent.isEmpty())
        h.wire.state(ConnectionPhase.READY); h.drain()
        assertEquals(RemoteStatus.SYNCING, h.state.status)
        assertTrue(h.wire.sent.isEmpty())
        h.wire.hello(); h.drain()
        assertEquals(listOf(1L to ClientFrame.Sync(0)), h.wire.sent)
        h.wire.change(); h.wire.synced(); h.drain()
        assertEquals(RemoteStatus.ONLINE, h.state.status)
        assertFalse(h.state.busy)
        assertEquals("remote text", h.state.documents["note.md"]!!.content)
    }

    @Test fun immediateConnectCallbacksAreNotLostBeforeConnectReturns() {
        val h = Harness(); h.selected()
        h.onWire = { w -> w.duringConnect = { w.hello(); w.change(); w.synced() } }
        h.session.connect(); h.drain()
        assertEquals(RemoteStatus.ONLINE, h.state.status)
        assertEquals(1, h.wire.sent.size)
    }

    @Test fun missingCredentialNeverOpensTransport() {
        val h = Harness(); val p = h.selected(); h.credentials.remove(p.credentialId)
        h.session.connect(); h.drain()
        assertEquals(RemoteError.MISSING_CREDENTIAL, h.state.error)
        assertTrue(h.wires.isEmpty())
    }

    @Test fun authenticationTlsProtocolAndNetworkHaveSafeDistinctCategories() {
        val categories = mapOf(
            ConnectionProblem.HTTP_UNAUTHORIZED to RemoteError.AUTHENTICATION,
            ConnectionProblem.KEY_REJECTED to RemoteError.AUTHENTICATION,
            ConnectionProblem.PROTOCOL_MISMATCH to RemoteError.PROTOCOL_VERSION,
            ConnectionProblem.TLS to RemoteError.TLS,
            ConnectionProblem.NETWORK to RemoteError.NETWORK,
            ConnectionProblem.OPEN_TIMEOUT to RemoteError.NETWORK,
            ConnectionProblem.HELLO_TIMEOUT to RemoteError.NETWORK,
            ConnectionProblem.BAD_CONFIGURATION to RemoteError.INVALID_PROFILE,
            ConnectionProblem.INVALID_FRAME to RemoteError.INVALID_DATA,
            ConnectionProblem.HTTP_ERROR to RemoteError.SERVER,
        )
        for ((problem, expected) in categories) {
            val h = Harness(); h.selected(); h.session.connect(); h.drain()
            h.wire.state(ConnectionPhase.STOPPED, problem); h.drain()
            assertEquals(problem.name, expected, h.state.error)
            assertFalse(h.state.busy)
            assertFalse(h.state.connectionActive)
            assertTrue(h.wire.sent.isEmpty())
            assertEquals(1, h.wires.size) // UI does not invent an automatic retry.
        }
    }

    @Test fun incompatibleHelloHasNoSyncAndClosesBinding() {
        val h = Harness(); h.selected(); h.session.connect(); h.drain()
        h.wire.hello(protocol = 1); h.drain()
        assertEquals(RemoteError.PROTOCOL_VERSION, h.state.error)
        assertTrue(h.wire.closed); assertTrue(h.wire.sent.isEmpty())
    }

    @Test fun backoffAndNewGenerationHandshakeUseDurableCursorNotHelloSeq() {
        val h = Harness().online()
        h.wire.state(ConnectionPhase.BACKOFF, ConnectionProblem.NETWORK); h.drain()
        assertEquals(RemoteStatus.OFFLINE, h.state.status)
        assertEquals(RemoteError.NETWORK, h.state.error)
        assertTrue(h.state.connectionActive)
        assertNotNull(h.state.documents["note.md"])
        h.wire.state(ConnectionPhase.AUTHENTICATING, generation = 2)
        h.wire.hello(generation = 2); h.drain()
        assertEquals(2L to ClientFrame.Sync(1), h.wire.sent.last())
        h.wire.synced(seq = 1, generation = 2); h.drain()
        assertEquals(RemoteStatus.ONLINE, h.state.status); assertNull(h.state.error)
    }

    @Test fun backoffCanBeExplicitlyDisconnectedAndCannotReconnectFromLateCallbacks() {
        val h = Harness().online()
        val old = h.wire
        old.state(ConnectionPhase.BACKOFF, ConnectionProblem.NETWORK); h.drain()
        assertTrue(h.state.connectionActive)
        assertFalse(old.closed)
        h.session.disconnect(); h.drain()
        assertTrue(old.closed); assertFalse(h.state.connectionActive)
        old.state(ConnectionPhase.AUTHENTICATING, generation = 2)
        old.hello(generation = 2); old.change(text = "late retry", rev = 9, generation = 2)
        old.synced(seq = 9, generation = 2); h.drain()
        assertEquals(RemoteStatus.OFFLINE, h.state.status)
        assertFalse(h.state.connectionActive)
        assertEquals("remote text", h.state.documents["note.md"]!!.content)
        assertEquals(1, old.connects); assertEquals(1, old.sent.size)
    }

    @Test fun oldGenerationCannotChangeCacheStatusOrSelection() {
        val h = Harness().online(); h.session.openDocument("note.md"); h.drain()
        h.wire.state(ConnectionPhase.BACKOFF, ConnectionProblem.NETWORK)
        h.wire.hello(generation = 2); h.wire.synced(generation = 2); h.drain()
        h.wire.change(text = "stale", rev = 99, generation = 1)
        h.wire.state(ConnectionPhase.STOPPED, ConnectionProblem.KEY_REJECTED, generation = 1); h.drain()
        assertEquals(RemoteStatus.ONLINE, h.state.status)
        assertNull(h.state.error)
        assertEquals("remote text", h.state.documents["note.md"]!!.content)
        assertEquals("note.md", h.state.selectedPath)
    }

    @Test fun mainThreadDoesNotReceiveSupersededGenerationSnapshots() {
        val h = Harness().online()
        val before = h.states.size
        h.wire.state(ConnectionPhase.BACKOFF, ConnectionProblem.NETWORK)
        h.worker.drain()
        h.wire.hello(generation = 2); h.wire.synced(generation = 2)
        h.worker.drain(); h.main.drain()
        assertEquals(before + 1, h.states.size)
        assertEquals(RemoteStatus.ONLINE, h.state.status)
        assertNull(h.state.error)
    }

    @Test fun stoppedGenerationCannotBeRevivedByLateHello() {
        val h = Harness().online()
        h.wire.state(ConnectionPhase.STOPPED, ConnectionProblem.KEY_REJECTED); h.drain()
        h.wire.hello(); h.wire.change(text = "late", rev = 9); h.wire.synced(seq = 9); h.drain()
        assertEquals(RemoteError.AUTHENTICATION, h.state.error)
        assertEquals("remote text", h.state.documents["note.md"]!!.content)
        assertEquals(1, h.wire.sent.size)
    }

    @Test fun lateReadyCannotReviveRetiredBackoffGeneration() {
        val h = Harness().online()
        h.wire.state(ConnectionPhase.BACKOFF, ConnectionProblem.NETWORK); h.drain()
        h.wire.state(ConnectionPhase.READY); h.drain()
        assertEquals(RemoteStatus.OFFLINE, h.state.status)
        assertEquals(RemoteError.NETWORK, h.state.error)
    }

    @Test fun tombstoneRemovesListEntryAndOpenSelectionWithoutSendingWrite() {
        val h = Harness().online()
        h.session.openDocument("note.md"); h.drain(); assertEquals("note.md", h.state.selectedPath)
        h.wire.frame(ServerFrame.Change("note.md", 2, null, true, null, null)); h.drain()
        assertTrue(h.state.documents.isEmpty()); assertNull(h.state.selectedPath)
        assertTrue(h.repository.view().documents["note.md"]!!.deleted)
        assertTrue(h.wire.sent.all { it.second is ClientFrame.Sync })
    }

    @Test fun caseOnlyPathsStaySeparateAndExposeAWarning() {
        val h = Harness().online()
        h.wire.change(path = "Note.md", text = "upper", rev = 2)
        h.wire.change(path = "note.md", text = "lower", rev = 3)
        h.drain()
        assertEquals("upper", h.state.documents["Note.md"]!!.content)
        assertEquals("lower", h.state.documents["note.md"]!!.content)
        assertTrue(h.state.hasCaseCollisions)
    }

    @Test fun documentOpenBackAndInvalidPathsAreLocalOnly() {
        val h = Harness().online(); val count = h.wire.sent.size
        h.session.openDocument("missing.md"); h.drain(); assertNull(h.state.selectedPath)
        repeat(10) {
            h.session.openDocument("note.md"); h.drain(); assertEquals("note.md", h.state.selectedPath)
            h.session.closeDocument(); h.drain(); assertNull(h.state.selectedPath)
        }
        assertEquals(count, h.wire.sent.size)
    }

    @Test fun replacementSpaceClearsSelectedPathEvenWhenSamePathReturns() {
        val h = Harness().online(); h.session.openDocument("note.md"); h.drain()
        h.wire.state(ConnectionPhase.BACKOFF, ConnectionProblem.NETWORK)
        h.wire.hello(generation = 2, space = "space-b")
        h.wire.change(text = "other namespace", generation = 2); h.wire.synced(generation = 2); h.drain()
        assertNull(h.state.selectedPath); assertTrue(h.state.documents.isEmpty())
        assertEquals(RemoteError.INVALID_DATA, h.state.error); assertTrue(h.wire.closed)
        assertEquals(1, h.wire.sent.size) // no sync or automatic identity adoption
    }

    @Test fun delayedOpenFromDisplayedNamespaceDoesNotOpenSamePathInReplacementNamespace() {
        val h = Harness().online()
        h.session.selectServerProfile(h.state.selectedServer!!.localProfileId)
        h.worker.drain() // Old namespace remains displayed until the UI queue runs.
        h.session.openDocument("note.md"); h.drain()
        assertNull(h.state.selectedPath); assertTrue(h.state.documents.isEmpty())
    }

    @Test fun explicitDocumentEpochRejectsOldScreenClickEvenAfterNewNamespaceIsDisplayed() {
        val h = Harness().online(); val oldEpoch = h.state.documentEpoch
        h.http.projects = h.http.projects + ProjectSummary("space-b", "Other", "b".repeat(32), 1, "now")
        h.session.refreshProjects(); h.drain(); h.session.selectProject("space-b"); h.drain()
        h.session.connect(); h.drain(); h.wire.hello(space = "space-b", epoch = "b".repeat(32)); h.wire.change(); h.wire.synced(); h.drain()
        h.session.openDocument("note.md", oldEpoch); h.drain(); assertNull(h.state.selectedPath)
        h.session.openDocument("note.md"); h.drain(); assertEquals("note.md", h.state.selectedPath)
    }

    @Test fun profileSwitchFencesQueuedOldFramesAndPostedUiSnapshots() {
        val h = Harness().online(); val b = h.profile("Other", "profile-b"); val old = h.wire
        old.change(text = "private A", rev = 2); h.worker.drain()
        val before = h.states.size
        h.session.selectProfile(b.id); old.change(text = "late A", rev = 3); h.main.drain()
        assertEquals(before, h.states.size); h.drain()
        assertTrue(old.closed); assertEquals(b.id, h.state.selectedServer!!.localProfileId)
        assertTrue(h.state.documents.isEmpty()); assertNull(h.state.selectedPath)
        h.http.account = "account-b"; h.authorize(); h.session.connect(); h.drain()
        old.hello(generation = 8); old.change(text = "late", rev = 8, generation = 8)
        h.wire.hello(account = "account-b"); h.wire.change(text = "B"); h.wire.synced(); h.drain()
        assertEquals("B", h.state.documents["note.md"]!!.content)
        assertTrue(old.sent.all { it.second is ClientFrame.Sync })
    }

    @Test fun latestSelectionWinsBeforeWorkerStarts() {
        val h = Harness(); val a = h.profile(); val b = h.profile("Other", "profile-b"); h.drain()
        h.session.selectProfile(a.id); h.session.connect(); h.session.selectProfile(b.id); h.drain()
        assertEquals(b.id, h.state.selectedServer!!.localProfileId); assertTrue(h.wires.isEmpty())
    }

    @Test fun oldConfirmationCannotConnectAnotherProfileBeforeItsUiSnapshotArrives() {
        val h = Harness()
        val a = h.profile(); val b = h.profile("Other", "profile-b")
        h.drain(); h.session.selectProfile(a.id); h.drain()
        h.session.selectProfile(b.id)
        h.worker.drain() // Native owns B, UI still displays A.
        h.session.connect(a.id)
        h.drain()
        assertEquals(b.id, h.state.selectedServer!!.localProfileId)
        assertTrue(h.wires.isEmpty())
        h.authorize(); h.session.connect(b.id); h.drain()
        assertEquals(1, h.wires.size)
    }

    @Test fun savedCallbackDoesNotCloseUiAfterProfileSelectionChanged() {
        val h = Harness(); val a = h.selected()
        var saved = false
        h.session.saveProfile("New", "new.example", "new-space", TOKEN) { saved = true }
        h.worker.drain() // Persisted, success callback is waiting on main.
        h.session.selectProfile(a.id)
        h.drain()
        assertFalse(saved)
        assertEquals(a.id, h.state.selectedServer!!.localProfileId)
        assertEquals(2, h.state.serverProfiles.size) // Local save remains durable, only the callback is fenced.
        assertTrue(h.wires.isEmpty())
    }

    @Test fun invalidProfileSelectionClearsOldBindingAndSelection() {
        val h = Harness().online()
        h.session.selectProfile("missing-profile"); h.drain()
        assertNull(h.state.selectedServer)
        assertTrue(h.state.documents.isEmpty())
        assertEquals(RemoteError.INVALID_PROFILE, h.state.error)
        assertTrue(h.wire.closed)
        h.session.connect(); h.drain(); assertEquals(1, h.wires.size)
    }

    @Test fun disconnectRetainsOfflineCacheAndRejectsDelayedWireEvents() {
        val h = Harness().online(); val old = h.wire
        h.session.disconnect(); old.change(text = "late", rev = 3); h.drain()
        assertTrue(old.closed)
        assertEquals(RemoteStatus.OFFLINE, h.state.status)
        assertTrue(h.state.cacheAvailable)
        assertEquals("remote text", h.state.documents["note.md"]!!.content)
        assertNull(h.state.error)
    }

    @Test fun retryCreatesNewImmutableBindingAndOnlySyncs() {
        val h = Harness().online(); val old = h.wire
        h.session.retry(); h.drain()
        assertTrue(old.closed); assertEquals(2, h.wires.size)
        h.wire.hello(); h.wire.synced(); h.drain()
        assertEquals(listOf(1L to ClientFrame.Sync(1)), h.wire.sent)
        assertEquals(RemoteStatus.ONLINE, h.state.status)
    }

    @Test fun lastAuthenticatedCacheIsReadableAfterSessionRestartWithoutCredentialOrNetwork() {
        val h = Harness().online(); val p = h.state.selectedServer!!; val b = h.state.selectedProject!!
        h.session.close(); h.worker.drain(); h.credentials.remove(p.credentialId)
        val worker = ManualWorker(); val main = ManualWorker(); val states = mutableListOf<RemoteUiState>()
        val http = SessionTestHttp()
        val restarted = RemoteSession({
            SessionDependencies(h.profiles, h.credentials, DocumentsRepository(h.disk), serverProfiles = h.identities,
                creates = h.creates, http = http) { error("no network offline") }
        }, worker, main) { states.add(it) }
        worker.drain(); main.drain()
        restarted.selectServerProfile(p.localProfileId); worker.drain(); main.drain()
        assertTrue(states.last().documents.isEmpty())
        restarted.selectKnownProject(b.bindingId); worker.drain(); main.drain()
        assertEquals(RemoteStatus.OFFLINE, states.last().status)
        assertEquals("remote text", states.last().documents["note.md"]!!.content)
        assertEquals(VerificationStatus.UNVERIFIED, states.last().verificationStatus)
        assertEquals(0, http.verifies); assertTrue(http.posts.isEmpty())
        restarted.close(); worker.drain()
    }

    @Test fun accountMismatchIsFailClosedAndDoesNotDisplayOtherAccount() {
        val h = Harness().online(); h.session.retry(); h.drain()
        h.wire.hello(account = "different-account"); h.wire.change(text = "secret other", rev = 2); h.drain()
        assertEquals(RemoteError.INVALID_DATA, h.state.error)
        assertTrue(h.state.documents.isEmpty()); assertTrue(h.wire.closed)
        assertTrue(h.wire.sent.isEmpty())
    }

    @Test fun failedDiskApplicationStopsSocketAndSendsNothing() {
        val h = Harness(); h.selected(); h.session.connect(); h.drain(); h.disk.fail = true
        h.wire.hello(); h.wire.change(); h.drain()
        assertEquals(RemoteError.STORAGE, h.state.error)
        assertTrue(h.wire.closed); assertTrue(h.wire.sent.all { it.second is ClientFrame.Sync })
        assertFalse(h.state.busy); assertTrue(h.state.documents.isEmpty())
    }

    @Test fun corruptProfileStoreIsAnErrorNotSilentEmptySuccess() {
        val h = Harness(); h.profileBlob.bytes = byteArrayOf(1, 2, 3); h.drain()
        assertEquals(RemoteError.STORAGE, h.state.error)
        assertFalse(h.state.busy); assertTrue(h.wires.isEmpty())
    }

    @Test fun corruptDurableJournalStopsOfflineSelectionWithoutNetwork() {
        val h = Harness().online(); val b = h.state.selectedProject!!
        h.disk.drafts[DocumentPartition(b.canonicalUrl,b.projectId,b.expectedAccountId,b.storageEpoch).journalKey()] = byteArrayOf(1,2,3)
        h.session.selectKnownProject(b.bindingId); h.drain()
        assertEquals(RemoteError.STORAGE, h.state.error)
        assertTrue(h.state.documents.isEmpty()); assertEquals(1, h.wires.size)
    }

    @Test fun rejectedSyncSendStopsBindingAndRetainsCache() {
        val h = Harness().online(); h.session.retry(); h.drain()
        h.wire.sendResult = SendResult.NOT_READY
        h.wire.hello(); h.drain()
        assertEquals(RemoteError.NETWORK, h.state.error)
        assertTrue(h.wire.closed)
        assertEquals("remote text", h.state.documents["note.md"]!!.content)
    }

    @Test fun lateSendResultCannotStopNewGenerationOrNewProfile() {
        val h = Harness().online(); h.session.retry(); h.drain()
        h.wire.deferResults = true
        h.wire.hello(); h.drain(); val oldResult = h.wire.results.single()
        h.wire.state(ConnectionPhase.BACKOFF, ConnectionProblem.NETWORK)
        h.wire.hello(generation = 2); h.wire.synced(generation = 2); h.drain()
        oldResult(SendResult.CLOSED); h.drain()
        assertEquals(RemoteStatus.ONLINE, h.state.status); assertNull(h.state.error)
    }

    @Test fun serverErrorIsCategoricalAndNeverShowsRawMessage() {
        val h = Harness().online()
        h.wire.frame(ServerFrame.Error("unsafe $TOKEN", "sync", null, null, null)); h.drain()
        assertEquals(RemoteError.SERVER, h.state.error)
        assertTrue(h.wire.closed); assertFalse(h.state.toString().contains(TOKEN))
    }

    @Test fun boundedInboundBacklogStopsRatherThanRetainingUnboundedFrames() {
        val h = Harness().online()
        repeat(600) { h.wire.change(text = "queued", rev = it + 2L) }
        h.drain()
        assertTrue(h.wire.closed); assertEquals(RemoteError.NETWORK, h.state.error)
        assertEquals("remote text", h.state.documents["note.md"]!!.content)
    }

    @Test fun hundredSmallDocumentsCanQueueBeforeWorkerAndPersistSuccessfulFullSync() {
        val h = Harness(); val profile = h.selected()
        h.session.connect(); h.drain()
        h.wire.hello()
        repeat(100) { i -> h.wire.change(path = "folder/note-$i.md", text = "document $i", rev = i + 1L) }
        h.wire.synced(seq = 100)
        // Intentionally no worker drain between network frames: a normal full-replay burst.
        h.drain()
        assertEquals(RemoteStatus.ONLINE, h.state.status)
        assertTrue(h.state.connectionActive); assertFalse(h.wire.closed)
        assertNull(h.state.error); assertEquals(100, h.state.documents.size)
        assertEquals(1, h.wire.sent.size)
        h.session.close(); h.worker.drain()
        val restarted = DocumentsRepository(h.disk)
        restarted.selectPartition(DocumentPartition(profile.canonicalServerUrl, "space-a", "account-a", "a".repeat(32)))
        assertEquals(100, restarted.view().documents.size)
        assertEquals(100L, restarted.view().cursor)
    }

    @Test fun inboundByteBudgetAlsoBoundsSmallNumbersOfLargeDocuments() {
        val h = Harness().online()
        val large = "x".repeat(1_000_000)
        repeat(20) { h.wire.change(text = large, rev = it + 2L) }
        h.drain()
        assertTrue(h.wire.closed); assertEquals(RemoteError.NETWORK, h.state.error)
        assertEquals("remote text", h.state.documents["note.md"]!!.content)
    }

    @Test fun closeDisposesOnceDropsQueuedIntentsAndMainDeliveries() {
        val h = Harness().online(); val count = h.states.size
        h.session.openDocument("note.md"); h.worker.drain()
        h.session.close(); h.session.close(); h.worker.drain(); h.main.drain()
        assertTrue(h.wire.closed); assertTrue(h.worker.isShutdown)
        assertEquals(count, h.states.size)
        h.wire.change(); h.session.connect(); h.session.retry(); h.session.disconnect()
        assertEquals(count, h.states.size)
    }

    @Test fun viewModelExposesComposeStateAndForwardsSessionIntents() {
        val h = Harness(); val p = h.profile()
        val vm = RemoteViewModel { observer ->
            RemoteSession({ SessionDependencies(h.profiles, h.credentials, DocumentsRepository(h.disk), serverProfiles = h.identities, creates = h.creates, http = h.http) { error("offline") } },
                h.worker, h.main, observer)
        }
        h.drain(); vm.selectProfile(p.id); h.drain()
        assertEquals(p.id, vm.state.value.selectedServer!!.localProfileId)
        vm.closeDocument(); vm.disconnect(); h.drain()
        assertEquals(RemoteStatus.DISCONNECTED, vm.state.value.status)
        val store = ViewModelStore()
        store.put("session", vm)
        store.clear(); h.worker.drain()
        assertTrue(h.worker.isShutdown)
    }

    @Test fun verifyAndCatalogAreIndependentHttpOnlyExplicitIntents() {
        val h=Harness();val p=h.profile();h.drain();h.session.selectProfile(p.id);h.drain()
        assertEquals(0,h.http.verifies);assertEquals(0,h.http.lists)
        h.session.verifyServer();h.drain()
        assertEquals(VerificationStatus.VERIFIED,h.state.verificationStatus)
        assertEquals(0,h.http.lists);assertTrue(h.wires.isEmpty());assertTrue(h.http.posts.isEmpty())
        h.session.refreshProjects();h.drain()
        assertEquals(1,h.http.lists);assertNull(h.state.selectedProject);assertTrue(h.wires.isEmpty())
    }
    @Test fun legacyServerVerificationNeverFallsBackToLegacyNetworking() {
        val h=Harness();val p=h.profile();h.drain();h.session.selectProfile(p.id);h.drain()
        h.http.protocol=1;h.session.verifyServer();h.drain()
        assertEquals(VerificationStatus.UNSUPPORTED,h.state.verificationStatus)
        h.session.refreshProjects();h.session.createProject("new","Name");h.session.connect();h.drain()
        assertEquals(0,h.http.lists);assertTrue(h.http.posts.isEmpty());assertTrue(h.wires.isEmpty())
    }
    @Test fun sameCredentialIdRevisionChangeRejectsQueuedVerificationResult() {
        val h=Harness();val p=h.profile();h.drain();h.session.selectProfile(p.id);h.drain()
        h.http.deferVerify=true;h.session.verifyServer();h.drain()
        h.identities.upsert(ServerProfile.create(p.label,p.canonicalServerUrl,p.credentialId,p.id,2))
        h.http.verifyReplies.single().invoke();h.drain()
        assertNull(h.state.verifiedAccount);assertTrue(h.state.projects.isEmpty());assertTrue(h.wires.isEmpty())
    }
    @Test fun staleCatalogAndVerifyCallbacksCannotCrossServerSwitch() {
        val h=Harness();val a=h.profile();val b=h.profile("Other","other");h.drain()
        h.session.selectProfile(a.id);h.drain();h.http.deferVerify=true;h.session.verifyServer();h.drain()
        val verify=h.http.verifyReplies.single()
        h.session.selectProfile(b.id);verify();h.drain()
        assertNull(h.state.verifiedAccount);assertEquals(b.id,h.state.selectedServer!!.localProfileId)
        h.http.deferVerify=false;h.session.verifyServer();h.drain();h.http.deferList=true
        h.session.refreshProjects();h.drain();val list=h.http.listReplies.single()
        h.session.selectProfile(a.id);list();h.drain()
        assertTrue(h.state.projects.isEmpty());assertNull(h.state.verifiedAccount)
    }
    @Test fun createPersistsUnknownBeforePostAndNeverSelectsEvenOnSuccess() {
        val h=Harness();h.selected()
        h.session.createProject("original-id","Name");h.drain()
        assertEquals(CreateOutcome.UNKNOWN,h.creates.list().single().outcome)
        assertEquals(listOf("original-id" to "Name"),h.http.posts)
        val before=h.state.selectedProject
        val project=ProjectSummary("original-id","Name","b".repeat(32),1,"now")
        h.http.createReplies.single()(CatalogResult.Success(project));h.drain()
        assertEquals(CreateOutcome.CREATED,h.creates.list().single().outcome)
        assertSame(before,h.state.selectedProject);assertTrue(h.wires.isEmpty())
    }
    @Test fun createStorageFailureAndDuplicateUnknownNeverDispatchPost() {
        val h=Harness();h.selected();h.createBlob.failWrite=true
        h.session.createProject("original-id","Name");h.drain();assertTrue(h.http.posts.isEmpty())
        h.createBlob.failWrite=false;h.session.createProject("original-id","Name");h.drain()
        h.session.createProject("original-id","Name");h.drain()
        assertEquals(1,h.http.posts.size);assertEquals(CreateOutcome.UNKNOWN,h.creates.list().single().outcome)
    }
    @Test fun unknownCreateRestartUsesOriginalIdGetWithoutPostOrSelection() {
        val h=Harness();h.selected();h.session.createProject("original-id","Name");h.drain()
        h.session.close();h.worker.drain()
        val worker=ManualWorker();val main=ManualWorker();val states=mutableListOf<RemoteUiState>();val http=SessionTestHttp()
        val project=ProjectSummary("original-id","Other race winner","b".repeat(32),1,"now")
        http.getResult=CatalogResult.Success(project)
        val session=RemoteSession({SessionDependencies(h.profiles,h.credentials,DocumentsRepository(h.disk),
            serverProfiles=ServerProfileStore(h.identityBlob),creates=CreateIntentStore(h.createBlob),http=http) { error("no socket") }},worker,main) { states+=it }
        fun drain() { worker.drain();main.drain() }
        drain();assertEquals(0,http.verifies);assertTrue(http.gets.isEmpty())
        session.selectServerProfile(h.state.selectedServer!!.localProfileId);drain();session.verifyServer();drain()
        assertEquals(listOf("original-id"),http.restores)
        session.createProject("original-id","Name");drain();assertTrue(http.posts.isEmpty())
        session.resolveCreate("original-id");drain()
        assertEquals(listOf("original-id"),http.gets);assertTrue(http.posts.isEmpty());assertNull(states.last().selectedProject)
        assertEquals("Other race winner",states.last().createIntents.single().observedProject!!.displayName)
        session.close();worker.drain()
    }
    @Test fun lateCreateResultAfterSwitchLeavesDurableUnknownInOriginalAccount() {
        val h=Harness();h.selected();val b=h.profile("Other","other")
        h.session.createProject("original-id","Name");h.drain()
        h.session.selectServerProfile(b.id)
        h.http.createReplies.single()(CatalogResult.Success(ProjectSummary("original-id","Name","b".repeat(32),1,"now")))
        h.drain();assertTrue(h.state.projects.isEmpty());assertTrue(h.state.createIntents.isEmpty())
        assertEquals(CreateOutcome.UNKNOWN,CreateIntentStore(h.createBlob).list().single().outcome)
    }
    @Test fun originalId404RequiresAnotherExplicitCreateAndDoesNotRetryItself() {
        val h=Harness();h.selected();h.session.createProject("original-id","Name");h.drain()
        h.session.resolveCreate("original-id");h.drain();assertTrue(h.http.gets.isEmpty()) // original POST still active
        h.http.createReplies.single()(CatalogResult.CreateUncertain("original-id"));h.drain()
        h.session.resolveCreate("original-id");h.drain()
        assertEquals(CreateOutcome.NOT_CREATED,h.state.createIntents.single().outcome)
        assertEquals(1,h.http.posts.size)
        h.session.createProject("original-id","Name");h.drain();assertEquals(2,h.http.posts.size)
    }
    @Test fun staleUiConfirmationCannotCreateSelectOrConnectNewServer() {
        val h=Harness();h.selected();val generation=h.state.selectionGeneration;val b=h.profile("Other","other")
        h.session.selectServerProfile(b.id);h.worker.drain()
        h.session.createProject("new-id","Name",generation);h.session.selectProject("space-a",generation)
        h.session.connect(b.id,generation);h.drain()
        assertTrue(h.http.posts.isEmpty());assertTrue(h.wires.isEmpty());assertNull(h.state.selectedProject)
    }
    @Test fun changedCatalogEpochInvalidatesPreviouslyDisplayedSelectConfirmation() {
        val h=Harness();h.selected();val generation=h.state.selectionGeneration
        h.http.projects=h.http.projects.map { it.copy(storageEpoch="b".repeat(32)) }
        h.session.refreshProjects();h.drain();h.session.selectProject("space-a",generation);h.drain()
        assertEquals("a".repeat(32),h.state.selectedProject!!.storageEpoch)
    }
    @Test fun replacedCredentialRevokesOfflineBindingAndHistoricalVerificationIsNotLive() {
        val h=Harness().online();val p=h.state.selectedServer!!;val binding=h.state.selectedProject!!
        h.session.replaceServerKey(p.localProfileId,"replacement-token") {};h.drain()
        assertEquals(2L,h.state.selectedServer!!.credentialRevision)
        assertEquals(VerificationStatus.UNVERIFIED,h.state.verificationStatus);assertTrue(h.state.documents.isEmpty())
        h.session.selectKnownProject(binding.bindingId);h.drain();assertTrue(h.state.documents.isEmpty())
        assertNull(h.state.selectedProject);assertTrue(h.wire.closed)
    }

    @Test fun legacySourcesStayByteExactAndActivationIsSeparateFromPrepareSelectAndConnect() {
        val h=Harness();val credential=h.credentials.create(Credential.fromToken(TOKEN))
        val p=SavedProfile.create("Legacy","https://remote.example","game",credential,"legacy")
        h.profiles.upsert(p)
        val old=DocumentsRepository(h.legacyDisk);val owner=old.selectProfile(p);val id="c".repeat(32)
        old.onEvent(owner,ConnectionEvent.Frame(1,ServerFrame.Hello(1,RemoteUser("account-a","Alice","#123456"),RemoteSpace(id,"game"),1)))
        old.onEvent(owner,ConnectionEvent.Frame(1,ServerFrame.Change("note.md",1,documentHash("legacy text"),false,"legacy text",null)))
        old.onEvent(owner,ConnectionEvent.Frame(1,ServerFrame.Synced(1)))
        val scope=old.commandScope(owner)
        old.queueCreate(scope,"new.md","generation N")
        assertNotNull(old.nextWrite(scope,"new.md"))
        old.saveDraft(scope,"new.md","newer generation N+1")
        val source=(h.legacyDisk.cache+h.legacyDisk.drafts).mapValues { it.value.toList() }
        val profiles=h.profileBlob.bytes!!.toList()
        h.http.projects=listOf(ProjectSummary(id,"Renamed label",id,2,"now"))
        h.drain();h.session.selectServerProfile(p.id);h.drain();h.session.verifyServer();h.drain();h.session.refreshProjects();h.drain()
        h.session.selectProject(id);h.drain()
        assertTrue(h.state.migrationRequired);assertNull(h.state.selectedProject);assertTrue(h.state.documents.isEmpty())
        h.session.prepareLegacyMigration();h.drain()
        assertEquals(IdentityMigrationPhase.VERIFIED,h.state.migrationStatus!!.phase)
        assertNull(h.state.selectedProject);assertTrue(h.wires.isEmpty())
        h.session.selectProject(id);h.drain();assertNull(h.state.selectedProject);assertTrue(h.state.documents.isEmpty())
        h.session.activateLegacyProject(id);h.drain()
        assertEquals(IdentityMigrationPhase.ACTIVATED,h.state.migrationStatus!!.phase)
        assertNull(h.state.selectedProject);assertTrue(h.wires.isEmpty())
        h.session.selectProject(id);h.drain()
        assertEquals("legacy text",h.state.documents["note.md"]!!.content)
        assertEquals("newer generation N+1",h.state.localDrafts.single().content)
        h.session.connect();h.drain();h.wire.hello(space=id,epoch=id);h.wire.synced();h.drain()
        assertTrue(h.wire.sent.all { it.second is ClientFrame.Sync })
        assertEquals(source,(h.legacyDisk.cache+h.legacyDisk.drafts).mapValues { it.value.toList() })
        assertEquals(profiles,h.profileBlob.bytes!!.toList())
    }
    @Test fun migrationFaultDoesNotActivateOrWriteLegacyAndCanResumeExplicitly() {
        val h=Harness();val credential=h.credentials.create(Credential.fromToken(TOKEN))
        val p=SavedProfile.create("Legacy","https://remote.example","game",credential,"legacy")
        h.profiles.upsert(p);val source=h.profileBlob.bytes!!.toList()
        h.drain();h.session.selectServerProfile(p.id);h.drain();h.session.verifyServer();h.drain()
        h.disk.fail=true;h.session.prepareLegacyMigration();h.drain()
        assertEquals(RemoteError.STORAGE,h.state.error);assertNull(h.state.migrationStatus);assertTrue(h.wires.isEmpty())
        assertEquals(source,h.profileBlob.bytes!!.toList());assertTrue(h.legacyDisk.drafts.isEmpty())
        h.disk.fail=false;h.session.prepareLegacyMigration();h.drain()
        assertEquals(IdentityMigrationPhase.VERIFIED,h.state.migrationStatus!!.phase)
        assertTrue(h.state.migrationStatus!!.activated.isEmpty());assertNull(h.state.selectedProject)
    }
    @Test fun activatedMetadataNeverReimportsChangedLegacyProfilesOnRestart() {
        val h=Harness();val p=h.profile();h.drain();h.profileBlob.bytes=byteArrayOf(1,2,3)
        h.session.close();h.worker.drain()
        val worker=ManualWorker();val main=ManualWorker();val states=mutableListOf<RemoteUiState>()
        val restarted=RemoteSession({SessionDependencies(h.profiles,h.credentials,DocumentsRepository(h.disk),
            serverProfiles=h.identities,creates=h.creates,http=SessionTestHttp()) { error("no socket") }},worker,main) { states+=it }
        worker.drain();main.drain()
        assertNull(states.last().error);assertEquals(p.id,states.last().serverProfiles.single().localProfileId)
        restarted.close();worker.drain()
    }

    @Test fun offlineLegacyArchiveIncludesPendingNewerDraftAndRawOnlyRoomWithoutActivation() {
        val h=Harness();val credential=h.credentials.create(Credential.fromToken(TOKEN))
        val p=SavedProfile.create("Legacy","https://remote.example","game",credential,"legacy")
        h.profiles.upsert(p)
        val old=DocumentsRepository(h.legacyDisk);val owner=old.selectProfile(p);val id="d".repeat(32)
        old.onEvent(owner,ConnectionEvent.Frame(1,ServerFrame.Hello(1,RemoteUser("account-a","Alice","#123456"),RemoteSpace(id,"game"),1)))
        old.onEvent(owner,ConnectionEvent.Frame(1,ServerFrame.Change("note.md",1,documentHash("cached"),false,"cached",null)))
        old.onEvent(owner,ConnectionEvent.Frame(1,ServerFrame.Synced(1)))
        old.queueCreate(old.commandScope(owner),"new.md","pending N")
        assertNotNull(old.nextWrite(old.commandScope(owner),"new.md"))
        old.saveDraft(old.commandScope(owner),"new.md","draft N+1")
        val original=DocumentNamespace(p.canonicalServerUrl,"account-a",id)
        com.voxarium.remote.data.room.RoomRecoveryStore(h.legacyDisk).persist(original,"raw.md",
            com.voxarium.remote.data.room.EditorContext(8,1,"old-room"),null,listOf("AQ=="))
        h.legacyDisk.roomNamespaces+=original
        val source=(h.legacyDisk.drafts+h.legacyDisk.cache).mapValues { it.value.toList() }
        h.drain();h.credentials.remove(credential) // vault/key unavailable is irrelevant to read-only archive
        h.session.selectServerProfile(p.id);h.drain();h.session.prepareLegacyArchive();h.drain()
        assertEquals(IdentityMigrationPhase.VERIFIED,h.state.migrationStatus!!.phase)
        h.session.loadLegacyArchive(id);h.drain()
        val entries=h.state.legacyArchive
        assertEquals(setOf("pending N","draft N+1","cached"),entries.mapNotNull { it.markdown }.toSet())
        assertTrue(entries.all { it.namespace==original && it.namespace.storageEpoch==null })
        val raw=entries.single { it.path=="raw.md" };assertNull(raw.markdown);assertEquals(1,raw.rawItemCount)
        assertNull(h.state.selectedProject);assertNull(h.state.documentNamespace)
        assertEquals(0,h.http.verifies);assertEquals(0,h.http.lists);assertTrue(h.http.gets.isEmpty())
        assertTrue(h.http.posts.isEmpty());assertTrue(h.wires.isEmpty())
        assertTrue(h.state.migrationStatus!!.activated.isEmpty())
        h.session.activateLegacyProject(id);h.drain();assertTrue(h.state.migrationStatus!!.activated.isEmpty())
        assertEquals(source,(h.legacyDisk.drafts+h.legacyDisk.cache).mapValues { it.value.toList() })
    }
    @Test fun offlineLegacyArchiveRejectsUnknownAccountAndRotatedCredentialIdentity() {
        val h=Harness();val credential=h.credentials.create(Credential.fromToken(TOKEN))
        val p=SavedProfile.create("Unresolved","https://remote.example","game",credential,"legacy")
        h.profiles.upsert(p);h.drain();h.session.selectServerProfile(p.id);h.drain()
        h.session.prepareLegacyArchive();h.drain()
        assertNull(h.state.migrationStatus);assertTrue(h.state.legacyArchive.isEmpty())
        assertEquals(0,h.http.verifies);assertTrue(h.wires.isEmpty())
        h.session.replaceServerKey(p.id,"new-key") {};h.drain();h.session.prepareLegacyArchive();h.drain()
        assertNull(h.state.migrationStatus);assertTrue(h.state.legacyArchive.isEmpty())
    }

    @Test fun dispatchExceptionKeepsUnknownButAllowsOriginalIdResolution() {
        val h=Harness();h.selected();h.http.throwCreate=true
        h.session.createProject("original-id","Name");h.drain()
        assertEquals(CreateOutcome.UNKNOWN,h.creates.list().single().outcome)
        assertTrue(h.state.creatingProjectIds.isEmpty());assertTrue(h.http.posts.isEmpty())
        h.http.throwCreate=false;h.session.resolveCreate("original-id");h.drain()
        assertEquals(listOf("original-id"),h.http.gets)
        assertEquals(CreateOutcome.NOT_CREATED,h.creates.list().single().outcome)
    }

    @Test fun latestVerifiedAccountFencesKnownCacheAcrossReselectAndRestart() {
        val h=Harness().online();val p=h.state.selectedServer!!;val old=h.state.selectedProject!!
        h.http.account="account-b";h.session.verifyServer();h.drain()
        assertEquals("account-b",h.state.verifiedAccount!!.id)
        h.session.selectKnownProject(old.bindingId);h.drain()
        assertTrue(h.state.documents.isEmpty());assertNull(h.state.selectedProject)
        h.session.selectServerProfile(p.localProfileId);h.drain()
        assertNull(h.state.verifiedAccount)
        assertEquals("account-b",h.state.selectedServer!!.verification!!.accountId)
        h.session.selectKnownProject(old.bindingId);h.drain()
        assertTrue(h.state.documents.isEmpty());assertNull(h.state.selectedProject)
        h.session.close();h.worker.drain()
        val worker=ManualWorker();val main=ManualWorker();val states=mutableListOf<RemoteUiState>();val http=SessionTestHttp()
        val restarted=RemoteSession({SessionDependencies(h.profiles,h.credentials,DocumentsRepository(h.disk),
            serverProfiles=ServerProfileStore(h.identityBlob),creates=h.creates,http=http) { error("no socket") }},worker,main) { states+=it }
        fun drain() {worker.drain();main.drain()}
        drain();restarted.selectServerProfile(p.localProfileId);drain()
        assertEquals("account-b",states.last().selectedServer!!.verification!!.accountId)
        assertEquals(VerificationStatus.UNVERIFIED,states.last().verificationStatus)
        restarted.selectKnownProject(old.bindingId);drain()
        assertTrue(states.last().documents.isEmpty());assertNull(states.last().selectedProject)
        assertEquals(0,http.verifies);assertTrue(http.gets.isEmpty())
        // The old account's bytes remain archived, not deleted to implement isolation.
        val reader=DocumentsRepository(h.disk)
        reader.selectPartition(DocumentPartition(old.canonicalUrl,old.projectId,old.expectedAccountId,old.storageEpoch))
        assertEquals("remote text",reader.view().documents["note.md"]!!.content)
        restarted.close();worker.drain()
    }

    @Test fun knownCacheChecksPersistedAccountEvenWhenSelectedMetadataSnapshotIsOlder() {
        val h=Harness().online();val p=h.state.selectedServer!!;val old=h.state.selectedProject!!
        h.session.selectServerProfile(p.localProfileId);h.drain() // no live verification, selected snapshot says A
        val newer=ServerVerification.create(p.canonicalUrl,p.credentialId,p.credentialRevision,999,"account-b",2,123,
            "B","#654321",2)
        h.identities.upsert(ServerProfile.create(p.name,p.canonicalUrl,p.credentialId,p.localProfileId,p.credentialRevision,newer))
        h.session.selectKnownProject(old.bindingId);h.drain()
        assertTrue(h.state.documents.isEmpty());assertNull(h.state.selectedProject)
        assertEquals("account-b",h.state.selectedServer!!.verification!!.accountId)
    }

    @Test fun knownBindingWithoutPersistedAuthenticationDoesNotInventAnAccountGrant() {
        val h=Harness();val p=h.profile();val target=ProjectBinding.create(h.identities.snapshot().profiles.single(),
            "space-a","account-a","a".repeat(32))
        h.identities.addBinding(target);h.drain();h.session.selectServerProfile(p.id);h.drain()
        h.session.selectKnownProject(target.bindingId);h.drain()
        assertNull(h.state.selectedProject);assertTrue(h.state.documents.isEmpty());assertTrue(h.wires.isEmpty())
    }

    @Test fun staleKeyActionQueuedBeforeNewServerIntentCannotMutateVaultOrMetadata() {
        val h=Harness().online();val a=h.state.selectedServer!!;val b=h.profile("Other","other")
        val captured=h.state.selectionGeneration;val vault=h.credentialBlob.bytes!!.toList();var saved=false
        h.session.replaceServerKey(a.localProfileId,"new-key",captured) {saved=true}
        h.session.selectServerProfile(b.id);h.drain()
        assertEquals(b.id,h.state.selectedServer!!.localProfileId);assertFalse(saved)
        assertEquals(vault,h.credentialBlob.bytes!!.toList())
        assertEquals(1L,h.identities.snapshot().profiles.single {it.localProfileId==a.localProfileId}.credentialRevision)
    }

    @Test fun retainedOldKeyFormCannotOverrideRequestedOrAlreadyDisplayedNewServer() {
        for(deliverAfterDisplay in listOf(false,true)) {
            val h=Harness().online();val a=h.state.selectedServer!!;val b=h.profile("Other","other")
            val captured=h.state.selectionGeneration;val vault=h.credentialBlob.bytes!!.toList();var saved=false
            h.session.selectServerProfile(b.id)
            if(deliverAfterDisplay) h.drain()
            h.session.replaceServerKey(a.localProfileId,"new-key",captured) {saved=true};h.drain()
            assertEquals(b.id,h.state.selectedServer!!.localProfileId);assertFalse(saved)
            assertEquals(vault,h.credentialBlob.bytes!!.toList())
            assertEquals(1L,h.identities.snapshot().profiles.single {it.localProfileId==a.localProfileId}.credentialRevision)
        }
    }

    @Test fun staleMigrationCallbackCannotCancelNewServerOrStageEitherServer() {
        for(queuedBeforeSwitch in listOf(false,true)) {
            val h=Harness();h.selected();val b=h.profile("Other","other");val captured=h.state.selectionGeneration
            val before=h.disk.drafts.mapValues {it.value.toList()}
            if(queuedBeforeSwitch) h.session.prepareLegacyMigration(captured)
            h.session.selectServerProfile(b.id)
            if(!queuedBeforeSwitch) h.session.prepareLegacyMigration(captured)
            h.drain()
            assertEquals(b.id,h.state.selectedServer!!.localProfileId);assertNull(h.state.migrationStatus)
            assertEquals(before,h.disk.drafts.mapValues {it.value.toList()});assertTrue(h.legacyDisk.drafts.isEmpty())
        }
    }

    @Test fun repeatedExactProjectSelectionAtBindingLimitReusesMetadataButFencesLiveOwners() {
        val h=Harness().online();val original=h.state.selectedProject!!;val p=h.state.selectedServer!!
        repeat(999) {h.identities.addBinding(ProjectBinding.create(p,"other-$it",original.expectedAccountId,original.storageEpoch))}
        assertEquals(1000,h.identities.snapshot().bindings.size)
        val bytes=h.identityBlob.bytes!!.toList();val oldWire=h.wire;val oldEpoch=h.state.documentEpoch
        repeat(5) {h.session.selectProject(original.projectId);h.drain();assertNull(h.state.error)}
        assertEquals(original.bindingId,h.state.selectedProject!!.bindingId)
        assertEquals(1000,h.identities.snapshot().bindings.size);assertEquals(bytes,h.identityBlob.bytes!!.toList())
        assertTrue(oldWire.closed);assertTrue(h.state.documentEpoch>oldEpoch)
        h.session.connect();h.drain()
        oldWire.hello(generation=99);oldWire.change(text="stale owner",rev=999,generation=99);oldWire.synced(seq=999,generation=99)
        h.wire.hello();h.wire.change(text="current owner",rev=2);h.wire.synced(seq=2);h.drain()
        assertEquals("current owner",h.state.documents["note.md"]!!.content)
        assertEquals(original.bindingId,h.state.selectedProject!!.bindingId)
        assertTrue(h.wire.sent.all {it.second is ClientFrame.Sync})
    }

    @Test fun legacyArchiveRetainsEveryOriginalRecoveryReasonWithoutRelabelling() {
        val h=Harness();val credential=h.credentials.create(Credential.fromToken(TOKEN))
        val p=SavedProfile.create("Legacy","https://remote.example","game",credential,"legacy");h.profiles.upsert(p)
        val old=DocumentsRepository(h.legacyDisk);val owner=old.selectProfile(p);val id="e".repeat(32)
        old.onEvent(owner,ConnectionEvent.Frame(1,ServerFrame.Hello(1,RemoteUser("account-a","Alice","#123456"),RemoteSpace(id,"game"),1)))
        old.onEvent(owner,ConnectionEvent.Frame(1,ServerFrame.Change("note.md",1,documentHash("cached"),false,"cached",null)))
        old.onEvent(owner,ConnectionEvent.Frame(1,ServerFrame.Synced(1)))
        val journalKey=DocumentNamespace(p.canonicalServerUrl,"account-a","@journal").storageKey()
        val journal=DocumentStateCodec.journal(h.legacyDisk.drafts[journalKey]!!)
        val recoveries=RecoveryReason.entries.mapIndexed {index,reason ->
            DocumentRecovery(index+100L,id,"reason-$index.md","exact reason $index",index+10L,null,reason,null)
        }
        val archived=journal.copy(nextId=1000,nextGeneration=1000,spaces=journal.spaces+(id to
            (journal.spaces[id] ?: SpaceJournal()).copy(recoveries=recoveries)))
        h.legacyDisk.writeDrafts(journalKey,DocumentStateCodec.journal(archived));val source=h.legacyDisk.drafts[journalKey]!!.toList()
        h.drain();h.session.selectServerProfile(p.id);h.drain();h.session.prepareLegacyArchive();h.drain()
        h.session.loadLegacyArchive(id);h.drain()
        val entries=h.state.legacyArchive.filter {it.kind==com.voxarium.remote.LegacyArchiveKind.RECOVERY}
        assertEquals(recoveries.map {it.reason},entries.map {it.reason})
        assertEquals(recoveries.map {it.content},entries.map {it.markdown})
        assertEquals(recoveries.map {it.generation},entries.map {it.generation})
        assertTrue(h.state.legacyArchive.filter {it.kind!=com.voxarium.remote.LegacyArchiveKind.RECOVERY}.all {it.reason==null})
        assertEquals(source,h.legacyDisk.drafts[journalKey]!!.toList())
        assertTrue(h.wires.isEmpty());assertEquals(0,h.http.verifies)
    }

    @Test fun helloBelowDurableRevisionAboveCursorShowsRollbackWarningAndFullSync() {
        val h=Harness().online()
        h.wire.change(text="durable change ahead of cursor",rev=10);h.drain()
        assertEquals(1L,h.repository.view().cursor)
        h.session.retry();h.drain()
        h.wire.frame(ServerFrame.Hello(2,RemoteUser("account-a","Alice","#123456"),
            RemoteSpace("space-a","Project","a".repeat(32)),8));h.drain()
        assertEquals(com.voxarium.remote.IdentityWarning.SEQUENCE_ROLLBACK,h.state.identityWarning)
        assertEquals(listOf(1L to ClientFrame.Sync(0)),h.wire.sent)
        assertTrue(h.state.documents.isEmpty())
        h.wire.synced(seq=8);h.drain()
        assertEquals(RemoteStatus.ONLINE,h.state.status)
        assertEquals(com.voxarium.remote.IdentityWarning.SEQUENCE_ROLLBACK,h.state.identityWarning)
        assertTrue(h.wire.sent.all {it.second is ClientFrame.Sync})
    }

    private companion object {
        const val TOKEN = "synthetic-session-token-only"
        val cryptoKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }
}

/** Deliberately delivers stale replies too: consumer fencing, not a friendly fake, is tested. */
internal class SessionTestHttp : SessionHttp {
    var account = "account-a"
    var protocol = 2L
    var projects = listOf(ProjectSummary("space-a", "Project", "a".repeat(32), 1, "2026-01-01T00:00:00Z"))
    var deferVerify = false
    var deferList = false
    var deferGet = false
    var deferCreate = true
    var throwCreate = false
    var verifies = 0; var lists = 0; var closed = false
    val posts = mutableListOf<Pair<String, String>>()
    val gets = mutableListOf<String>()
    val restores = mutableListOf<String>()
    val verifyReplies = mutableListOf<() -> Unit>()
    val listReplies = mutableListOf<() -> Unit>()
    val getReplies = mutableListOf<() -> Unit>()
    val createReplies = mutableListOf<(CatalogResult<ProjectSummary>) -> Unit>()
    var getResult: CatalogResult<ProjectSummary>? = null
    private var generation = 0L
    private var request = 0L
    override fun verify(identity: ServerRequestIdentity, bearer: String, callback: (VerificationReply) -> Unit) {
        verifies++
        val result = VerifiedServer(identity, RemoteUser(account, "Alice", "#123456"), protocol,
            if (protocol == 2L) 2 else null, if (protocol == 2L) setOf("project_catalog", "explicit_project_create", "strict_connect") else emptySet())
        val reply = { callback(VerificationReply(++request, identity, VerificationResult.Verified(result))) }
        if (deferVerify) verifyReplies += reply else reply()
    }
    override fun invalidate() { generation++ }
    override fun select(server: VerifiedServer, bearer: String) = CatalogScope(++generation, server)
    override fun list(scope: CatalogScope, callback: (CatalogReply<List<ProjectSummary>>) -> Unit) {
        lists++
        val rows = projects.toList()
        val reply = { callback(CatalogReply(++request, scope, CatalogResult.Success(rows))) }
        if (deferList) listReplies += reply else reply()
    }
    override fun get(scope: CatalogScope, id: String, callback: (CatalogReply<ProjectSummary>) -> Unit) {
        gets += id
        val result = getResult ?: projects.find { it.id == id }?.let { CatalogResult.Success(it) }
            ?: CatalogResult.Failed(ServerProblem.PROJECT_NOT_FOUND)
        val reply = { callback(CatalogReply(++request, scope, result)) }
        if (deferGet) getReplies += reply else reply()
    }
    override fun create(scope: CatalogScope, id: String, name: String, callback: (CatalogReply<ProjectSummary>) -> Unit) {
        if (throwCreate) error("dispatch failed")
        posts += id to name
        val reply: (CatalogResult<ProjectSummary>) -> Unit = { callback(CatalogReply(++request, scope, it)) }
        if (deferCreate) createReplies += reply else reply(CatalogResult.CreateUncertain(id))
    }
    override fun restore(scope: CatalogScope, id: String) { restores += id }
    override fun close() { closed = true }
}
