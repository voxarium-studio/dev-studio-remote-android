package com.voxarium.remote.transport

import com.voxarium.remote.data.transport.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

internal class CatalogHttpFixture : Closeable {
    val server = MockWebServer()
    val client: OkHttpClient
    val key = "vxs_synthetic_catalog_only"
    val health = """{"service":"voxarium_studio_remote","protocol":2,"supported_protocols":[2],"catalog_api":2}"""
    val me = """{"protocol":2,"catalog_api":2,"capabilities":["project_catalog","explicit_project_create","strict_connect","legacy_resolution","project_snapshot"],"user":{"id":"account-a","display_name":"Анна","color":"#abcdef"}}"""
    init {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        server.useHttps(tls.sslSocketFactory(), false)
        server.start()
        client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
    }
    fun identity(revision: Long = 1, selection: Long = 1) = ServerRequestIdentity("profile", RemoteEndpoint.create(server.url("/proxy/remote").newBuilder().host("localhost").build().toString()), "credential", revision, selection)
    fun verified(identity: ServerRequestIdentity = identity()): VerifiedServer = ProjectCatalogCodec.decodeV2Me(me, identity)
    fun enqueue(body: String, code: Int = 200) { server.enqueue(MockResponse().setResponseCode(code).setBody(body)) }
    fun project(id: String = "game", name: String = "Игра") = """{"id":"$id","display_name":"$name","storage_epoch":"0123456789abcdef0123456789abcdef","metadata_revision":1,"created_at":"2026-11-16T00:00:00+00:00"}"""
    fun projectReply(id: String = "game", name: String = "Игра") = """{"protocol":2,"project":${project(id, name)}}"""
    fun error(code: String) = """{"error":{"code":"$code","message":"raw-secret-canary","request_id":"not-for-ui"}}"""
    override fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        server.shutdown()
    }
}

