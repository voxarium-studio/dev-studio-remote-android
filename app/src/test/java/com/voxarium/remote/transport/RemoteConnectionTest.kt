package com.voxarium.remote.transport

import com.voxarium.remote.data.transport.*
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class RemoteConnectionTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private val connections = mutableListOf<RemoteConnection>()
    private val events = LinkedBlockingQueue<ConnectionEvent>()
    private val history = CopyOnWriteArrayList<ConnectionEvent>()
    private val frames = LinkedBlockingQueue<String>()
    private val key = "vxs_mock_only_not_a_real_credential"
    private val hello = """{"t":"hello","protocol":2,"user":{"id":"u","display_name":"Alice","color":"#ffffff"},"space":{"id":"game","display_name":"Game label","storage_epoch":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},"seq":0}"""
    private val me = """{"protocol":2,"user":{"id":"u","display_name":"Alice","color":"#ffffff"}}"""

    private val epoch = "a".repeat(32)
    private fun RemoteConnection.start(target: RemoteEndpoint, bearer: String): Long =
        connect(target,bearer,target.space,"u",epoch)

    @Before fun setup() {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val testTrust = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        server = MockWebServer()
        server.useHttps(serverTls.sslSocketFactory(), false)
        server.start()
        // Test-only exact CA trust; normal hostname verification remains enabled.
        client = RemoteConnection.defaultClient().newBuilder()
            .sslSocketFactory(testTrust.sslSocketFactory(), testTrust.trustManager).build()
    }
    @After fun cleanup() {
        connections.forEach { it.close() }
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        server.shutdown()
    }
    private fun connection(helloMillis: Long = 1500, openMillis: Long = 2000, observe: ((ConnectionEvent) -> Unit)? = null): RemoteConnection =
        RemoteConnection(client, ConnectionTiming(openMillis, helloMillis, 80, 320, { 0 })) {
            history += it
            events.offer(it)
            observe?.invoke(it)
        }.also { connections += it }
    private fun endpoint(space: String = " Game ", prefix: String = "/proxy") = RemoteEndpoint.create(server.url(prefix).toString(), space)
    private fun enqueueMe(body: String = me) { server.enqueue(MockResponse().setBody(body).setHeader("Content-Type", "application/json")) }
    private fun enqueueSocket(first: String? = hello, after: (WebSocket) -> Unit = {}) {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (first != null) webSocket.send(first)
                after(webSocket)
            }
            override fun onMessage(webSocket: WebSocket, text: String) { frames.offer(text) }
        }))
    }
    private fun await(predicate: (ConnectionEvent) -> Boolean): ConnectionEvent {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val event = events.poll(100, TimeUnit.MILLISECONDS) ?: continue
            if (predicate(event)) return event
        }
        fail("Missing expected transport event; received $history")
        error("unreachable")
    }
    private fun ready() = await { it is ConnectionEvent.Frame && it.frame is ServerFrame.Hello }
    private fun stopped(problem: ConnectionProblem) = await {
        it is ConnectionEvent.State && it.phase == ConnectionPhase.STOPPED && it.problem == problem
    }
    private fun send(c: RemoteConnection, generation: Long, frame: ClientFrame): SendResult {
        val queue = LinkedBlockingQueue<SendResult>()
        c.send(generation, frame) { queue.offer(it) }
        return queue.poll(3, TimeUnit.SECONDS) ?: error("send callback missing")
    }

    @Test fun bearerIsHeaderOnlyAndPrefixSpaceArePreserved() {
        enqueueMe(); enqueueSocket()
        val c = connection(); val generation = c.start(endpoint(), key)
        ready()
        val http = server.takeRequest(2, TimeUnit.SECONDS)!!
        val ws = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/proxy/api/v2/me", http.path)
        assertEquals("/proxy/api/v2/sync?space=game", ws.path)
        for (request in listOf(http, ws)) {
            assertEquals("Bearer $key", request.getHeader("Authorization"))
            assertFalse(request.requestUrl.toString().contains(key))
        }
        assertEquals(SendResult.ENQUEUED, send(c, generation, ClientFrame.Sync(0)))
        assertEquals("sync", JSONObject(frames.poll(2, TimeUnit.SECONDS)!!).getString("t"))
        assertTrue(history.none { it.toString().contains(key) })
    }
    @Test fun http401StopsWithoutOpeningSocket() {
        server.enqueue(MockResponse().setResponseCode(401).setBody(key))
        val c = connection(); c.start(endpoint(), key)
        stopped(ConnectionProblem.HTTP_UNAUTHORIZED)
        assertEquals(1, server.requestCount)
        assertFalse(history.toString().contains(key))
    }
    @Test fun meVersionMismatchNeverOpensOrWrites() {
        enqueueMe(me.replace("\"protocol\":2", "\"protocol\":1"))
        val c = connection(); val generation = c.start(endpoint(), key)
        stopped(ConnectionProblem.PROTOCOL_MISMATCH)
        assertNotEquals(SendResult.ENQUEUED, send(c, generation, ClientFrame.Sync(0)))
        assertEquals(1, server.requestCount)
    }
    @Test fun helloVersionMismatchNeverWrites() {
        enqueueMe(); enqueueSocket(hello.replace("\"protocol\":2", "\"protocol\":1"))
        val c = connection(); val generation = c.start(endpoint(), key)
        stopped(ConnectionProblem.PROTOCOL_MISMATCH)
        assertNotEquals(SendResult.ENQUEUED, send(c, generation, ClientFrame.Put(1, "a.md", "text", 0)))
        assertNull(frames.poll(150, TimeUnit.MILLISECONDS))
        assertTrue(history.none { it is ConnectionEvent.State && it.phase == ConnectionPhase.READY })
    }
    @Test fun noSendUntilHelloAndHelloTimeoutReconnects() {
        enqueueMe(); enqueueSocket(null)
        enqueueMe(); enqueueSocket()
        val c = connection(helloMillis = 150); val generation = c.start(endpoint(), key)
        await { it is ConnectionEvent.State && it.phase == ConnectionPhase.AWAITING_HELLO }
        assertEquals(SendResult.NOT_READY, send(c, generation, ClientFrame.Sync(0)))
        await { it is ConnectionEvent.State && it.problem == ConnectionProblem.HELLO_TIMEOUT }
        val next = ready()
        assertTrue(next.generation > generation)
        assertEquals(SendResult.STALE_GENERATION, send(c, generation, ClientFrame.Sync(0)))
    }
    @Test fun badSpaceAndKeyRevocationHaveDistinctTerminalStates() {
        for ((code, problem) in listOf(4400 to ConnectionProblem.BAD_CONFIGURATION, 4401 to ConnectionProblem.KEY_REJECTED, 4404 to ConnectionProblem.PROJECT_NOT_FOUND)) {
            enqueueMe(); enqueueSocket(null) { it.close(code, key) }
            val c = connection(); c.start(endpoint(), key)
            stopped(problem)
            c.disconnect()
        }
        assertEquals(6, server.requestCount)
        assertFalse(history.toString().contains(key))
    }
    @Test fun overloadedReconnectsWithNewGenerationAndDoesNotReplay() {
        enqueueMe(); enqueueSocket(hello) { it.close(4408, "Too far behind") }
        enqueueMe(); enqueueSocket()
        val c = connection(); val generation = c.start(endpoint(), key)
        await { it is ConnectionEvent.State && it.phase == ConnectionPhase.BACKOFF && it.problem == ConnectionProblem.TOO_FAR_BEHIND }
        val next = ready()
        // ready() may see the first hello if it was not consumed before BACKOFF? await consumes it.
        assertTrue(next.generation > generation)
        assertNull(frames.poll(150, TimeUnit.MILLISECONDS))
    }
    @Test fun inboundFramesAreDeliveredInWireOrder() {
        enqueueMe(); enqueueSocket(hello) { socket ->
            for (seq in 1..50) socket.send("""{"t":"synced","seq":$seq}""")
        }
        val threadNames = CopyOnWriteArrayList<String>()
        val c = connection(observe = { threadNames += Thread.currentThread().name })
        c.start(endpoint(), key)
        await { it is ConnectionEvent.Frame && (it.frame as? ServerFrame.Synced)?.seq == 50L }
        val seqs = history.filterIsInstance<ConnectionEvent.Frame>().mapNotNull { (it.frame as? ServerFrame.Synced)?.seq }
        assertEquals((1L..50L).toList(), seqs)
        assertEquals(1, threadNames.distinct().size)
    }
    @Test fun profileSwitchDiscardsDelayedHttpCallbackAndOldSend() {
        server.enqueue(MockResponse().setBody(me).setBodyDelay(400, TimeUnit.MILLISECONDS))
        enqueueMe(); enqueueSocket(hello.replace("\"id\":\"game\"", "\"id\":\"new\""))
        val c = connection(); val old = c.start(endpoint("old"), key)
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        val fresh = c.start(endpoint("new"), key)
        ready()
        assertEquals(SendResult.STALE_GENERATION, send(c, old, ClientFrame.Sync(0)))
        assertEquals(SendResult.ENQUEUED, send(c, fresh, ClientFrame.Sync(0)))
        assertEquals("/proxy/api/v2/me", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
        assertEquals("/proxy/api/v2/sync?space=new", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
        assertTrue(history.filterIsInstance<ConnectionEvent.Frame>().all { it.generation == fresh })
    }
    @Test fun queuedOldSocketCallbacksCannotCrossGenerationFence() {
        val fence = CountDownLatch(1); val release = CountDownLatch(1)
        enqueueMe(); enqueueSocket(hello) { it.send("""{"t":"synced","seq":91}""") }
        enqueueMe(); enqueueSocket(hello.replace("\"id\":\"game\"", "\"id\":\"new\""))
        var first = true
        val c = connection(observe = {
            if (first && it is ConnectionEvent.Frame && it.frame is ServerFrame.Hello) {
                first = false; fence.countDown(); release.await(3, TimeUnit.SECONDS)
            }
        })
        val old = c.start(endpoint(), key)
        assertTrue(fence.await(3, TimeUnit.SECONDS))
        val fresh = c.start(endpoint("new"), key)
        release.countDown()
        await { it is ConnectionEvent.Frame && it.generation == fresh && it.frame is ServerFrame.Hello }
        assertTrue(history.none { it is ConnectionEvent.Frame && it.generation == old && it.frame is ServerFrame.Synced })
    }
    @Test fun binaryAndPreHelloFramesAreRejected() {
        enqueueMe(); enqueueSocket("""{"t":"synced","seq":0}""")
        val c = connection(); c.start(endpoint(), key)
        stopped(ConnectionProblem.INVALID_FRAME)
        c.disconnect()
        enqueueMe(); enqueueSocket(null) { it.send(okio.ByteString.of(1, 2, 3)) }
        c.start(endpoint(), key)
        stopped(ConnectionProblem.INVALID_FRAME)
    }
    @Test fun websocketHttp401IsNotNetworkRetry() {
        enqueueMe(); server.enqueue(MockResponse().setResponseCode(401))
        val c = connection(); c.start(endpoint(), key)
        stopped(ConnectionProblem.HTTP_UNAUTHORIZED)
        assertEquals(2, server.requestCount)
    }
    @Test fun redirectDoesNotForwardBearer() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/steal")))
        val c = connection(); c.start(endpoint(), key)
        stopped(ConnectionProblem.HTTP_ERROR)
        assertEquals(1, server.requestCount)
    }
    @Test fun untrustedCertificateFailsWithoutTrustAll() {
        val c = RemoteConnection(RemoteConnection.defaultClient(), ConnectionTiming(), { events.offer(it) }).also { connections += it }
        c.start(endpoint(), key)
        stopped(ConnectionProblem.TLS)
        assertEquals(0, server.requestCount)
    }
    @Test fun mockTrustUsesVerifiedHostname() {
        server.enqueue(MockResponse().setBody("ok"))
        client.newCall(okhttp3.Request.Builder().url(server.url("/")).build()).execute().use {
            assertEquals(200, it.code)
        }
    }
    @Test fun openTimeoutIsBounded() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val c = connection(openMillis = 100); c.start(endpoint(), key)
        await { it is ConnectionEvent.State && it.problem == ConnectionProblem.OPEN_TIMEOUT && it.phase == ConnectionPhase.BACKOFF }
        c.disconnect()
    }
    @Test fun writeOrderMatchesSerializedSubmissionOrder() {
        enqueueMe(); enqueueSocket()
        val c = connection(); val id = c.start(endpoint(), key); ready()
        assertEquals(SendResult.ENQUEUED, send(c, id, ClientFrame.Update("sid", "AQ==")))
        assertEquals(SendResult.ENQUEUED, send(c, id, ClientFrame.Put(1, "doc.md", "text", 0, "sid")))
        assertEquals("update", JSONObject(frames.poll(2, TimeUnit.SECONDS)!!).getString("t"))
        assertEquals("put", JSONObject(frames.poll(2, TimeUnit.SECONDS)!!).getString("t"))
    }
    @Test fun serverErrorsRemainCorrelatedFramesNotImplicitRetries() {
        enqueueMe(); enqueueSocket(hello) {
            it.send("""{"t":"error","message":"invalid payload","of":"put","id":12,"path":"a.md"}""")
        }
        val c = connection(); val generation = c.start(endpoint(), key)
        val event = await { it is ConnectionEvent.Frame && it.frame is ServerFrame.Error } as ConnectionEvent.Frame
        val error = event.frame as ServerFrame.Error
        assertEquals(FrameId.Integer(12), error.id)
        assertEquals("put", error.of)
        assertEquals(SendResult.ENQUEUED, send(c, generation, ClientFrame.Sync(0)))
        assertTrue(history.none { it is ConnectionEvent.State && it.phase == ConnectionPhase.BACKOFF })
    }
    @Test fun interruptedMeBodyIsNetworkNotMalformedProtocol() {
        server.enqueue(MockResponse().setBody(me).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        enqueueMe(); enqueueSocket()
        val c = connection(); val generation = c.start(endpoint(), key)
        await { it is ConnectionEvent.State && it.phase == ConnectionPhase.BACKOFF && it.problem == ConnectionProblem.NETWORK }
        assertTrue(ready().generation > generation)
    }
    @Test fun consecutiveFailuresExponentiallyBackOffAndClampWithJitter() {
        repeat(4) { server.enqueue(MockResponse().setResponseCode(503)) }
        val delays = mutableListOf<Long>()
        val c = RemoteConnection(client, ConnectionTiming(2000, 1000, 20, 80, { it / 2 })) {
            history += it; events.offer(it)
        }.also { connections += it }
        c.start(endpoint(), key)
        repeat(4) {
            val event = await { it is ConnectionEvent.State && it.phase == ConnectionPhase.BACKOFF } as ConnectionEvent.State
            delays += event.retryInMillis!!
        }
        c.disconnect()
        assertEquals(listOf(30L, 60L, 120L, 120L), delays)
        val generations = history.filterIsInstance<ConnectionEvent.State>().filter { it.phase == ConnectionPhase.BACKOFF }.map { it.generation }
        assertEquals(generations.distinct().size, generations.size)
    }
    @Test fun consumerExceptionFailsClosedInsteadOfLosingAFrame() {
        enqueueMe(); enqueueSocket()
        val c = connection(observe = { if (it is ConnectionEvent.Frame) error("consumer failed") })
        c.start(endpoint(), key)
        stopped(ConnectionProblem.CONSUMER_FAILED)
    }
    @Test fun sendCloseRaceAlwaysCompletesItsCallback() {
        repeat(30) {
            val c = connection()
            val replies = LinkedBlockingQueue<SendResult>()
            val sender = Thread { c.send(0, ClientFrame.Sync(0)) { replies.offer(it) } }
            sender.start(); c.close(); sender.join(1000)
            assertNotNull(replies.poll(2, TimeUnit.SECONDS))
            assertNull(replies.poll())
        }
    }
    @Test fun legacyConnectCannotMakeAnyNetworkRequest() {
        val c=connection()
        c.connect(endpoint(),key)
        stopped(ConnectionProblem.BAD_CONFIGURATION)
        assertNull(server.takeRequest(100,TimeUnit.MILLISECONDS))
    }
    @Test fun freshMeCannotChangeTheExpectedAccount() {
        enqueueMe(me.replace("\"id\":\"u\"","\"id\":\"other\""))
        val c=connection(); val generation=c.start(endpoint(),key)
        stopped(ConnectionProblem.IDENTITY_MISMATCH)
        assertEquals(1,server.requestCount)
        assertNotEquals(SendResult.ENQUEUED,send(c,generation,ClientFrame.Sync(0)))
    }
    @Test fun helloIdentityMismatchStopsBeforeReadyOrAnyEffects() {
        val variants=listOf(
            hello.replace("\"id\":\"u\"","\"id\":\"other\""),
            hello.replace("\"id\":\"game\"","\"id\":\"other\""),
            hello.replace(epoch,"b".repeat(32)),
        )
        for(wire in variants) {
            history.clear()
            enqueueMe();enqueueSocket(wire)
            val c=connection();val generation=c.start(endpoint(),key)
            stopped(ConnectionProblem.IDENTITY_MISMATCH)
            assertTrue(history.none { it is ConnectionEvent.State && it.phase == ConnectionPhase.READY })
            assertTrue(history.none { it is ConnectionEvent.Frame })
            assertNotEquals(SendResult.ENQUEUED,send(c,generation,ClientFrame.Put(1,"a.md","blocked",0)))
        }
        assertNull(frames.poll(100,TimeUnit.MILLISECONDS))
        assertEquals(6,server.requestCount)
    }
    @Test fun labelRenameDoesNotChangeIdentityAndV2PresenceIsDelivered() {
        enqueueMe();enqueueSocket(hello.replace("Game label","Different label")) {
            it.send("""{"t":"presence","peers":[{"user":{"id":"u","display_name":"New label","color":"#123456"},"connections":1,"docs":["a.md"]}]}""")
        }
        val c=connection();c.start(endpoint(),key)
        val event=await { it is ConnectionEvent.Frame && it.frame is ServerFrame.Presence } as ConnectionEvent.Frame
        assertEquals("New label",(event.frame as ServerFrame.Presence).peers.single().user.displayName)
        assertTrue(history.any { it is ConnectionEvent.State && it.phase == ConnectionPhase.READY })
    }
    @Test fun malformedUtf8MeFailsClosedBeforeSocket() {
        server.enqueue(MockResponse().setBody(okio.Buffer().write(byteArrayOf(0xc3.toByte(),0x28))))
        val c=connection();c.start(endpoint(),key)
        stopped(ConnectionProblem.INVALID_FRAME)
        assertEquals(1,server.requestCount)
    }
    @Test fun retryKeepsOriginalExpectedIdentityRatherThanAdoptingFreshMeAccount() {
        enqueueMe();enqueueSocket(hello) { it.close(4408,"overloaded") }
        enqueueMe(me.replace("\"id\":\"u\"","\"id\":\"other\""))
        val c=connection();val first=c.start(endpoint(),key)
        val stop=stopped(ConnectionProblem.IDENTITY_MISMATCH)
        assertTrue(stop.generation > first)
        assertEquals(3,server.requestCount)
        assertNull(frames.poll(100,TimeUnit.MILLISECONDS))
    }

}
