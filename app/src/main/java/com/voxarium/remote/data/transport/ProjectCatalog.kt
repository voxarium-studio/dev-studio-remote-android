package com.voxarium.remote.data.transport

import okio.BufferedSink
import okhttp3.RequestBody
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.Closeable

/** Labels are literal presentation metadata, never selectors (dev spec §16.7). */
data class ProjectSummary(val id: String, val displayName: String, val storageEpoch: String,
    val metadataRevision: Long, val createdAt: String) {
    override fun toString() = "ProjectSummary(redacted)"
}
data class ProjectPage(val projects: List<ProjectSummary>, val nextAfter: String?) {
    override fun toString() = "ProjectPage(redacted)"
}
class CatalogScope internal constructor(val generation: Long, val server: VerifiedServer) {
    override fun toString() = "CatalogScope(redacted)"
}
/** GET resolution is information only. Even PRESENT never selects/binds the project. */
data class UncertainCreate(val id: String, val observedProject: ProjectSummary? = null) {
    override fun toString() = "UncertainCreate(redacted)"
}
sealed class CatalogResult<out T> {
    data class Success<T>(val value: T) : CatalogResult<T>()
    data class Failed(val problem: ServerProblem) : CatalogResult<Nothing>()
    data class CreateUncertain(val originalId: String) : CatalogResult<Nothing>()
    final override fun toString() = "CatalogResult.${javaClass.simpleName}(redacted)"
}
data class CatalogReply<T>(val requestId: Long, val scope: CatalogScope, val result: CatalogResult<T>) {
    override fun toString() = "CatalogReply(redacted)"
}

/**
 * Native HTTP catalog. A5 owns durable UI intent/Select; this adapter never selects, opens a
 * socket, writes Documents, generates an ID or retries a POST (§16.7–16.8).
 * Capture the credential for exactly scope.server.identity. On ANY selection/key revision
 * change call select/invalidate before scheduling new work. Callbacks are serialized with
 * invalidation; queued consumer work must still check isCurrent(reply.scope).
 * Unknown Create records survive cancellations/reselections in this instance, not process death:
 * the integrating owner must durably retain the original ID before invoking Create.
 */
class ProjectCatalog internal constructor(client: OkHttpClient, timeoutMillis: Long = 10_000) : Closeable {
    constructor() : this(OkHttpClient()) { ownsClient = true }
    private val http = BoundedRemoteHttp(client, timeoutMillis)
    private var ownsClient = false
    private var closed = false
    private var generation = 0L
    private var requestId = 0L
    private class Binding(val scope: CatalogScope, val bearer: String) {
        override fun toString() = "CatalogBinding(redacted)"
    }
    private var binding: Binding? = null
    private val calls = mutableMapOf<Long, Call>()
    private data class CreateKey(val url: String, val account: String, val id: String) {
        override fun toString() = "CreateKey(redacted)"
    }
    private val uncertain = mutableMapOf<CreateKey, UncertainCreate>()

    @Synchronized
    fun select(server: VerifiedServer, bearer: String): CatalogScope {
        check(!closed) { "Catalog is closed" }
        checkBearer(bearer)
        invalidate()
        return CatalogScope(generation, server).also { binding = Binding(it, bearer) }
    }
    @Synchronized
    fun invalidate() {
        generation++
        binding = null
        calls.values.forEach { it.cancel() }
        calls.clear()
    }
    @Synchronized
    fun isCurrent(scope: CatalogScope): Boolean = !closed && binding?.scope === scope
    @Synchronized
    fun cancelRequest(scope: CatalogScope, id: Long) { if (isCurrent(scope)) calls.remove(id)?.cancel() }
    @Synchronized
    fun uncertainCreates(scope: CatalogScope): List<UncertainCreate> {
        if (!isCurrent(scope)) return emptyList()
        return uncertain.filterKeys { it.url == scope.server.identity.endpoint.serverUrl && it.account == scope.server.user.id }.values.toList()
    }
    /** Restore an owner's durable unknown-outcome record before exposing Create after restart. */
    @Synchronized
    fun restoreUncertainCreate(scope: CatalogScope, originalId: String) {
        check(isCurrent(scope)) { "Stale catalog scope" }
        ProjectCatalogCodec.checkId(originalId)
        val k = key(scope, originalId)
        require(uncertain.containsKey(k) || uncertain.size < 256) { "Create recovery limit reached" }
        if (!uncertain.containsKey(k)) uncertain[k] = UncertainCreate(originalId)
    }
    private fun key(scope: CatalogScope, id: String) = CreateKey(scope.server.identity.endpoint.serverUrl, scope.server.user.id, id)

