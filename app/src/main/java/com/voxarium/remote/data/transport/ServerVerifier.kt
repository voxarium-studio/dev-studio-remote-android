package com.voxarium.remote.data.transport

import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.Authenticator
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** Capture once per intent. A rotated key MUST increment revision, even at the same credentialId. */
data class ServerRequestIdentity(
    val profileId: String,
    val endpoint: RemoteEndpoint,
    val credentialId: String,
    val credentialRevision: Long,
    val selectionGeneration: Long,
) {
    init {
        require(profileId.isNotEmpty() && credentialId.isNotEmpty() &&
            credentialRevision >= 0 && selectionGeneration >= 0 && endpoint.space.isEmpty()) { "Invalid request identity" }
    }
    override fun toString() = "ServerRequestIdentity(redacted)"
}

enum class ServerProblem {
    UNAUTHORIZED, TLS, TIMEOUT, NETWORK, REDIRECT, HTTP_ERROR, INVALID_RESPONSE,
    UNSUPPORTED, INVALID_INPUT, STALE_REQUEST, PROJECT_EXISTS, PROJECT_NOT_FOUND,
    LEGACY_NOT_FOUND, CREATE_UNCERTAIN,
}

/** Ephemeral authentication only; not a persisted grant and never an editor/write gate. */
class VerifiedServer internal constructor(
    val identity: ServerRequestIdentity,
    val user: RemoteUser,
    val protocol: Long,
    val catalogApi: Long?,
    capabilities: Set<String>,
) {
    val capabilities: Set<String> = capabilities.toSet()
    val catalogSupported: Boolean get() = protocol == 2L && catalogApi == 2L &&
        capabilities.containsAll(ProjectCatalogCodec.requiredCapabilities)
    override fun toString() = "VerifiedServer(redacted)"
}

sealed class VerificationResult {
    data class Verified(val server: VerifiedServer) : VerificationResult()
    data class Failed(val problem: ServerProblem) : VerificationResult()
    final override fun toString() = "VerificationResult.${javaClass.simpleName}(redacted)"
}

data class VerificationReply(val generation: Long, val identity: ServerRequestIdentity, val result: VerificationResult) {
    override fun toString() = "VerificationReply(redacted)"
}

/**
 * Android design §4 / dev spec §16.7–16.8. HTTP only: no socket, dummy selector,
 * catalog mutation, fallback probing, retries or redirects. Callback delivery and invalidation
 * are serialized. Keep callbacks short; capture reply.identity on the consumer's ordered worker.
 */
class ServerVerifier internal constructor(client: OkHttpClient, timeoutMillis: Long = 10_000) : Closeable {
    constructor() : this(OkHttpClient()) { ownsClient = true }
    private val http = BoundedRemoteHttp(client, timeoutMillis)
    private var ownsClient = false
    private var generation = 0L
    private var currentIdentity: ServerRequestIdentity? = null
    private var call: Call? = null
    private var closed = false

    @Synchronized
    fun verify(identity: ServerRequestIdentity, bearer: String, callback: (VerificationReply) -> Unit): Long {
        check(!closed) { "Verifier is closed" }
        checkBearer(bearer)
        invalidate()
        val id = generation
        currentIdentity = identity
        fun deliver(result: VerificationResult) {
            if (generation == id && !closed) {
                call = null
                callback(VerificationReply(id, identity, result))
            }
        }
        call = http.get(identity.endpoint.healthUrl(), null, MAX_ME_BYTES) { health ->
            synchronized(this) {
                if (closed || generation != id) return@synchronized
                val failure = health.failure()
                if (failure != null) { deliver(VerificationResult.Failed(failure)); return@synchronized }
                val protocol = try {
                    val obj = ProjectCatalogCodec.json(health.body ?: "")
                    if (ProjectCatalogCodec.string(obj.opt("service"), 64) != "voxarium_studio_remote") ProjectCatalogCodec.bad()
                    ProjectCatalogCodec.number(obj.opt("protocol")).also { version ->
                        if (version == 2L) {
                            if (ProjectCatalogCodec.number(obj.opt("catalog_api")) != 2L) ProjectCatalogCodec.bad()
                            val supported = obj.opt("supported_protocols") as? org.json.JSONArray ?: ProjectCatalogCodec.bad()
                            if (supported.length() !in 1..16 || (0 until supported.length()).none {
                                    ProjectCatalogCodec.number(supported.opt(it)) == 2L }) ProjectCatalogCodec.bad()
                        }
                    }
                } catch (_: Exception) { deliver(VerificationResult.Failed(ServerProblem.INVALID_RESPONSE)); return@synchronized }
                if (protocol != 1L && protocol != 2L) {
                    deliver(VerificationResult.Failed(ServerProblem.UNSUPPORTED)); return@synchronized
                }
                val url = if (protocol == 2L) identity.endpoint.v2MeUrl() else identity.endpoint.meUrl()
                call = http.get(url, bearer, MAX_ME_BYTES) { me ->
                    synchronized(this) {
                        if (closed || generation != id) return@synchronized
                        val problem = me.failure()
                        if (problem != null) deliver(VerificationResult.Failed(problem))
                        else {
                            val verified = try {
                                if (protocol == 2L) ProjectCatalogCodec.decodeV2Me(me.body ?: "", identity)
                                else ProjectCatalogCodec.decodeLegacyMe(me.body ?: "", identity)
                            } catch (_: Exception) { null }
                            deliver(if (verified == null) VerificationResult.Failed(ServerProblem.INVALID_RESPONSE)
                                else VerificationResult.Verified(verified))
                        }
                    }
                }
            }
        }
        return id
    }

