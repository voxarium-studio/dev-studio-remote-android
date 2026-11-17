package com.voxarium.remote.data.transport

import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLException
import kotlin.random.Random

enum class ConnectionPhase { AUTHENTICATING, OPENING, AWAITING_HELLO, READY, BACKOFF, STOPPED }
enum class ConnectionProblem {
    HTTP_UNAUTHORIZED, BAD_CONFIGURATION, KEY_REJECTED, TOO_FAR_BEHIND,
    PROTOCOL_MISMATCH, IDENTITY_MISMATCH, PROJECT_NOT_FOUND, HTTP_ERROR, NETWORK, TLS, OPEN_TIMEOUT, HELLO_TIMEOUT,
    INVALID_FRAME, LOCAL_OVERFLOW, CONSUMER_FAILED,
}
enum class SendResult { ENQUEUED, STALE_GENERATION, NOT_READY, INVALID_FRAME, BACKPRESSURE, CLOSED }

sealed interface ConnectionEvent {
    val generation: Long
    data class State(
        override val generation: Long,
        val phase: ConnectionPhase,
        val problem: ConnectionProblem? = null,
        val retryInMillis: Long? = null,
    ) : ConnectionEvent
    /** hello is delivered as a Frame after State(READY), on the same serial executor. */
    data class Frame(override val generation: Long, val frame: ServerFrame) : ConnectionEvent
}

internal data class ConnectionTiming(
    val openMillis: Long = 10_000,
    val helloMillis: Long = 15_000,
    val initialBackoffMillis: Long = 1_000,
    val maxBackoffMillis: Long = 30_000,
    val jitter: (Long) -> Long = { Random.nextLong(it / 2 + 1) },
)

/**
 * Native wire transport only (remote-android/protocol.md §§2–4,6,9).
 * All state, delivery and outgoing send decisions are serialized. Never retries writes.
 * The consumer must durably apply frames in this callback, or queue them in its own ordered
 * generation-aware actor; callbacks must not block waiting for another transport callback.
 */