    /** One authoritative keyset page, no snapshot guarantee. Refresh starts with after=null. */
    @Synchronized
    fun listProjects(scope: CatalogScope, limit: Int = 100, after: String? = null,
        callback: (CatalogReply<ProjectPage>) -> Unit): Long {
        val url = scope.server.identity.endpoint.projectsUrl().newBuilder().addQueryParameter("limit", limit.toString())
        if (after != null) url.addQueryParameter("after", after)
        return read(scope, url.build(), callback, valid = {
            require(limit in 1..200); after?.let { ProjectCatalogCodec.checkId(it) }
        }) { ProjectCatalogCodec.decodePage(it, limit, after) }
    }

    /** Bounded refresh, ID-deduplicated. Keyset pages are NOT a cross-page snapshot. */
    @Synchronized
    fun listAllProjects(scope: CatalogScope, callback: (CatalogReply<List<ProjectSummary>>) -> Unit): Long {
        val rid = ++requestId
        val rows = linkedMapOf<String, ProjectSummary>()
        var pages = 0
        fun deliver(result: CatalogResult<List<ProjectSummary>>) { callback(CatalogReply(rid, scope, result)) }
        eligible(scope)?.let { deliver(CatalogResult.Failed(it)); return rid }
        fun next(after: String?) {
            val url = scope.server.identity.endpoint.projectsUrl().newBuilder().addQueryParameter("limit", "100")
            if (after != null) url.addQueryParameter("after", after)
            calls[rid] = http.get(url.build(), binding!!.bearer, MAX_CATALOG_BYTES) { reply ->
                synchronized(this) {
                    if (!isCurrent(scope) || calls.remove(rid) == null) return@synchronized
                    val failure = reply.failure()
                    if (failure != null) { deliver(CatalogResult.Failed(failure)); return@synchronized }
                    val page = try { ProjectCatalogCodec.decodePage(reply.body ?: "", 100, after) } catch (_: Exception) { null }
                    if (page == null) { deliver(CatalogResult.Failed(ServerProblem.INVALID_RESPONSE)); return@synchronized }
                    page.projects.forEach { rows[it.id] = it }
                    pages++
                    if (rows.size > 10_000 || (pages >= 100 && page.nextAfter != null)) {
                        deliver(CatalogResult.Failed(ServerProblem.INVALID_RESPONSE)); return@synchronized
                    }
                    if (page.nextAfter == null) deliver(CatalogResult.Success(rows.values.toList()))
                    else next(page.nextAfter)
                }
            }
        }
        next(null)
        return rid
    }

    @Synchronized
    fun getProject(scope: CatalogScope, id: String, callback: (CatalogReply<ProjectSummary>) -> Unit): Long {
        // Validate before constructing path segments, but surface only allowlisted failures.
        val validId = try { ProjectCatalogCodec.checkId(id) } catch (_: Exception) { null }
        val url = if (validId == null) scope.server.identity.endpoint.projectsUrl() else scope.server.identity.endpoint.projectUrl(id)
        return read(scope, url, callback, valid = { require(validId != null) }, onResult = { result ->
            val k = key(scope, id)
            if (uncertain.containsKey(k)) when (result) {
                is CatalogResult.Success -> uncertain[k] = UncertainCreate(id, result.value)
                is CatalogResult.Failed -> if (result.problem == ServerProblem.PROJECT_NOT_FOUND) uncertain.remove(k)
                else -> Unit
            }
        }) { ProjectCatalogCodec.decodeProject(it, id) }
    }

    @Synchronized
    fun resolveLegacy(scope: CatalogScope, exactName: String, callback: (CatalogReply<ProjectSummary>) -> Unit): Long =
        read(scope, scope.server.identity.endpoint.legacyResolutionUrl(exactName), callback, valid = {
            ProjectCatalogCodec.unicode(exactName)
            require(exactName.isNotEmpty() && exactName.toByteArray(Charsets.UTF_8).size <= 480)
        }) { ProjectCatalogCodec.decodeLegacyResolution(it, exactName) }