    @Synchronized
    fun isCurrent(reply: VerificationReply): Boolean = !closed && reply.generation == generation && reply.identity == currentIdentity

    /** Call before a profile/URL/key revision switch, including replacement with the same ID. */
    @Synchronized
    fun invalidate(): Long {
        generation++
        currentIdentity = null
        call?.cancel()
        call = null
        return generation
    }
    @Synchronized
    override fun close() {
        if (!closed) { invalidate(); closed = true; if (ownsClient) http.release() }
    }
    companion object { internal const val MAX_ME_BYTES = 64L * 1024 }
}

internal fun checkBearer(bearer: String) {
    require(bearer.isNotEmpty() && bearer.length <= 8192 && bearer.all { it.code in 33..126 }) { "Invalid credential format" }
}
internal data class RemoteHttpReply(val status: Int = 0, val body: String? = null, val problem: ServerProblem? = null) {
    fun failure(expected: Int = 200): ServerProblem? = when {
        problem != null -> problem
        status == expected -> null
        status == 401 -> ServerProblem.UNAUTHORIZED
        status in 300..399 -> ServerProblem.REDIRECT
        else -> ServerProblem.HTTP_ERROR
    }
    override fun toString() = "RemoteHttpReply(redacted)"
}

/** Default trust/hostname validation; injected clients are internal test seams only. */
internal class BoundedRemoteHttp(client: OkHttpClient, timeoutMillis: Long) {
    init { require(timeoutMillis in 1..30_000) }
    private val client = client.newBuilder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .cookieJar(CookieJar.NO_COOKIES).cache(null)
        // OkHttp's 503 Retry-After:0 follow-up ignores retryOnConnectionFailure(false).
        // Surface the first response, not an invisible second request, even for GET.
        .addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (response.code == 503) response.newBuilder().header("Retry-After", "1").build() else response
        }
        .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS).readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .writeTimeout(timeoutMillis, TimeUnit.MILLISECONDS).build()
    fun get(url: okhttp3.HttpUrl, bearer: String?, maxBytes: Long, callback: (RemoteHttpReply) -> Unit): Call {
        val builder = Request.Builder().url(url).get().header("Cache-Control", "no-store")
        if (bearer != null) builder.header("Authorization", "Bearer $bearer")
        return request(builder.build(), maxBytes, callback)
    }
    fun request(request: Request, maxBytes: Long, callback: (RemoteHttpReply) -> Unit): Call =
        client.newCall(request).also { call ->
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    callback(RemoteHttpReply(problem = when (e) {
                        is SSLException -> ServerProblem.TLS
                        is InterruptedIOException -> ServerProblem.TIMEOUT
                        else -> ServerProblem.NETWORK
                    }))
                }
                override fun onResponse(call: Call, response: Response) {
                    val status = response.code
                    val result = try {
                        response.use {
                            val source = it.body?.source() ?: throw CatalogValidationException()
                            if (source.request(maxBytes + 1)) throw CatalogValidationException()
                            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT)
                            RemoteHttpReply(status, decoder.decode(ByteBuffer.wrap(source.readByteArray())).toString())
                        }
                    } catch (e: Exception) {
                        RemoteHttpReply(status, problem = when (e) {
                            is SSLException -> ServerProblem.TLS
                            is InterruptedIOException -> ServerProblem.TIMEOUT
                            is java.nio.charset.CharacterCodingException -> ServerProblem.INVALID_RESPONSE
                            is IOException -> ServerProblem.NETWORK
                            else -> ServerProblem.INVALID_RESPONSE
                        })
                    }
                    callback(result)
                }
            })
        }
    fun release() { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
}