class ServerVerifierTest {
    @Test fun boundedHttpOnlyVerificationPreservesPrefixAndNeverSendsSelector(): Unit = CatalogHttpFixture().use { f ->
        f.enqueue(f.health); f.enqueue(f.me)
        ServerVerifier(f.client).use { verifier ->
            val results = LinkedBlockingQueue<VerificationReply>()
            val id = verifier.verify(f.identity(), f.key) { results.offer(it) }
            val reply = results.poll(3, TimeUnit.SECONDS)!!
            assertEquals(id, reply.generation)
            val server = (reply.result as VerificationResult.Verified).server
            assertTrue(server.catalogSupported)
            assertEquals("account-a", server.user.id)
            assertEquals("Анна", server.user.name)
            val health = f.server.takeRequest(1, TimeUnit.SECONDS)!!
            val me = f.server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("/proxy/remote/healthz", health.path)
            assertNull(health.getHeader("Authorization"))
            assertEquals("/proxy/remote/api/v2/me", me.path)
            assertEquals("Bearer ${f.key}", me.getHeader("Authorization"))
            assertNull(me.getHeader("Upgrade")); assertNull(me.getHeader("Cookie"))
            assertEquals(2, f.server.requestCount)
            assertFalse(reply.toString().contains(f.key))
        }
    }
    @Test fun oldServerAuthenticatesButCatalogIsUnsupportedWithoutV2Guessing(): Unit = CatalogHttpFixture().use { f ->
        f.enqueue("""{"service":"voxarium_studio_remote","protocol":1}""")
        f.enqueue("""{"protocol":1,"user":{"id":"old","name":"Old","color":"#123456"}}""")
        ServerVerifier(f.client).use { verifier ->
            val q = LinkedBlockingQueue<VerificationReply>()
            verifier.verify(f.identity(), f.key) { q.offer(it) }
            val server = ((q.poll(3, TimeUnit.SECONDS)!!.result) as VerificationResult.Verified).server
            assertFalse(server.catalogSupported); assertEquals(1L, server.protocol)
            assertTrue(f.server.takeRequest().path!!.endsWith("/healthz"))
            assertTrue(f.server.takeRequest().path!!.endsWith("/api/v1/me"))
            assertEquals(2, f.server.requestCount)
        }
    }
    @Test fun redirectsAreNotFollowedAndNeverForwardBearer(): Unit = CatalogHttpFixture().use { f ->
        f.enqueue(f.health)
        f.server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", f.server.url("/stolen")))
        ServerVerifier(f.client).use { verifier ->
            val q = LinkedBlockingQueue<VerificationReply>()
            verifier.verify(f.identity(), f.key) { q.offer(it) }
            assertEquals(ServerProblem.REDIRECT, (q.poll(3, TimeUnit.SECONDS)!!.result as VerificationResult.Failed).problem)
            assertEquals(2, f.server.requestCount)
        }
    }
    @Test fun unauthorizedAndMalformedAreNotOldServerFallback(): Unit = CatalogHttpFixture().use { f ->
        ServerVerifier(f.client).use { verifier ->
            for ((body, code, expected) in listOf(
                Triple(f.error("unauthorised"), 401, ServerProblem.UNAUTHORIZED),
                Triple(f.me.replace("\"protocol\":2", "\"protocol\":1"), 200, ServerProblem.INVALID_RESPONSE),
                Triple(f.me.replace("\"strict_connect\"", "\"wrong\""), 200, ServerProblem.INVALID_RESPONSE),
                Triple("x".repeat(65_537), 200, ServerProblem.INVALID_RESPONSE),
                Triple(f.me + " junk", 200, ServerProblem.INVALID_RESPONSE),
            )) {
                f.enqueue(f.health); f.enqueue(body, code)
                val q = LinkedBlockingQueue<VerificationReply>()
                verifier.verify(f.identity(), f.key) { q.offer(it) }
                assertEquals(expected, (q.poll(3, TimeUnit.SECONDS)!!.result as VerificationResult.Failed).problem)
            }
            assertEquals(10, f.server.requestCount)
        }
    }
    @Test fun malformedUtf8IsInvalidResponseNotNetworkOrLegacy(): Unit = CatalogHttpFixture().use { f ->
        ServerVerifier(f.client).use { verifier ->
            for (bytes in listOf(byteArrayOf(0xc3.toByte(), 0x28),
                    byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()))) {
                f.enqueue(f.health)
                f.server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
                val q = LinkedBlockingQueue<VerificationReply>()
                verifier.verify(f.identity(), f.key) { q.offer(it) }
                assertEquals(ServerProblem.INVALID_RESPONSE,
                    (q.poll(3, TimeUnit.SECONDS)!!.result as VerificationResult.Failed).problem)
            }
            assertEquals(4, f.server.requestCount) // No fallback or retry after decoding failure.
        }
    }
    @Test fun unknownHealthVersionIsUnsupportedNotLegacy(): Unit = CatalogHttpFixture().use { f ->
        f.enqueue("""{"service":"voxarium_studio_remote","protocol":99}""")
        ServerVerifier(f.client).use { verifier ->
            val q = LinkedBlockingQueue<VerificationReply>()
            verifier.verify(f.identity(), f.key) { q.offer(it) }
            assertEquals(ServerProblem.UNSUPPORTED, (q.poll(3, TimeUnit.SECONDS)!!.result as VerificationResult.Failed).problem)
            assertEquals(1, f.server.requestCount)
        }
    }
    @Test fun timeoutIsBoundedAndNotRetried(): Unit = CatalogHttpFixture().use { f ->
        f.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        ServerVerifier(f.client, 200).use { verifier ->
            val q = LinkedBlockingQueue<VerificationReply>()
            verifier.verify(f.identity(), f.key) { q.offer(it) }
            assertEquals(ServerProblem.TIMEOUT, (q.poll(3, TimeUnit.SECONDS)!!.result as VerificationResult.Failed).problem)
            assertEquals(1, f.server.requestCount)
        }
    }
    @Test fun cancellationAndSameCredentialIdNewRevisionFenceLateMe(): Unit = CatalogHttpFixture().use { f ->
        f.enqueue(f.health)
        f.server.enqueue(MockResponse().setBody(f.me).setBodyDelay(400, TimeUnit.MILLISECONDS))
        ServerVerifier(f.client).use { verifier ->
            val q = LinkedBlockingQueue<VerificationReply>()
            verifier.verify(f.identity(1), f.key) { q.offer(it) }
            f.server.takeRequest(2, TimeUnit.SECONDS)!!
            f.server.takeRequest(2, TimeUnit.SECONDS)!!
            f.enqueue(f.health); f.enqueue(f.me.replace("account-a", "account-b"))
            verifier.verify(f.identity(2), "vxs_second_synthetic") { q.offer(it) }
            val reply = q.poll(3, TimeUnit.SECONDS)!!
            assertEquals(2L, reply.identity.credentialRevision)
            assertEquals("account-b", (reply.result as VerificationResult.Verified).server.user.id)
            assertNull(q.poll(600, TimeUnit.MILLISECONDS))
            verifier.invalidate()
            assertEquals(4, f.server.requestCount)
        }
    }
    @Test fun get503RetryAfterZeroDoesNotRetryDiscovery(): Unit = CatalogHttpFixture().use { f ->
        f.server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
        f.enqueue(f.health)
        ServerVerifier(f.client).use { verifier ->
            val q = LinkedBlockingQueue<VerificationReply>()
            verifier.verify(f.identity(), f.key) { q.offer(it) }
            val reply = q.poll(3, TimeUnit.SECONDS)!!
            assertEquals(ServerProblem.HTTP_ERROR, (reply.result as VerificationResult.Failed).problem)
            assertTrue(verifier.isCurrent(reply))
            verifier.invalidate()
            assertFalse(verifier.isCurrent(reply))
            assertEquals(1, f.server.requestCount)
        }
    }
    @Test fun systemTrustAndHostnameValidationAreNotBypassed(): Unit = CatalogHttpFixture().use { f ->
        for (client in listOf(OkHttpClient(), f.client)) {
            ServerVerifier(client).use { verifier ->
                val identity = if (client === f.client) f.identity().copy(endpoint = RemoteEndpoint.create(
                    f.server.url("/proxy").newBuilder().host("127.0.0.1").build().toString())) else f.identity()
                val q = LinkedBlockingQueue<VerificationReply>()
                verifier.verify(identity, f.key) { q.offer(it) }
                assertEquals(ServerProblem.TLS, (q.poll(3, TimeUnit.SECONDS)!!.result as VerificationResult.Failed).problem)
            }
            if (client !== f.client) { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        }
        assertEquals(0, f.server.requestCount)
    }
}