class RemoteConnection internal constructor(
    private val client: OkHttpClient,
    private val timing: ConnectionTiming,
    private val observer: (ConnectionEvent) -> Unit,
) : Closeable {
    constructor(observer: (ConnectionEvent) -> Unit) : this(defaultClient(), ConnectionTiming(), observer) {
        ownsClient = true
    }

    private var ownsClient = false

    private val actor = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "remote-transport").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private val generation = AtomicLong()
    private val closed = AtomicBoolean()
    private val inbound = AtomicInteger()
    private val queuedBytes = AtomicLong()
    private var attempt: Attempt? = null
    private var retry: ScheduledFuture<*>? = null

    private class Credentials(val endpoint: RemoteEndpoint, val bearer: String,
                              val projectId: String, val accountId: String, val storageEpoch: String) {
        override fun toString() = "Credentials([redacted])"
    }
    private class Attempt(val generation: Long, val credentials: Credentials, val backoff: Long) {
        var authenticatedAccount: String? = null
        var call: Call? = null
        var socket: WebSocket? = null
        var timeout: ScheduledFuture<*>? = null
        var phase = ConnectionPhase.AUTHENTICATING
        var finished = false
        val inputFailed = AtomicBoolean()
    }

    /** Immediately fences all callbacks/sends belonging to an earlier profile or attempt. */
    fun connect(endpoint: RemoteEndpoint, bearer: String): Long {
        // A legacy selector cannot prove a v2 project/account/incarnation. Never probe v1 WS.
        check(!closed.get()) { "Transport is closed" }
        val id = generation.incrementAndGet()
        dispatch { if(generation.get() == id) { retire(); emit(ConnectionEvent.State(id, ConnectionPhase.STOPPED, ConnectionProblem.BAD_CONFIGURATION)) } }
        return id
    }

    /** Android identity design §4.2: immutable expected identity survives automatic retries. */
    fun connect(endpoint: RemoteEndpoint, bearer: String, projectId: String,
                expectedAccountId: String, storageEpoch: String): Long {
        check(!closed.get()) { "Transport is closed" }
        require(bearer.isNotEmpty() && bearer.length <= 8192 && bearer.all { it.code in 33..126 }) {
            "Invalid credential format"
        }
        require(Regex("[a-z0-9][a-z0-9._-]{0,63}").matches(projectId))
        require(com.voxarium.remote.data.security.validIdentityValue(expectedAccountId))
        require(Regex("[0-9a-f]{32}").matches(storageEpoch))
        require(endpoint.space.isEmpty() || endpoint.space == projectId)
        val id = generation.incrementAndGet()
        dispatch {
            if (generation.get() == id && !closed.get()) {
                retire()
                begin(id, Credentials(endpoint, bearer, projectId, expectedAccountId, storageEpoch), timing.initialBackoffMillis)
            }
        }
        return id
    }

    /** ENQUEUED means only accepted by the local socket, never a persistence ACK. */
    fun send(expectedGeneration: Long, frame: ClientFrame, result: (SendResult) -> Unit = {}) {
        if (closed.get()) { result(SendResult.CLOSED); return }
        val accepted = tryDispatch {
            val current = attempt
            val outcome = when {
                generation.get() != expectedGeneration || current?.generation != expectedGeneration -> SendResult.STALE_GENERATION
                current.finished || current.phase != ConnectionPhase.READY -> SendResult.NOT_READY
                else -> {
                    val encoded = try { FrameCodec.encode(frame) } catch (_: Exception) { null }
                    when {
                        encoded == null -> SendResult.INVALID_FRAME
                        generation.get() != expectedGeneration || closed.get() -> SendResult.STALE_GENERATION
                        current.socket?.send(encoded) == true -> SendResult.ENQUEUED
                        else -> {
                            fail(current, ConnectionProblem.NETWORK, true)
                            SendResult.BACKPRESSURE
                        }
                    }
                }
            }
            try { result(outcome) } catch (_: Exception) {
                current?.let { fail(it, ConnectionProblem.CONSUMER_FAILED, false) }
            }
        }
        if (!accepted) result(SendResult.CLOSED)
    }

    fun disconnect(): Long {
        val id = generation.incrementAndGet()
        dispatch {
            if (generation.get() == id) {
                retire()
                emit(ConnectionEvent.State(id, ConnectionPhase.STOPPED))
            }
        }
        return id
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            generation.incrementAndGet()
            dispatch {
                retire()
                if (ownsClient) {
                    client.dispatcher.executorService.shutdown()
                    client.connectionPool.evictAll()
                }
                actor.shutdown()
            }
        }
    }

    private fun begin(id: Long, credentials: Credentials, backoff: Long) {
        val a = Attempt(id, credentials, backoff)
        attempt = a
        if (!state(a, ConnectionPhase.AUTHENTICATING)) return
        a.timeout = actor.schedule({ if (active(a)) fail(a, ConnectionProblem.OPEN_TIMEOUT, true) }, timing.openMillis, TimeUnit.MILLISECONDS)
        val request = Request.Builder().url(credentials.endpoint.v2MeUrl())
            .header("Authorization", "Bearer ${credentials.bearer}").get().build()
        a.call = client.newCall(request).also { call ->
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = dispatch {
                    if (active(a)) fail(a, if (e is SSLException) ConnectionProblem.TLS else ConnectionProblem.NETWORK, e !is SSLException)
                }
                override fun onResponse(call: Call, response: Response) {
                    // Bound the body before allocation, never expose response/error text in events.
                    val code = response.code
                    var readFailure: ConnectionProblem? = null
                    val body = try {
                        response.use {
                            if (code != 200) null else {
                                val source = it.body?.source() ?: throw IOException()
                                if (source.request(MAX_ME_BYTES + 1)) null
                                else Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                                    .decode(java.nio.ByteBuffer.wrap(source.readByteArray())).toString()
                            }
                        }
                    } catch (_: java.nio.charset.CharacterCodingException) { null } catch (error: IOException) {
                        readFailure = if (error is SSLException) ConnectionProblem.TLS else ConnectionProblem.NETWORK
                        null
                    } catch (_: Exception) { null }
                    dispatch {
                        if (!active(a)) return@dispatch
                        when {
                            code == 401 -> fail(a, ConnectionProblem.HTTP_UNAUTHORIZED, false)
                            code != 200 -> fail(a, ConnectionProblem.HTTP_ERROR, code == 408 || code == 429 || code >= 500)
                            readFailure != null -> fail(a, readFailure, readFailure == ConnectionProblem.NETWORK)
                            body == null -> fail(a, ConnectionProblem.INVALID_FRAME, false)
                            else -> {
                                val identity = try { FrameCodec.decodeMeV2(body) } catch (_: Exception) { null }
                                when {
                                    identity == null -> fail(a, ConnectionProblem.INVALID_FRAME, false)
                                    identity.protocol != FrameCodec.LIVE_PROTOCOL_VERSION.toLong() -> fail(a, ConnectionProblem.PROTOCOL_MISMATCH, false)
                                    identity.user.id != a.credentials.accountId -> fail(a, ConnectionProblem.IDENTITY_MISMATCH, false)
                                    else -> { a.authenticatedAccount = identity.user.id; openSocket(a) }
                                }
                            }
                        }
                    }
                }
            })
        }
    }

    private fun openSocket(a: Attempt) {
        a.call = null
        a.timeout?.cancel(false)
        if (!state(a, ConnectionPhase.OPENING)) return
        a.timeout = actor.schedule({ if (active(a)) fail(a, ConnectionProblem.OPEN_TIMEOUT, true) }, timing.openMillis, TimeUnit.MILLISECONDS)
        val request = Request.Builder().url(a.credentials.endpoint.v2SyncUrl(a.credentials.projectId))
            .header("Authorization", "Bearer ${a.credentials.bearer}").build()
        a.socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = dispatch {
                if (!active(a)) { webSocket.cancel(); return@dispatch }
                a.timeout?.cancel(false)
                if (state(a, ConnectionPhase.AWAITING_HELLO)) {
                    a.timeout = actor.schedule({ if (active(a)) fail(a, ConnectionProblem.HELLO_TIMEOUT, true) }, timing.helloMillis, TimeUnit.MILLISECONDS)
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (generation.get() != a.generation || closed.get() || a.inputFailed.get()) return
                val bytes = text.length * 2L // JVM UTF-16 budget, not just the number of frames.
                val count = inbound.incrementAndGet()
                val total = queuedBytes.addAndGet(bytes)
                if (text.length > MAX_FRAME_CHARS || count > MAX_QUEUED_FRAMES || total > MAX_QUEUED_BYTES) {
                    inbound.decrementAndGet()
                    queuedBytes.addAndGet(-bytes)
                    inputFailure(a, ConnectionProblem.LOCAL_OVERFLOW, true)
                    return
                }
                if (!tryDispatch {
                    inbound.decrementAndGet()
                    queuedBytes.addAndGet(-bytes)
                    if (!active(a)) return@tryDispatch
                    val frame = try { FrameCodec.decodeV2(text) } catch (_: Exception) {
                        fail(a, ConnectionProblem.INVALID_FRAME, false); return@tryDispatch
                    }
                    if (a.phase == ConnectionPhase.AWAITING_HELLO) {
                        if (frame !is ServerFrame.Hello) {
                            fail(a, ConnectionProblem.INVALID_FRAME, false); return@tryDispatch
                        }
                        if (frame.protocol != FrameCodec.LIVE_PROTOCOL_VERSION.toLong()) {
                            fail(a, ConnectionProblem.PROTOCOL_MISMATCH, false); return@tryDispatch
                        }
                        if(frame.user.id != a.authenticatedAccount || frame.user.id != a.credentials.accountId ||
                            frame.space.id != a.credentials.projectId || frame.space.storageEpoch != a.credentials.storageEpoch) {
                            fail(a, ConnectionProblem.IDENTITY_MISMATCH, false); return@tryDispatch
                        }
                        a.timeout?.cancel(false)
                        if (!state(a, ConnectionPhase.READY)) return@tryDispatch
                    } else if (a.phase != ConnectionPhase.READY || frame is ServerFrame.Hello) {
                        fail(a, ConnectionProblem.INVALID_FRAME, false); return@tryDispatch
                    }
                    if (!emit(ConnectionEvent.Frame(a.generation, frame))) fail(a, ConnectionProblem.CONSUMER_FAILED, false)
                }) {
                    inbound.decrementAndGet()
                    queuedBytes.addAndGet(-bytes)
                }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Do not retain arbitrary binary buffers in the actor queue.
                inputFailure(a, ConnectionProblem.INVALID_FRAME, false)
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = dispatch {
                if (active(a)) closedByServer(a, code)
                webSocket.close(code, null)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dispatch {
                if (active(a)) closedByServer(a, code)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dispatch {
                val code = response?.code
                response?.close()
                if (!active(a)) return@dispatch
                fail(a, when {
                    code == 401 -> ConnectionProblem.HTTP_UNAUTHORIZED
                    t is SSLException -> ConnectionProblem.TLS
                    code != null -> ConnectionProblem.HTTP_ERROR
                    else -> ConnectionProblem.NETWORK
                }, code != 401 && t !is SSLException && (code == null || code == 408 || code == 429 || code >= 500))
            }
        })
    }

    private fun inputFailure(a: Attempt, problem: ConnectionProblem, retry: Boolean) {
        if (generation.get() == a.generation && !closed.get() && a.inputFailed.compareAndSet(false, true)) {
            dispatch { if (active(a)) fail(a, problem, retry) }
        }
    }

    private fun closedByServer(a: Attempt, code: Int) = when (code) {
        4400 -> fail(a, ConnectionProblem.BAD_CONFIGURATION, false)
        4401 -> fail(a, ConnectionProblem.KEY_REJECTED, false)
        4404 -> fail(a, ConnectionProblem.PROJECT_NOT_FOUND, false)
        4408 -> fail(a, ConnectionProblem.TOO_FAR_BEHIND, true)
        else -> fail(a, ConnectionProblem.NETWORK, true)
    }

    private fun active(a: Attempt) = !closed.get() && generation.get() == a.generation && attempt === a && !a.finished
    private fun state(a: Attempt, phase: ConnectionPhase): Boolean {
        a.phase = phase
        if (!emit(ConnectionEvent.State(a.generation, phase))) {
            fail(a, ConnectionProblem.CONSUMER_FAILED, false)
            return false
        }
        return active(a)
    }
    private fun fail(a: Attempt, problem: ConnectionProblem, recoverable: Boolean) {
        if (!active(a)) return
        a.finished = true
        a.timeout?.cancel(false)
        a.call?.cancel()
        a.call = null
        a.socket?.cancel()
        a.socket = null
        if (!recoverable) {
            attempt = null // Drop our reference to the Bearer credential on terminal errors.
            emit(ConnectionEvent.State(a.generation, ConnectionPhase.STOPPED, problem))
            return
        }
        val base = if (a.phase == ConnectionPhase.READY) timing.initialBackoffMillis else a.backoff
        val delay = base + timing.jitter(base).coerceIn(0, base / 2)
        if (!emit(ConnectionEvent.State(a.generation, ConnectionPhase.BACKOFF, problem, delay))) {
            attempt = null
            return
        }
        retry = actor.schedule({
            if (!closed.get() && generation.compareAndSet(a.generation, a.generation + 1)) {
                val next = a.generation + 1
                begin(next, a.credentials, (base * 2).coerceAtMost(timing.maxBackoffMillis))
            }
        }, delay, TimeUnit.MILLISECONDS)
    }
    private fun retire() {
        retry?.cancel(false)
        retry = null
        attempt?.let {
            it.finished = true
            it.timeout?.cancel(false)
            it.call?.cancel()
            it.socket?.cancel()
        }
        attempt = null
    }
    private fun emit(event: ConnectionEvent): Boolean {
        if (closed.get() || generation.get() != event.generation) return true
        return try { observer(event); true } catch (_: Exception) { false }
    }
    private fun dispatch(action: () -> Unit) { tryDispatch(action) }
    private fun tryDispatch(action: () -> Unit): Boolean =
        try { actor.execute(action); true } catch (_: RejectedExecutionException) { false }

    companion object {
        private const val MAX_ME_BYTES = 64L * 1024
        private const val MAX_FRAME_CHARS = 32 * 1024 * 1024
        private const val MAX_QUEUED_FRAMES = 512
        private const val MAX_QUEUED_BYTES = 64L * 1024 * 1024
        internal fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS).pingInterval(30, TimeUnit.SECONDS).build()
    }
}