    /** Explicit, already-confirmed native intent only. Not called by refresh/get/select/resolve. */
    @Synchronized
    fun createProject(scope: CatalogScope, id: String, displayName: String,
        callback: (CatalogReply<ProjectSummary>) -> Unit): Long {
        val rid = ++requestId
        fun fail(problem: ServerProblem): Long { callback(CatalogReply(rid, scope, CatalogResult.Failed(problem))); return rid }
        eligible(scope)?.let { return fail(it) }
        val body = try { ProjectCatalogCodec.encodeCreate(id, displayName) } catch (_: Exception) { return fail(ServerProblem.INVALID_INPUT) }
        val k = key(scope, id)
        // A timeout/cancel can have committed. GET original ID first, never blind replay.
        if (uncertain.containsKey(k)) {
            callback(CatalogReply(rid, scope, CatalogResult.CreateUncertain(id))); return rid
        }
        if (uncertain.size >= 256) return fail(ServerProblem.INVALID_INPUT)
        uncertain[k] = UncertainCreate(id) // BEFORE dispatch, survives cancellation/late response.
        val request = Request.Builder().url(scope.server.identity.endpoint.projectsUrl())
            .header("Authorization", "Bearer ${binding!!.bearer}").header("Cache-Control", "no-store")
            .post(object : RequestBody() {
                private val delegate = body.toRequestBody("application/json; charset=utf-8".toMediaType())
                override fun contentType() = delegate.contentType()
                override fun contentLength() = delegate.contentLength()
                override fun writeTo(sink: BufferedSink) = delegate.writeTo(sink)
                // Also prevent OkHttp's HTTP 408/503/auth follow-up machinery replaying POST.
                override fun isOneShot() = true
            }).build()
        calls[rid] = http.request(request, MAX_CATALOG_BYTES) { reply ->
            synchronized(this) {
                if (!isCurrent(scope) || calls.remove(rid) == null) return@synchronized
                val code = ProjectCatalogCodec.errorCode(reply.body)
                val result: CatalogResult<ProjectSummary> = if (reply.problem == null && reply.status == 201) {
                    val project = try { ProjectCatalogCodec.decodeProject(reply.body ?: "", id) } catch (_: Exception) { null }
                    if (project != null && project.displayName == ProjectCatalogCodec.normalizeDisplayName(displayName)) {
                        uncertain.remove(k)
                        CatalogResult.Success(project)
                    } else CatalogResult.CreateUncertain(id)
                } else if (reply.problem == null && ((reply.status == 409 && code == "project_exists") ||
                        (reply.status == 401 && code == "unauthorised") ||
                        (reply.status == 400 && code in setOf("invalid_id", "invalid_display_name", "invalid_request")) ||
                        (reply.status == 413 && code == "payload_too_large"))) {
                    uncertain.remove(k)
                    CatalogResult.Failed(if (reply.status == 409) ServerProblem.PROJECT_EXISTS
                        else if (reply.status == 401) ServerProblem.UNAUTHORIZED else ServerProblem.INVALID_INPUT)
                } else CatalogResult.CreateUncertain(id)
                callback(CatalogReply(rid, scope, result))
            }
        }
        return rid
    }

    private fun eligible(scope: CatalogScope): ServerProblem? = when {
        !isCurrent(scope) -> ServerProblem.STALE_REQUEST
        !scope.server.catalogSupported -> ServerProblem.UNSUPPORTED
        calls.size >= 8 -> ServerProblem.HTTP_ERROR
        else -> null
    }
    private fun <T> read(scope: CatalogScope, url: okhttp3.HttpUrl, callback: (CatalogReply<T>) -> Unit,
        valid: () -> Unit = {}, onResult: (CatalogResult<T>) -> Unit = {}, decode: (String) -> T): Long {
        val rid = ++requestId
        val problem = eligible(scope) ?: try { valid(); null } catch (_: Exception) { ServerProblem.INVALID_INPUT }
        if (problem != null) { callback(CatalogReply(rid, scope, CatalogResult.Failed(problem))); return rid }
        calls[rid] = http.get(url, binding!!.bearer, MAX_CATALOG_BYTES) { reply ->
            synchronized(this) {
                if (!isCurrent(scope) || calls.remove(rid) == null) return@synchronized
                val result: CatalogResult<T> = if (reply.failure() != null) {
                    val code = ProjectCatalogCodec.errorCode(reply.body)
                    CatalogResult.Failed(when {
                        reply.problem == null && reply.status == 404 && code == "project_not_found" -> ServerProblem.PROJECT_NOT_FOUND
                        reply.problem == null && reply.status == 404 && code == "legacy_space_not_found" -> ServerProblem.LEGACY_NOT_FOUND
                        else -> reply.failure()!!
                    })
                } else try { CatalogResult.Success(decode(reply.body ?: "")) }
                    catch (_: Exception) { CatalogResult.Failed(ServerProblem.INVALID_RESPONSE) }
                onResult(result)
                callback(CatalogReply(rid, scope, result))
            }
        }
        return rid
    }
    @Synchronized
    override fun close() {
        if (!closed) { invalidate(); closed = true; if (ownsClient) http.release() }
    }
    companion object { internal const val MAX_CATALOG_BYTES = 1024L * 1024 }
}



