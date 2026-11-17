package com.voxarium.remote.ui.connection

import android.content.Context
import com.voxarium.remote.RemoteError
import com.voxarium.remote.RemoteStatus
import com.voxarium.remote.RemoteUiState
import com.voxarium.remote.VerificationStatus
import com.voxarium.remote.CatalogStatus
import com.voxarium.remote.IdentityWarning
import com.voxarium.remote.LegacyArchiveKind
import com.voxarium.remote.LegacyArchiveEntry
import com.voxarium.remote.data.documents.*
import com.voxarium.remote.data.room.*
import com.voxarium.remote.data.security.*
import com.voxarium.remote.data.transport.*
import java.io.Closeable
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Injectable native transport boundary; no test needs a server or an Android Keystore. */
internal interface SessionConnection : Closeable {
    fun connect(endpoint: RemoteEndpoint, bearer: String, projectId: String, expectedAccountId: String, storageEpoch: String): Long
    fun send(generation: Long, frame: ClientFrame, result: (SendResult) -> Unit)
}

/** Test seam for native HTTP. Implementations must not invoke callbacks with secrets. */
internal interface SessionHttp : Closeable {
    fun verify(identity: ServerRequestIdentity, bearer: String, callback: (VerificationReply) -> Unit)
    fun invalidate()
    fun select(server: VerifiedServer, bearer: String): CatalogScope
    fun list(scope: CatalogScope, callback: (CatalogReply<List<ProjectSummary>>) -> Unit)
    fun get(scope: CatalogScope, id: String, callback: (CatalogReply<ProjectSummary>) -> Unit)
    fun create(scope: CatalogScope, id: String, name: String, callback: (CatalogReply<ProjectSummary>) -> Unit)
    fun restore(scope: CatalogScope, id: String)
}

private class NativeSessionHttp : SessionHttp {
    private val verifier = ServerVerifier()
    private val catalog = ProjectCatalog()
    override fun verify(identity: ServerRequestIdentity, bearer: String, callback: (VerificationReply) -> Unit) {
        verifier.verify(identity, bearer, callback)
    }
    override fun invalidate() { verifier.invalidate(); catalog.invalidate() }
    override fun select(server: VerifiedServer, bearer: String) = catalog.select(server, bearer)
    override fun list(scope: CatalogScope, callback: (CatalogReply<List<ProjectSummary>>) -> Unit) { catalog.listAllProjects(scope, callback) }
    override fun get(scope: CatalogScope, id: String, callback: (CatalogReply<ProjectSummary>) -> Unit) { catalog.getProject(scope, id, callback) }
    override fun create(scope: CatalogScope, id: String, name: String, callback: (CatalogReply<ProjectSummary>) -> Unit) { catalog.createProject(scope, id, name, callback) }
    override fun restore(scope: CatalogScope, id: String) = catalog.restoreUncertainCreate(scope, id)
    override fun close() { verifier.close(); catalog.close() }
}

internal class SessionDependencies(
    val profiles: ProfileStore,
    val credentials: CredentialStore,
    val documents: DocumentsRepository,
    val recovery: RoomRecoveryStore? = null,
    val writesEnabled: Boolean = false,
    val nowMillis: () -> Long = android.os.SystemClock::elapsedRealtime,
    val serverProfiles: ServerProfileStore,
    val creates: CreateIntentStore,
    val http: SessionHttp,
    val migration: IdentityMigration? = null,
    val legacyStorage: DocumentStorage? = null,
    val wallClockMillis: () -> Long = System::currentTimeMillis,
    val connection: ((ConnectionEvent) -> Unit) -> SessionConnection,
)

/**
 * Native sync + room vertical slice (remote-android/protocol.md §§6,9,13).
 * One ordered worker owns ALL disk access, repository events, intents and sends. A socket's
 * immutable owner is never rebound. Only the coordinator can authorize live room writes.
 */
