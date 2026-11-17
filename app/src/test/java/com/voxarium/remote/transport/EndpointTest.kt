package com.voxarium.remote.transport

import com.voxarium.remote.data.transport.*
import org.junit.Assert.*
import org.junit.Test

class EndpointTest {
    @Test fun copyCannotBypassFactory() {
        val copies=RemoteEndpoint::class.java.declaredMethods.filter { it.name == "copy" }
        assertFalse(copies.isEmpty())
        assertTrue(copies.none { java.lang.reflect.Modifier.isPublic(it.modifiers) })
    }
    @Test fun canonicalTlsAndPrefix() {
        val e=RemoteEndpoint.create("  WSS://EXAMPLE.COM:443/proxy/remote///  "," Game_1 ")
        assertEquals("https://example.com/proxy/remote",e.serverUrl)
        assertEquals("game_1",e.space)
        assertEquals("https://example.com/proxy/remote/api/v1/me",e.meUrl().toString())
        assertEquals("https://example.com/proxy/remote/api/v1/sync?space=game_1",e.syncUrl().toString())
        assertEquals("https://example.com",RemoteEndpoint.create("Example.COM/","a").serverUrl)
        assertEquals("https://example.com:8443/p",RemoteEndpoint.create("example.com:8443/p","a").serverUrl)
    }
    @Test fun rejectInsecureAndAmbiguousUrls() {
        listOf("http://example.com","ws://example.com","ftp://example.com","https://u:p@example.com","https://@example.com","https://example.com?token=x","https://example.com?","https://example.com#x","https://example.com#","https://exam ple.com","https://example.com\\foo","").forEach { u ->
            assertThrows("URL rejected",IllegalArgumentException::class.java) { RemoteEndpoint.create(u,"a") }
        }
    }
    @Test fun urlOnlyEndpointPreservesPrefixWithoutInventingSpace() {
        val e = RemoteEndpoint.create(" WSS://Example.com:443/proxy/remote/// ")
        assertEquals("https://example.com/proxy/remote", e.serverUrl)
        assertEquals("", e.space)
        assertEquals("https://example.com/proxy/remote/healthz", e.healthUrl().toString())
        assertEquals("https://example.com/proxy/remote/api/v2/me", e.v2MeUrl().toString())
        assertEquals("https://example.com/proxy/remote/api/v2/projects/exact-id", e.projectUrl("exact-id").toString())
        assertEquals("https://example.com/proxy/remote/api/v2/sync?space=exact-id", e.v2SyncUrl("exact-id").toString())
        assertThrows(IllegalArgumentException::class.java) { e.syncUrl() }
        for (id in listOf(" Exact ", "EXACT", "%61", "a/b")) {
            assertThrows(IllegalArgumentException::class.java) { e.projectUrl(id) }
            assertThrows(IllegalArgumentException::class.java) { e.v2SyncUrl(id) }
        }
    }
    @Test fun asciiSpaceValidation() {
        listOf(""," ",".a","-a","_a","a/b","a b","абв","a?","a".repeat(65)).forEach { s ->
            assertThrows(IllegalArgumentException::class.java) { RemoteEndpoint.create("example.com",s) }
        }
        assertEquals("a".repeat(64),RemoteEndpoint.create("example.com","A".repeat(64)).space)
    }
}