class RemoteSession internal constructor(
    private val dependencies: () -> SessionDependencies,
    private val worker: ExecutorService,
    private val ui: Executor,
    private val observer: (RemoteUiState) -> Unit,
) : Closeable {
    private val closed = AtomicBoolean()
    // Fence even already-posted UI publications as soon as a new selection is requested.
    private val intent = AtomicLong()
    private val uiRevision = AtomicLong()
    private var activeIntent = 0L
    private var model = RemoteUiState(busy = true)
    private var deps: SessionDependencies? = null
    private var binding: Binding? = null
    private var namespace: DocumentNamespace? = null
    private val visibleNamespace = AtomicReference<DocumentNamespace?>()
    private val visibleProfileId = AtomicReference<String?>()
    private val visibleDocumentEpoch = AtomicLong()
    private val editorActive = AtomicBoolean()
    private var afterEditorClose: (() -> Unit)? = null
    private var closePriority=-1
    private var resumeEditor = false
    private var foreground = true
    private val requestFence = AtomicLong()
    private val selectionGeneration = AtomicLong()
    private val selectionLock = Any()
    private val visibleSelectionGeneration = AtomicLong()
    private var verified: VerifiedServer? = null
    private var catalogScope: CatalogScope? = null
    private var catalogOperation = 0L
    private val inFlightCreates = mutableMapOf<String, String>()
    private var migrationSource: LegacyDocumentSource? = null

    private class Binding(val owner: Long, val intent: Long, val project: ProjectBinding) {
        lateinit var transport: SessionConnection
        lateinit var room: RoomCoordinator
        lateinit var channel: EditorChannel
        var editing = false
        var generation = -1L
        var retiredThrough = -1L
        val poisoned = AtomicBoolean()
        val queued = AtomicInteger()
        val queuedBytes = AtomicLong()
        val editorQueued=AtomicInteger()
        val editorBytes=AtomicLong()
        val editorOverflow=AtomicBoolean()
        val overflowSnapshot=AtomicReference<RoomSnapshot?>()
    }

    init {
        submit {
            try {
                val opened = dependencies()
                deps = opened
                val existing = opened.serverProfiles.snapshot()
                val legacy = if (existing.legacySourceSha256 == null) opened.profiles.list() else emptyList()
                val observations = legacy.map { p ->
                    val key = listOf("profile-binding.v1", p.canonicalServerUrl, p.spaceName, p.id, p.credentialId)
                        .joinToString("") { "${it.length}:$it" }
                    val n = opened.legacyStorage?.readDraftsForMigration(key)?.let(DocumentStateCodec::identity)
                    if (n != null && (n.server != p.canonicalServerUrl || n.storageEpoch != null)) error("Legacy identity")
                    LegacyImportRecord.create(p, n?.accountId, n?.spaceId)
                }
                val metadata = opened.serverProfiles.migrateLegacy(opened.profiles, observations)
                opened.creates.list() // corruption is not an empty/new installation
                model = model.copy(profiles = legacy, serverProfiles = metadata.profiles, knownBindings = metadata.bindings, legacyImports = metadata.legacyImports, busy = false)
                publish()
            } catch (_: Exception) { fail(RemoteError.STORAGE, clearDocuments = true) }
        }
    }

    /** Compatibility entry point: the legacy selector is deliberately NOT persisted or used. */
    @Suppress("UNUSED_PARAMETER")
    fun saveProfile(label: String, server: String, space: String, key: String, onSaved: () -> Unit) =
        saveServerProfile(label, server, key, onSaved)
    fun selectProfile(id: String) = selectServerProfile(id)

    fun saveServerProfile(name: String, server: String, key: String, onSaved: () -> Unit) {
        val requested = requestFence.get()
        submit {
            val d = deps ?: return@submit fail(RemoteError.STORAGE)
            try {
                val url = RemoteEndpoint.create(server).serverUrl
                ServerProfile.create(name.trim(), url, "validation")
                val credential = Credential.fromToken(key)
                val credentialId = d.credentials.create(credential)
                val profile = ServerProfile.create(name.trim(), url, credentialId)
                d.serverProfiles.upsert(profile)
                model = model.copy(serverProfiles = d.serverProfiles.snapshot().profiles, error = null)
                ui.execute { if (!closed.get() && requestFence.get() == requested) onSaved() }
                // Save is local-only. It does not verify, load catalog, select a project or connect.
                publish()
            } catch (e: SecureStoreException) {
                fail(if (e.reason == SecureStoreException.Reason.INVALID_INPUT) RemoteError.INVALID_PROFILE else RemoteError.CREDENTIAL_STORAGE)
            } catch (_: IllegalArgumentException) { fail(RemoteError.INVALID_PROFILE) }
            catch (_: Exception) { fail(RemoteError.STORAGE) }
        }
    }

    private fun advanceSelectionGeneration(): Long = synchronized(selectionLock) { selectionGeneration.incrementAndGet() }

    /** Selection/key changes immediately fence queued HTTP replies, but old room events must
     * remain eligible until Flush + ACK/recovery + Dispose has finished (§6). */
    private fun retireThen(clearVerification: Boolean = true, expectedGeneration: Long? = null, action: () -> Unit) {
        val (targetGeneration, requested) = synchronized(selectionLock) {
            if (expectedGeneration != null && expectedGeneration != selectionGeneration.get()) return
            advanceSelectionGeneration() to requestFence.incrementAndGet()
        }
        submit {
            if (requested != requestFence.get() || targetGeneration != selectionGeneration.get()) return@submit
            deps?.http?.invalidate()
            inFlightCreates.clear()
            catalogScope = null
            closeEditorThen(priority = 2) {
                if (requested == requestFence.get() && targetGeneration == selectionGeneration.get()) {
                    activeIntent = intent.incrementAndGet()
                    stopBinding()
                    if (clearVerification) {
                        verified = null
                        model = model.copy(verificationStatus = VerificationStatus.UNVERIFIED, verifiedAccount = null,
                            projects = emptyList(), catalogStatus = CatalogStatus.IDLE, createIntents = emptyList(),
                            migrationStatus = null, migrationRequired = false, serverProblem = null)
                        migrationSource = null
                    }
                    action()
                    publish() // even an ineligible/no-op intent must publish its new UI fence
                }
            }
        }
    }

    fun selectServerProfile(id: String) = retireThen {
        try {
            val snapshot = deps!!.serverProfiles.snapshot()
            clearSelection()
            model = model.copy(selectedServer = null, selectedProfile = null, knownBindings = snapshot.bindings)
            val profile = snapshot.profiles.find { it.localProfileId == id }
                ?: return@retireThen fail(RemoteError.INVALID_PROFILE, clearDocuments = true)
            model = model.copy(serverProfiles = snapshot.profiles, selectedServer = profile,
                selectedProfile = model.profiles.find { it.id == id })
            publish()
        } catch (_: Exception) { fail(RemoteError.STORAGE, clearDocuments = true) }
    }

    fun replaceServerKey(profileId: String, key: String, expectedGeneration: Long = visibleSelectionGeneration.get(), onSaved: () -> Unit) {
        if (profileId != visibleProfileId.get()) return
        retireThen(expectedGeneration = expectedGeneration) {
            try {
                val d = deps!!
                val selected = model.selectedServer?.takeIf { it.localProfileId == profileId } ?: return@retireThen
                val old = d.serverProfiles.snapshot().profiles.singleOrNull { it.localProfileId == profileId }
                    ?: return@retireThen fail(RemoteError.INVALID_PROFILE)
                if (old.canonicalUrl != selected.canonicalUrl || old.credentialId != selected.credentialId ||
                    old.credentialRevision != selected.credentialRevision) return@retireThen
                val token = Credential.fromToken(key)
                // Allocate a new vault reference: shared credentials of other profiles stay untouched.
                val reference = d.credentials.create(token)
                val next = ServerProfile.create(old.name, old.canonicalUrl, reference, old.localProfileId,
                    Math.addExact(old.credentialRevision, 1))
                d.serverProfiles.upsert(next)
                clearSelection()
                model = model.copy(serverProfiles = d.serverProfiles.snapshot().profiles, selectedServer = next)
                val expected = requestFence.get()
                ui.execute { if (!closed.get() && expected == requestFence.get()) onSaved() }
                publish()
            } catch (_: SecureStoreException) { fail(RemoteError.CREDENTIAL_STORAGE) }
            catch (_: Exception) { fail(RemoteError.STORAGE) }
        }
    }


    private fun clearSelection() {
        namespace = null
        model = model.copy(selectedProject = null, selectedProjectSummary = null, legacyArchive = emptyList(), identityWarning = null, documents = emptyMap(), selectedPath = null,
            editor = null, roomStatus = null, recovery = emptyList(), documentRecovery = emptyList(), localDrafts = emptyList(),
            cacheAvailable = false, hasCaseCollisions = false, status = RemoteStatus.DISCONNECTED, error = null, busy = false,
            documentEpoch = Math.addExact(model.documentEpoch, 1L))
    }

    fun verifyServer(expectedGeneration: Long = visibleSelectionGeneration.get()) = retireThen(expectedGeneration = expectedGeneration) {
        clearSelection()
        val d = deps ?: return@retireThen fail(RemoteError.STORAGE)
        val p = model.selectedServer ?: return@retireThen fail(RemoteError.INVALID_PROFILE)
        val fence = requestFence.get()
        val identity = ServerRequestIdentity(p.localProfileId, RemoteEndpoint.create(p.canonicalUrl),
            p.credentialId, p.credentialRevision, fence)
        try {
            val credential = d.credentials.get(p.credentialId) ?: return@retireThen fail(RemoteError.MISSING_CREDENTIAL)
            model = model.copy(verificationStatus = VerificationStatus.VERIFYING, busy = true, serverProblem = null, error = null); publish()
            credential.useToken { token -> d.http.verify(identity, token) { reply -> submit {
                try {
                    if (fence != requestFence.get() || reply.identity != identity || !matchesCurrent(identity)) return@submit
                    when (val result = reply.result) {
                        is VerificationResult.Failed -> {
                            model = model.copy(verificationStatus = VerificationStatus.FAILED, busy = false, serverProblem = result.problem)
                        }
                        is VerificationResult.Verified -> {
                            if (result.server.identity != identity) return@submit
                            verified = result.server
                            val observation = ServerVerification.create(p.canonicalUrl, p.credentialId, p.credentialRevision,
                                reply.generation.coerceAtLeast(1), result.server.user.id, result.server.protocol.toInt(),
                                d.wallClockMillis(), result.server.user.name, result.server.user.color,
                                if (result.server.catalogSupported) 2 else null)
                            val updated = ServerProfile.create(p.name, p.canonicalUrl, p.credentialId, p.localProfileId,
                                p.credentialRevision, observation)
                            d.serverProfiles.upsert(updated)
                            model = model.copy(serverProfiles = d.serverProfiles.snapshot().profiles, selectedServer = updated,
                                verifiedAccount = result.server.user, busy = false,
                                verificationStatus = if (result.server.catalogSupported) VerificationStatus.VERIFIED else VerificationStatus.UNSUPPORTED)
                            if (result.server.catalogSupported) establishCatalog()
                        }
                    }
                    publish()
                } catch (_: Exception) { fail(RemoteError.STORAGE) }
            } } }
        } catch (_: SecureStoreException) { fail(RemoteError.CREDENTIAL_STORAGE) }
        catch (_: Exception) { fail(RemoteError.STORAGE) }
    }

    private fun matchesCurrent(identity: ServerRequestIdentity, accountId: String? = null): Boolean {
        val p = model.selectedServer ?: return false
        val stored = deps?.serverProfiles?.snapshot()?.profiles?.find { it.localProfileId == p.localProfileId } ?: return false
        return stored.canonicalUrl == identity.endpoint.serverUrl && stored.localProfileId == identity.profileId &&
            stored.credentialId == identity.credentialId && stored.credentialRevision == identity.credentialRevision &&
            (accountId == null || stored.verification?.accountId == accountId)
    }
    private fun establishCatalog(): CatalogScope? {
        val d = deps ?: return null
        val server = verified?.takeIf { it.catalogSupported && matchesCurrent(it.identity, it.user.id) } ?: return null
        val credential = d.credentials.get(server.identity.credentialId) ?: return null
        val scope = credential.useToken { d.http.select(server, it) }
        catalogScope = scope
        reloadCreates()
        model.createIntents.filter { it.outcome == CreateOutcome.UNKNOWN }.forEach { d.http.restore(scope, it.id) }
        return scope
    }
    private fun scope(): CatalogScope? = catalogScope ?: establishCatalog()
    private fun current(scope: CatalogScope, fence: Long) = fence == requestFence.get() && catalogScope === scope &&
        verified === scope.server && matchesCurrent(scope.server.identity, scope.server.user.id)
    private fun reloadCreates() {
        val server = verified
        model = model.copy(createIntents = if (server == null) emptyList() else deps!!.creates.list().filter {
            it.canonicalUrl == server.identity.endpoint.serverUrl && it.accountId == server.user.id
        })
    }

    fun refreshProjects(expectedGeneration: Long = visibleSelectionGeneration.get()) = submit {
        if (expectedGeneration != selectionGeneration.get()) return@submit
        try {
            val scope = scope() ?: return@submit
            val fence = requestFence.get(); val operation = ++catalogOperation
            model = model.copy(catalogStatus = CatalogStatus.LOADING, serverProblem = null); publish()
            deps!!.http.list(scope) { reply -> submit {
                try {
                    if (!current(scope, fence) || reply.scope !== scope || operation != catalogOperation) return@submit
                    model = when (val result = reply.result) {
                        is CatalogResult.Success -> { advanceSelectionGeneration(); model.copy(projects = result.value, catalogStatus = CatalogStatus.READY) }
                        is CatalogResult.Failed -> model.copy(catalogStatus = CatalogStatus.FAILED, serverProblem = result.problem)
                        is CatalogResult.CreateUncertain -> model.copy(catalogStatus = CatalogStatus.FAILED, serverProblem = ServerProblem.INVALID_RESPONSE)
                    }
                    publish()
                } catch (_: Exception) { fail(RemoteError.STORAGE) }
            } }
        } catch (_: Exception) { fail(RemoteError.STORAGE) }
    }
    fun lookupProject(id: String, expectedGeneration: Long = visibleSelectionGeneration.get()) = readProject(id, resolve = false, expectedGeneration)
    fun resolveCreate(id: String, expectedGeneration: Long = visibleSelectionGeneration.get()) = readProject(id, resolve = true, expectedGeneration)
    private fun readProject(id: String, resolve: Boolean, expectedGeneration: Long) = submit {
        if (expectedGeneration != selectionGeneration.get()) return@submit
        try {
            val scope = scope() ?: return@submit
            val fence = requestFence.get(); val operation = ++catalogOperation
            val record = model.createIntents.find { it.id == id }
            if (resolve && (record == null || record.attemptId in inFlightCreates)) return@submit
            model = model.copy(catalogStatus = CatalogStatus.LOADING, serverProblem = null); publish()
            deps!!.http.get(scope, id) { reply -> submit {
                try {
                    if (!current(scope, fence) || reply.scope !== scope || operation != catalogOperation) return@submit
                    when (val result = reply.result) {
                        is CatalogResult.Success -> {
                            if (result.value.id != id) { model = model.copy(serverProblem = ServerProblem.INVALID_RESPONSE); return@submit publish() }
                            if (resolve) deps!!.creates.resolve(record!!, CreateOutcome.CREATED, result.value)
                            advanceSelectionGeneration()
                            model = model.copy(projects = model.projects.filterNot { it.id == id } + result.value, catalogStatus = CatalogStatus.READY)
                        }
                        is CatalogResult.Failed -> {
                            if (resolve && result.problem == ServerProblem.PROJECT_NOT_FOUND) deps!!.creates.resolve(record!!, CreateOutcome.NOT_CREATED)
                            model = model.copy(catalogStatus = CatalogStatus.FAILED, serverProblem = result.problem)
                        }
                        is CatalogResult.CreateUncertain -> Unit
                    }
                    reloadCreates(); publish() // Information only; never Select or POST.
                } catch (_: Exception) { fail(RemoteError.STORAGE) }
            } }
        } catch (_: Exception) { fail(RemoteError.STORAGE) }
    }

    /** Caller presents a separate confirmation; durable UNKNOWN precedes the only POST. */
    fun createProject(id: String, displayName: String, expectedGeneration: Long = visibleSelectionGeneration.get()) = submit {
        if (expectedGeneration != selectionGeneration.get()) return@submit
        var dispatchedAttempt: String? = null
        try {
            val scope = scope() ?: return@submit
            val fence = requestFence.get()
            ProjectCatalogCodec.encodeCreate(id, displayName) // validate before persisting intent
            val name = ProjectCatalogCodec.normalizeDisplayName(displayName)
            val d = deps!!
            val record = d.creates.prepare(model.selectedServer!!, scope.server.user.id, id, name)
            dispatchedAttempt = record.attemptId
            inFlightCreates[record.attemptId] = id
            reloadCreates(); model = model.copy(serverProblem = null); publish()
            d.http.create(scope, id, name) { reply -> submit {
                try {
                    if (!current(scope, fence) || reply.scope !== scope) return@submit
                    inFlightCreates.remove(record.attemptId)
                    when (val result = reply.result) {
                        is CatalogResult.Success -> {
                            if (result.value.id != id) { model = model.copy(serverProblem = ServerProblem.INVALID_RESPONSE); publish(); return@submit }
                            d.creates.resolve(record, CreateOutcome.CREATED, result.value)
                            advanceSelectionGeneration()
                            model = model.copy(projects = model.projects.filterNot { it.id == id } + result.value)
                        }
                        is CatalogResult.Failed -> {
                            if (result.problem in setOf(ServerProblem.PROJECT_EXISTS, ServerProblem.UNAUTHORIZED, ServerProblem.INVALID_INPUT))
                                d.creates.resolve(record, if (result.problem == ServerProblem.PROJECT_EXISTS) CreateOutcome.CONFLICT else CreateOutcome.NOT_CREATED)
                            model = model.copy(serverProblem = result.problem)
                        }
                        is CatalogResult.CreateUncertain -> model = model.copy(serverProblem = ServerProblem.CREATE_UNCERTAIN)
                    }
                    reloadCreates(); publish()
                } catch (_: Exception) { fail(RemoteError.STORAGE) }
            } }
        } catch (e: SecureStoreException) {
            dispatchedAttempt?.let(inFlightCreates::remove)
            if (e.reason == SecureStoreException.Reason.INVALID_INPUT) { model = model.copy(serverProblem = ServerProblem.CREATE_UNCERTAIN); publish() }
            else fail(RemoteError.STORAGE)
        } catch (_: IllegalArgumentException) { dispatchedAttempt?.let(inFlightCreates::remove); model = model.copy(serverProblem = ServerProblem.INVALID_INPUT); publish() }
        catch (_: Exception) { dispatchedAttempt?.let(inFlightCreates::remove); fail(RemoteError.STORAGE) }
    }

    fun selectProject(id: String, expectedGeneration: Long = visibleSelectionGeneration.get()) = retireThen(clearVerification = false, expectedGeneration = expectedGeneration) {
        try {
            val server = verified?.takeIf { it.catalogSupported && matchesCurrent(it.identity, it.user.id) } ?: return@retireThen
            val project = model.projects.singleOrNull { it.id == id } ?: return@retireThen
            val p = model.selectedServer ?: return@retireThen
            // Legacy data must be inventoried/staged before even a fresh v2 reader can occupy
            // its destination. The separate Activate action is still required for candidates.
            if (model.legacyImports.any { it.canonicalUrl == p.canonicalUrl &&
                    (it.lastAuthenticatedAccountId == null || it.lastAuthenticatedAccountId == server.user.id) } &&
                (migrationSource?.canonicalServerUrl != p.canonicalUrl || migrationSource?.accountId != server.user.id)) {
                model = model.copy(migrationRequired = true); publish(); return@retireThen
            }
            val candidate = ProjectBinding.create(p, project.id, server.user.id, project.storageEpoch)
            clearSelection()
            deps!!.documents.selectPartition(partition(candidate))
            // Durable identity is reusable; repository selection/live owner is fresh every time.
            val target = deps!!.serverProfiles.findOrAddBinding(candidate)
            model = model.copy(selectedProject = target, selectedProjectSummary = project, knownBindings = deps!!.serverProfiles.snapshot().bindings)
            refresh()
            model = model.copy(status = if (model.cacheAvailable) RemoteStatus.OFFLINE else RemoteStatus.DISCONNECTED)
            establishCatalog(); publish()
        } catch (_: Exception) { fail(RemoteError.STORAGE, clearDocuments = true) }
    }
    /** Explicit previously authenticated binding selection, read-only until a new Verify.
     * A rotated credential revision cannot reveal a previous account's private partition. */
    fun selectKnownProject(bindingId: String, expectedGeneration: Long = visibleSelectionGeneration.get()) =
        retireThen(clearVerification = false, expectedGeneration = expectedGeneration) {
            try {
                val selected = model.selectedServer ?: return@retireThen
                val metadata = deps!!.serverProfiles.snapshot()
                val p = metadata.profiles.singleOrNull { it.localProfileId == selected.localProfileId } ?: return@retireThen
                val target = metadata.bindings.singleOrNull { it.bindingId == bindingId } ?: return@retireThen
                if (p.canonicalUrl != selected.canonicalUrl || p.credentialId != selected.credentialId ||
                    p.credentialRevision != selected.credentialRevision) return@retireThen
                model = model.copy(selectedServer = p, serverProfiles = metadata.profiles, knownBindings = metadata.bindings)
                // Persisted HTTP identity is not a live grant, but it MUST fence a known account
                // mismatch even after reselect/restart. Never fall back to an older binding's user.
                if (target.serverProfileId != p.localProfileId || target.canonicalUrl != p.canonicalUrl ||
                    target.credentialId != p.credentialId || target.credentialRevision != p.credentialRevision ||
                    p.verification?.accountId != target.expectedAccountId) return@retireThen
                val live = verified
                if (live != null && (!matchesCurrent(live.identity, live.user.id) || live.user.id != target.expectedAccountId)) return@retireThen
                clearSelection()
                deps!!.documents.selectPartition(partition(target))
                model = model.copy(selectedProject = target)
                refresh(); model = model.copy(status = RemoteStatus.OFFLINE); publish()
            } catch (_: Exception) { fail(RemoteError.STORAGE, clearDocuments = true) }
        }

    private fun partition(b: ProjectBinding) = DocumentPartition(b.canonicalUrl, b.projectId, b.expectedAccountId, b.storageEpoch)

    /** Offline archive access relies only on the exact persisted v1 authentication association.
     * No credential read, HTTP, socket, activation or mutable v1 repository is involved. */
    fun prepareLegacyArchive(expectedGeneration: Long = visibleSelectionGeneration.get()) =
        retireThen(clearVerification = false, expectedGeneration = expectedGeneration) {
            try {
                val d = deps!!
                val p = model.selectedServer ?: return@retireThen
                val record = d.serverProfiles.snapshot().legacyImports.singleOrNull {
                    it.localProfileId == p.localProfileId && it.canonicalUrl == p.canonicalUrl &&
                        it.credentialId == p.credentialId && p.credentialRevision == 1L
                } ?: return@retireThen fail(RemoteError.INVALID_PROFILE)
                val associationKey = listOf("profile-binding.v1", record.canonicalUrl, record.spaceName,
                    record.localProfileId, record.credentialId).joinToString("") { "${it.length}:$it" }
                val association = if (record.lastAuthenticatedAccountId == null)
                    d.legacyStorage?.readDraftsForMigration(associationKey)?.let(DocumentStateCodec::identity) else null
                if (association != null && (association.server != record.canonicalUrl || association.storageEpoch != null))
                    return@retireThen fail(RemoteError.INVALID_DATA)
                val account = record.lastAuthenticatedAccountId ?: association?.accountId
                    ?: return@retireThen fail(RemoteError.INVALID_PROFILE)
                val records = d.serverProfiles.snapshot().legacyImports.filter {
                    it.canonicalUrl == record.canonicalUrl && (it.lastAuthenticatedAccountId == null || it.lastAuthenticatedAccountId == account)
                }
                val source = LegacyDocumentSource(record.canonicalUrl, account,
                    records.map { LegacyDocumentProfile(it.localProfileId, it.credentialId, it.spaceName) })
                val status = d.migration?.resume(source) ?: return@retireThen fail(RemoteError.STORAGE)
                clearSelection(); migrationSource = source
                model = model.copy(migrationStatus = status, migrationRequired = false); publish()
            } catch (_: Exception) { fail(RemoteError.STORAGE) }
        }

    fun loadLegacyArchive(projectId: String, expectedGeneration: Long = visibleSelectionGeneration.get()) =
        retireThen(clearVerification = false, expectedGeneration = expectedGeneration) {
            try {
                val source = migrationSource ?: return@retireThen
                val candidate = model.migrationStatus?.candidates?.singleOrNull { it.namespace.spaceId == projectId }
                    ?: return@retireThen
                val migration = deps!!.migration ?: return@retireThen
                val original = candidate.namespace
                val entries = mutableListOf<LegacyArchiveEntry>()
                val journal = migration.archivedAccountJournal(source)?.let(DocumentStateCodec::journal)
                journal?.spaces?.get(projectId)?.let { archive ->
                    archive.drafts.values.forEach { entries += LegacyArchiveEntry("draft:${it.generation}:${it.path}", original,
                        it.path, it.content, it.generation, LegacyArchiveKind.DRAFT) }
                    archive.pending.values.forEach { entries += LegacyArchiveEntry("pending:${it.id}", original,
                        it.path, it.content, it.generation, LegacyArchiveKind.PENDING) }
                    archive.recoveries.forEach { entries += LegacyArchiveEntry("recovery:${it.id}", original,
                        it.path, it.content, it.generation, LegacyArchiveKind.RECOVERY, reason = it.reason) }
                }
                migration.archivedMirror(source, projectId)?.let(DocumentStateCodec::mirror)?.files?.forEach { (path, version) ->
                    if (!version.deleted) entries += LegacyArchiveEntry("mirror:$path", original, path,
                        version.content, null, LegacyArchiveKind.MIRROR)
                }
                migration.archivedRoom(source, projectId)?.let { archived ->
                    // Reuse the full bounded/provenance-checking room codec through a strictly
                    // read-only archive adapter. Raw bytes are diagnostics, never replayed.
                    val reader = object : DocumentStorage {
                        override fun readCache(namespace: String): ByteArray? = null
                        override fun readDrafts(namespace: String): ByteArray? = archived.clone()
                        override fun writeCache(namespace: String, bytes: ByteArray) { error("Read-only archive") }
                        override fun writeDrafts(namespace: String, bytes: ByteArray) { error("Read-only archive") }
                    }
                    RoomRecoveryStore(reader).list(original).forEach { entry ->
                        entries += LegacyArchiveEntry("room:${entry.id}", original, entry.path, entry.snapshot?.markdown,
                            entry.snapshot?.generation ?: entry.context.documentGeneration, LegacyArchiveKind.ROOM, entry.raw.size)
                    }
                }
                clearSelection()
                model = model.copy(legacyArchive = entries.toList()); publish()
            } catch (_: Exception) { fail(RemoteError.STORAGE) }
        }

    fun prepareLegacyMigration(expectedGeneration: Long = visibleSelectionGeneration.get()) =
        retireThen(clearVerification = false, expectedGeneration = expectedGeneration) {
        try {
            val d = deps!!
            val server = verified?.takeIf { it.catalogSupported && matchesCurrent(it.identity, it.user.id) } ?: return@retireThen
            val records = d.serverProfiles.snapshot().legacyImports.filter { it.canonicalUrl == server.identity.endpoint.serverUrl &&
                (it.lastAuthenticatedAccountId == null || it.lastAuthenticatedAccountId == server.user.id) }
            val source = LegacyDocumentSource(server.identity.endpoint.serverUrl, server.user.id,
                records.map { LegacyDocumentProfile(it.localProfileId, it.credentialId, it.spaceName) })
            clearSelection()
            // No legacy repository/room is ever created by this process. stopBinding above also
            // quiesces any v2 writer before resumable migration touches destination storage.
            val status = d.migration?.resume(source) ?: return@retireThen fail(RemoteError.STORAGE)
            migrationSource = source
            model = model.copy(migrationStatus = status, migrationRequired = false)
            establishCatalog(); publish()
        } catch (_: Exception) { fail(RemoteError.STORAGE, clearDocuments = true) }
    }
    fun activateLegacyProject(id: String, expectedGeneration: Long = visibleSelectionGeneration.get()) = retireThen(clearVerification = false, expectedGeneration = expectedGeneration) {
        try {
            val server = verified?.takeIf { it.catalogSupported && matchesCurrent(it.identity, it.user.id) } ?: return@retireThen
            val source = migrationSource ?: return@retireThen
            val status = model.migrationStatus ?: return@retireThen
            val project = model.projects.singleOrNull { it.id == id } ?: return@retireThen
            if (source.accountId != server.user.id || source.canonicalServerUrl != server.identity.endpoint.serverUrl) return@retireThen
            val target = DocumentPartition(source.canonicalServerUrl, project.id, source.accountId, project.storageEpoch)
            val activated = deps!!.migration!!.activate(source, status.sourceFingerprint, target)
            model = model.copy(migrationStatus = activated)
            establishCatalog(); publish() // Activation is not Select or Connect.
        } catch (_: Exception) { fail(RemoteError.STORAGE) }
    }

    fun connect(expectedProfileId: String? = visibleProfileId.get(), expectedGeneration: Long = visibleSelectionGeneration.get()) {
        if (expectedGeneration != selectionGeneration.get()) return
        if (editorActive.get()) { submit { closeEditorThen { connect(expectedProfileId, expectedGeneration) } }; return }
        val requested = intent.get()
        submit {
            if (requested != intent.get() || expectedGeneration != selectionGeneration.get()) return@submit
            val d = deps ?: return@submit fail(RemoteError.STORAGE)
            val profile = model.selectedServer ?: return@submit fail(RemoteError.INVALID_PROFILE)
            if (profile.localProfileId != expectedProfileId) return@submit
            val selected = model.selectedProject ?: return@submit fail(RemoteError.INVALID_PROFILE)
            val server = verified ?: return@submit fail(RemoteError.INVALID_PROFILE)
            try {
                if (!server.catalogSupported || !matchesCurrent(server.identity, server.user.id) || selected.canonicalUrl != profile.canonicalUrl ||
                    selected.credentialId != profile.credentialId || selected.credentialRevision != profile.credentialRevision ||
                    selected.expectedAccountId != server.user.id) return@submit fail(RemoteError.INVALID_PROFILE)
                stopBinding()
                model = model.copy(status = RemoteStatus.CONNECTING, error = null, busy = true); publish()
                val credential = d.credentials.get(selected.credentialId) ?: return@submit fail(RemoteError.MISSING_CREDENTIAL)
                val owner = d.documents.selectPartition(partition(selected))
                refresh()
                val next = Binding(owner, requested, selected)
                next.channel = EditorChannel(ui, { event -> enqueueEditor(next, event) },
                    { context, unsupported -> submit { editorLost(next, context, unsupported) } })
                next.room = RoomCoordinator(d.documents, owner,
                    d.recovery ?: RoomRecoverySink { _, _, _, _, _ -> error("Recovery unavailable") },
                    writesEnabled = d.writesEnabled, nowMillis = d.nowMillis)
                next.transport = d.connection { event -> enqueue(next, event) }
                binding = next
                model = model.copy(connectionActive = true)
                credential.useToken { next.transport.connect(RemoteEndpoint.create(selected.canonicalUrl), it,
                    selected.projectId, selected.expectedAccountId, selected.storageEpoch) }
                publish()
            } catch (_: SecureStoreException) { fail(RemoteError.CREDENTIAL_STORAGE) }
            catch (_: Exception) { fail(RemoteError.STORAGE) }
        }
    }

    fun retry() = connect() // Explicit new binding/handshake. Never replay a write.

    fun disconnect() = retireThen(clearVerification = false) {
        model = model.copy(status = if (model.cacheAvailable) RemoteStatus.OFFLINE else RemoteStatus.DISCONNECTED,
            busy = false, error = null)
        publish()
    }

    fun openDocument(path: String, expectedEpoch: Long = visibleDocumentEpoch.get()) {
        val requested = intent.get()
        val displayedNamespace = visibleNamespace.get()
        submit {
            if (requested != intent.get()) return@submit
            if (expectedEpoch == model.documentEpoch && displayedNamespace == namespace && model.documents[path]?.deleted == false) {
                model = model.copy(selectedPath = path); publish()
            }
        }
    }

    fun closeDocument() {
        if (editorActive.get()) {
            submit { resumeEditor = false; closeEditorThen { model=model.copy(selectedPath=null);publish() } }
            return
        }
        val requested = intent.get()
        submit {
            if (requested == intent.get()) { model = model.copy(selectedPath = null); publish() }
        }
    }

    /** Explicit online room route; offline cache remains a safe native reader. */
    fun openEditor(path: String, expectedEpoch: Long = visibleDocumentEpoch.get()) {
        val requested=intent.get()
        val displayedNamespace=visibleNamespace.get()
        submit {
            if(requested != intent.get() || expectedEpoch != model.documentEpoch || displayedNamespace != namespace) return@submit
            val source=binding ?: return@submit
            if(source.editing || model.status != RemoteStatus.ONLINE || model.documents[path]?.deleted != false) return@submit
            if(deps?.recovery == null) return@submit // no durable sink, no editing capability
            source.channel.retire()
            source.channel=EditorChannel(ui,
                { event -> enqueueEditor(source,event) },
                { context,unsupported -> submit { editorLost(source,context,unsupported) } })
            source.overflowSnapshot.set(null)
            source.editing=true;editorActive.set(true);resumeEditor=false
            model=model.copy(selectedPath=path,editor=source.channel,roomStatus=RoomStatus.JOINING,error=null)
            publish()
            try { roomEffects(source,source.room.open(path)) } catch (_: Exception) { editorFault() }
        }
    }

    /** Lifecycle Stop includes rotation. Keep the retained renderer alive until its flush reply. */
    fun backgroundEditor() = submit {
        foreground=false
        if(binding?.editing == true) { resumeEditor=true;closeEditorThen(priority=0) { resumeIfNeeded() } }
    }
    fun foregroundEditor() = submit { foreground=true;resumeIfNeeded() }
    private fun resumeIfNeeded() {
        if(!foreground || !resumeEditor || binding?.editing == true) return
        resumeEditor=false
        val path=model.selectedPath ?: return
        openEditor(path,model.documentEpoch)
    }
    fun tickEditor() = submit {
        val source=binding ?: return@submit
        if(source.editing) try { roomEffects(source,source.room.tick()) }
        catch (_: Exception) { editorFault() }
    }
    fun loadRecovery() = submit {
        try {
            val n=namespace
            model=model.copy(recovery=if(n == null) emptyList() else deps?.recovery?.list(n).orEmpty())
            publish()
        } catch (_: Exception) { editorFault() }
    }
    private fun editorFault() {
        binding?.channel?.halt() // retain model, but stop user input after a failed disk/actor transition
        model=model.copy(error=RemoteError.STORAGE);publish()
    }
    private fun closeEditorThen(priority: Int = 1,action: () -> Unit) {
        val source=binding
        if(source?.editing != true) { action();return }
        // A background flush must not swallow Back, a new profile intent or final shutdown.
        if(afterEditorClose != null) {
            if(priority >= closePriority) { afterEditorClose=action;closePriority=priority }
            return
        }
        afterEditorClose=action;closePriority=priority
        try { roomEffects(source,source.room.close()) }
        catch (_: Exception) { editorFault() }
    }
    private fun editorEvent(source: Binding,event: EditorEvent) {
        if(binding !== source || source.intent != intent.get() || !source.editing) return
        try { roomEffects(source,source.room.onEditorEvent(event));if(binding === source) { refresh();publish() } }
        catch (_: Exception) { editorFault() }
    }
    private fun enqueueEditor(source: Binding,event: EditorEvent) {
        if(closed.get() || binding !== source) return
        val snapshot=when(event) {
            is EditorEvent.Changed -> event.snapshot
            is EditorEvent.Flushed -> event.snapshot
            is EditorEvent.Ready -> event.snapshot
            is EditorEvent.Applied -> event.snapshot
            is EditorEvent.Blocked -> event.snapshot
            else -> null
        }
        if(snapshot != null) source.overflowSnapshot.set(snapshot)
        // Final barrier/control replies have a reserved path. Their number is bounded by
        // outstanding typed requests in RoomEditorCodec, not arbitrary JS message names.
        val control=event is EditorEvent.Flushed || event is EditorEvent.Disposed || event is EditorEvent.Blocked
        val bytes=256L+when(event) {
            is EditorEvent.Prepared -> event.seed.length.toLong()*2
            is EditorEvent.LocalUpdate -> event.update.length.toLong()*2
            is EditorEvent.Awareness -> event.update.length.toLong()*2
            is EditorEvent.Blocked -> event.raw.sumOf { it.length.toLong()*2 }+(snapshot?.markdown?.length ?: 0)*2L
            else -> (snapshot?.markdown?.length ?: 0)*2L
        }
        val count=source.editorQueued.incrementAndGet()
        val total=source.editorBytes.addAndGet(bytes)
        if(!control && (source.editorOverflow.get() || count > 128 || total > 32L*1024*1024)) {
            source.editorQueued.decrementAndGet();source.editorBytes.addAndGet(-bytes)
            if(source.editorOverflow.compareAndSet(false,true)) {
                submit {
                    if(binding !== source || !source.editing) return@submit
                    try {
                        model=model.copy(error=RemoteError.EDITOR_PORT)
                        roomEffects(source,source.room.editorOverloaded(event.context))
                    } catch (_: Exception) { editorFault() }
                }
            }
            return // final Flush carries the complete merged model, not a truncated update set
        }
        submit {
            source.editorQueued.decrementAndGet();source.editorBytes.addAndGet(-bytes)
            if(source.editorOverflow.get() && !control) return@submit
            editorEvent(source,event)
        }
    }
    private fun editorLost(source: Binding,context: EditorContext?,unsupported: Boolean) {
        if(binding !== source || !source.editing) return
        try { roomEffects(source,source.room.editorLost(context,source.overflowSnapshot.get()));if(binding === source) { model=model.copy(error=if(unsupported) RemoteError.EDITOR_UNSUPPORTED else RemoteError.EDITOR_PORT);refresh();publish() } }
        catch (_: Exception) { editorFault() }
    }
    private fun roomEffects(source: Binding,effects: List<RoomEffect>) {
        for(effect in effects) when(effect) {
            is RoomEffect.Editor -> {
                if(effect.command is EditorCommand.Prepare) source.overflowSnapshot.set(null)
                if(effect.command is EditorCommand.Flush) model=model.copy(roomStatus=RoomStatus.CLOSING)
                source.channel.submit(effect.command)
            }
            is RoomEffect.Network -> if(binding === source && (!source.editorOverflow.get() || effect.frame is ClientFrame.Leave) && source.room.isCurrent(effect)) {
                source.transport.send(effect.generation,effect.frame) { result -> submit {
                    if(binding !== source || source.intent != intent.get()) return@submit
                    try { roomEffects(source,source.room.onSendResult(effect,result));if(binding === source) { refresh();publish() } }
                    catch (_: Exception) { editorFault() }
                } }
            }
            is RoomEffect.Status -> {
                model=model.copy(roomStatus=effect.value)
                if(effect.value == RoomStatus.RECONNECT_REQUIRED) {
                    model=model.copy(error=RemoteError.NETWORK)
                    closeEditorThen(priority=2) { fail(RemoteError.NETWORK) }
                }
                if(!source.room.hasActiveEditor && !source.room.hasOpenIntent && effect.value in setOf(RoomStatus.CLOSED_CONFIRMED,RoomStatus.CLOSED_RECOVERY_UNCONFIRMED,
                        RoomStatus.RECOVERY_UNCONFIRMED,RoomStatus.SCHEMA_BLOCKED,RoomStatus.MISSING,RoomStatus.STALE)) {
                    source.editing=false;editorActive.set(false)
                    source.channel.retire()
                    // A binding's bridge lifetime MUST NOT be reused by the next coordinator.
                    // Here the coordinator is retained, so context/request counters remain monotonic.
                    model=model.copy(editor=null)
                    if(source.editorOverflow.get()) {
                        stopBinding()
                        model=model.copy(status=RemoteStatus.OFFLINE,busy=false,error=RemoteError.EDITOR_PORT)
                    }
                    val continuation=afterEditorClose;afterEditorClose=null;closePriority=-1
                    continuation?.invoke()
                    if(binding !== source) return // continuation may have selected a different account/partition
                }
            }
        }
        publish()
    }

    private fun enqueue(source: Binding, incoming: ConnectionEvent) {
        if (closed.get() || source.intent != intent.get() || source.poisoned.get()) return
        // Error correlation/message strings are untrusted and not needed in this read-only UI.
        val event = if (incoming is ConnectionEvent.Frame && incoming.frame is ServerFrame.Error)
            ConnectionEvent.Frame(incoming.generation, incoming.frame.copy(message = "")) else incoming
        // Room frames are bounded together with sync traffic; presence is not used by this UI.
        if(event is ConnectionEvent.Frame && event.frame is ServerFrame.Presence) return
        val bytes = eventBytes(event)
        val queued = source.queued.incrementAndGet()
        val queuedBytes = source.queuedBytes.addAndGet(bytes)
        if (queued > 512 || queuedBytes > 32L * 1024 * 1024) {
            source.queued.decrementAndGet()
            source.queuedBytes.addAndGet(-bytes)
            if (source.poisoned.compareAndSet(false, true)) {
                source.transport.close()
                submit { if (binding === source) fail(RemoteError.NETWORK) }
            }
            return
        }
        submit {
            source.queued.decrementAndGet()
            source.queuedBytes.addAndGet(-bytes)
            if (binding !== source || source.intent != intent.get() || source.poisoned.get()) return@submit
            if(source.editorOverflow.get()) return@submit // close/reconnect will replay from the durable cursor
            if (event.generation < source.generation || event.generation <= source.retiredThrough) return@submit
            source.generation = event.generation
            try {
                if (event is ConnectionEvent.Frame && event.frame is ServerFrame.Hello &&
                    event.frame.protocol != FrameCodec.LIVE_PROTOCOL_VERSION.toLong()) {
                    fail(RemoteError.PROTOCOL_VERSION); return@submit
                }
                if (event is ConnectionEvent.Frame && event.frame is ServerFrame.Hello &&
                    (event.frame.user.id != source.project.expectedAccountId || event.frame.space.id != source.project.projectId ||
                        event.frame.space.storageEpoch != source.project.storageEpoch)) {
                    model = model.copy(identityWarning = IdentityWarning.PROJECT_IDENTITY_CHANGED)
                    fail(RemoteError.INVALID_DATA, clearDocuments = true); return@submit
                }
                if (event is ConnectionEvent.Frame && event.frame is ServerFrame.Hello) {
                    val durable = deps!!.documents.view()
                    // Match the repository rollback barrier: ACK/Change may be durable ahead
                    // of the last Synced cursor, without a pending write remaining (F2).
                    val highWater = maxOf(durable.cursor, durable.documents.values.maxOfOrNull { it.rev } ?: 0L)
                    if (event.frame.seq < highWater)
                        model = model.copy(identityWarning = IdentityWarning.SEQUENCE_ROLLBACK)
                }
                if (event is ConnectionEvent.State) when (event.problem) {
                    ConnectionProblem.IDENTITY_MISMATCH -> model = model.copy(identityWarning = IdentityWarning.PROJECT_IDENTITY_CHANGED)
                    ConnectionProblem.PROJECT_NOT_FOUND -> model = model.copy(identityWarning = IdentityWarning.PROJECT_NOT_FOUND)
                    else -> Unit
                }
                val effects = source.room.onConnectionEvent(event)
                when (event) {
                    is ConnectionEvent.State -> {
                        if (event.phase == ConnectionPhase.BACKOFF || event.phase == ConnectionPhase.STOPPED)
                            source.retiredThrough = maxOf(source.retiredThrough, event.generation)
                        model = model.copy(status = when (event.phase) {
                            ConnectionPhase.BACKOFF, ConnectionPhase.STOPPED -> RemoteStatus.OFFLINE
                            ConnectionPhase.READY -> RemoteStatus.SYNCING
                            else -> RemoteStatus.CONNECTING
                        }, error = event.problem?.let(::connectionError),
                            busy = event.phase !in listOf(ConnectionPhase.BACKOFF, ConnectionPhase.STOPPED))
                    }
                    is ConnectionEvent.Frame -> when (event.frame) {
                        is ServerFrame.Hello -> model = model.copy(status = RemoteStatus.SYNCING, error = null, busy = true)
                        is ServerFrame.Synced -> if (deps!!.documents.view().synced)
                            model = model.copy(status = RemoteStatus.ONLINE, error = null, busy = false)
                        is ServerFrame.Error -> if(!source.editing) { fail(RemoteError.SERVER); return@submit }
                            else model=model.copy(error=RemoteError.SERVER)
                        else -> Unit
                    }
                }
                refresh()
                if (event is ConnectionEvent.State && event.phase == ConnectionPhase.STOPPED && !source.editing) stopBinding()
                roomEffects(source,effects)
                if(event is ConnectionEvent.Frame && event.frame is ServerFrame.Error && source.editing)
                    closeEditorThen(priority=2) { fail(RemoteError.SERVER) }
                publish()
            } catch (e: DocumentRepositoryException) {
                if(source.editing) source.channel.halt()
                fail(if (e.reason == DocumentFailure.INVALID_FRAME) RemoteError.INVALID_DATA else RemoteError.STORAGE, clearDocuments = true)
            } catch (_: Exception) { if(source.editing) source.channel.halt();fail(RemoteError.STORAGE, clearDocuments = true) }
        }
    }

    private fun refresh() {
        val selected = model.selectedProject ?: return
        val view = deps!!.documents.view()
        if (view.namespace != partition(selected).namespace) return // never republish a retired repository view
        val files = view.documents.filterValues { !it.deleted }
        val selection = model.selectedPath?.takeIf { namespace == view.namespace && it in files }
        val sameNamespace=namespace == view.namespace
        val epoch = if (namespace != view.namespace) Math.addExact(model.documentEpoch, 1L) else model.documentEpoch
        namespace = view.namespace
        model = model.copy(documents = files, selectedPath = selection,
            recovery=if(sameNamespace) model.recovery else emptyList(),
            documentRecovery=view.recoveries,localDrafts=view.drafts.values.toList(),
            cacheAvailable = true, documentEpoch = epoch,
            hasCaseCollisions = view.caseCollisions.any { it in files })
    }

    private fun stopBinding() {
        val old = binding
        binding = null // fence before close; late callbacks cannot touch the repository
        model = model.copy(connectionActive = false)
        old?.channel?.retire()
        old?.transport?.close()
    }

    private fun fail(error: RemoteError, clearDocuments: Boolean = false) {
        if(binding?.editing == true) {
            model=model.copy(error=error);publish()
            closeEditorThen(priority=2) { fail(error,clearDocuments) };return
        }
        stopBinding()
        if (model.verificationStatus == VerificationStatus.VERIFYING || error in setOf(RemoteError.AUTHENTICATION,
                RemoteError.MISSING_CREDENTIAL, RemoteError.CREDENTIAL_STORAGE)) {
            verified = null; catalogScope = null; deps?.http?.invalidate()
            model = model.copy(verificationStatus = VerificationStatus.FAILED, verifiedAccount = null, projects = emptyList(),
                catalogStatus = CatalogStatus.IDLE, createIntents = emptyList())
        }
        val available = !clearDocuments && model.cacheAvailable
        model = model.copy(status = if (available) RemoteStatus.OFFLINE else RemoteStatus.DISCONNECTED,
            error = error, busy = false,
            documents = if (clearDocuments) emptyMap() else model.documents,
            selectedPath = if (clearDocuments) null else model.selectedPath,
            cacheAvailable = available, hasCaseCollisions = !clearDocuments && model.hasCaseCollisions)
        publish()
    }

    private fun publish() {
        val snapshot = model.copy(selectionGeneration = selectionGeneration.get(), documentNamespace = namespace, creatingProjectIds = inFlightCreates.values.toSet())
        val expected = activeIntent
        val snapshotNamespace = namespace
        val revision = uiRevision.incrementAndGet()
        ui.execute {
            // Superseded generation/namespace/status snapshots must not flash onto a delayed
            // main thread, even within the same profile intent.
            if (!closed.get() && intent.get() == expected && uiRevision.get() == revision && selectionGeneration.get() == snapshot.selectionGeneration) {
                visibleNamespace.set(snapshotNamespace)
                visibleProfileId.set(snapshot.selectedServer?.localProfileId)
                visibleDocumentEpoch.set(snapshot.documentEpoch)
                visibleSelectionGeneration.set(snapshot.selectionGeneration)
                observer(snapshot)
            }
        }
    }

    private fun submit(block: () -> Unit) {
        if (closed.get()) return
        try { worker.execute { if (!closed.get()) block() } }
        catch (_: RejectedExecutionException) { /* lifecycle disposal; no secret-bearing diagnostics */ }
    }

    override fun close() {
        if(editorActive.get()) {
            submit { resumeEditor=false;closeEditorThen(priority=3) { close() } }
            return
        }
        if (!closed.compareAndSet(false, true)) return
        intent.incrementAndGet()
        // An in-progress disk transaction can finish; queued commands/events and UI deliveries
        // are discarded. Cleanup runs after that transaction, never on the main thread.
        try { worker.execute { stopBinding(); deps?.http?.close() } } catch (_: RejectedExecutionException) { }
        worker.shutdown()
    }

    companion object {
        private fun eventBytes(event: ConnectionEvent): Long {
            val frame = (event as? ConnectionEvent.Frame)?.frame
            fun userLength(user: RemoteUser?) = user?.let { it.id.length.toLong() + it.name.length + it.color.length } ?: 0L
            val chars = when (frame) {
                is ServerFrame.Hello -> userLength(frame.user) + frame.space.id.length + frame.space.name.length
                is ServerFrame.Change -> frame.path.length.toLong() + (frame.content?.length ?: 0) + userLength(frame.by)
                is ServerFrame.Error -> frame.message.length.toLong() + (frame.of?.length ?: 0)
                is ServerFrame.Joined -> frame.state?.length?.toLong() ?: 0L
                is ServerFrame.Seeded -> frame.state?.length?.toLong() ?: 0L
                is ServerFrame.Update -> frame.update.length.toLong()
                is ServerFrame.Awareness -> frame.update.length.toLong()
                else -> 0L
            }
            return 256 + chars * 2
        }

        internal fun open(context: Context, ui: Executor, observer: (RemoteUiState) -> Unit): RemoteSession {
            val application = context.applicationContext
            val worker=Executors.newSingleThreadScheduledExecutor { Thread(it,"remote-documents").apply { isDaemon=true } }
            val session=RemoteSession({
                val legacy=AtomicDocumentStorage.open(application)
                val storage=AtomicDocumentStorage.openV2(application)
                SessionDependencies(ProfileStore.open(application), CredentialStore.open(application),
                    DocumentsRepository(storage),RoomRecoveryStore(storage),
                    writesEnabled = com.voxarium.remote.BuildConfig.EDITOR_WRITES_ENABLED,
                    serverProfiles = ServerProfileStore.open(application), creates = CreateIntentStore.open(application),
                    http = NativeSessionHttp(), migration = IdentityMigration(legacy, storage), legacyStorage = legacy) { observer ->
                    val connection = RemoteConnection(observer)
                    object : SessionConnection {
                        override fun connect(endpoint: RemoteEndpoint, bearer: String, projectId: String, expectedAccountId: String, storageEpoch: String) =
                            connection.connect(endpoint, bearer, projectId, expectedAccountId, storageEpoch)
                        override fun send(generation: Long, frame: ClientFrame, result: (SendResult) -> Unit) =
                            connection.send(generation, frame, result)
                        override fun close() = connection.close()
                    }
                }
            }, worker, ui, observer)
            worker.scheduleWithFixedDelay({ session.tickEditor() },100,100,java.util.concurrent.TimeUnit.MILLISECONDS)
            return session
        }

        private fun connectionError(problem: ConnectionProblem): RemoteError = when (problem) {
            ConnectionProblem.HTTP_UNAUTHORIZED, ConnectionProblem.KEY_REJECTED -> RemoteError.AUTHENTICATION
            ConnectionProblem.BAD_CONFIGURATION -> RemoteError.INVALID_PROFILE
            ConnectionProblem.PROTOCOL_MISMATCH -> RemoteError.PROTOCOL_VERSION
            ConnectionProblem.IDENTITY_MISMATCH -> RemoteError.INVALID_DATA
            ConnectionProblem.PROJECT_NOT_FOUND -> RemoteError.SERVER
            ConnectionProblem.TLS -> RemoteError.TLS
            ConnectionProblem.INVALID_FRAME, ConnectionProblem.CONSUMER_FAILED -> RemoteError.INVALID_DATA
            ConnectionProblem.HTTP_ERROR -> RemoteError.SERVER
            else -> RemoteError.NETWORK
        }
    }
}
