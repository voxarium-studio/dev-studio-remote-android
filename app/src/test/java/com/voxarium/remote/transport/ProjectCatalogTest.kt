package com.voxarium.remote.transport

import com.voxarium.remote.data.transport.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class ProjectCatalogTest {
    private fun <T> result(start: ((CatalogReply<T>) -> Unit) -> Long): CatalogReply<T> {
        val q = LinkedBlockingQueue<CatalogReply<T>>()
        start { q.offer(it) }
        return q.poll(3, TimeUnit.SECONDS) ?: error("Missing catalog result")
    }
    @Test fun listGetLegacyAndSelectAreReadOnlyExactPrefixPreservingRequests(): Unit = CatalogHttpFixture().use { f ->
        ProjectCatalog(f.client).use { catalog ->
            val scope = catalog.select(f.verified(), f.key)
            assertEquals(0, f.server.requestCount)
            f.enqueue("""{"protocol":2,"projects":[${f.project("a")},${f.project("b")}],"next_after":"b"}""")
            val page = result<ProjectPage> { catalog.listProjects(scope, 2, callback = it) }
            assertEquals(listOf("a", "b"), (page.result as CatalogResult.Success).value.projects.map { it.id })
            var request = f.server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("GET", request.method)
            assertEquals("/proxy/remote/api/v2/projects?limit=2", request.path)
            assertEquals("Bearer ${f.key}", request.getHeader("Authorization"))
            assertNull(request.getHeader("Cookie"))
            f.enqueue("""{"protocol":2,"projects":[${f.project("c")}],"next_after":null}""")
            assertTrue(result<ProjectPage> { catalog.listProjects(scope, 2, "b", it) }.result is CatalogResult.Success)
            assertEquals("/proxy/remote/api/v2/projects?limit=2&after=b", f.server.takeRequest().path)
            f.enqueue(f.projectReply("a"))
            val got = result<ProjectSummary> { catalog.getProject(scope, "a", it) }
            assertEquals("a", (got.result as CatalogResult.Success).value.id)
            assertEquals("/proxy/remote/api/v2/projects/a", f.server.takeRequest().path)
            val exact = " Legacy /%é "
            f.enqueue(JSONObject().put("protocol", 2).put("legacy_space_name", exact)
                .put("project", JSONObject(f.project("a"))).toString())
            assertTrue(result<ProjectSummary> { catalog.resolveLegacy(scope, exact, it) }.result is CatalogResult.Success)
            request = f.server.takeRequest()
            assertEquals(exact, request.requestUrl!!.queryParameter("name"))
            assertEquals("/proxy/remote/api/v2/legacy-spaces/resolve", request.requestUrl!!.encodedPath)
            assertEquals(4, f.server.requestCount)
        }
    }
    @Test fun explicitCreatePostsOnlyIdAndNormalizedLabelAndDoesNotBind(): Unit = CatalogHttpFixture().use { f ->
        ProjectCatalog(f.client).use { catalog ->
            val scope = catalog.select(f.verified(), f.key)
            f.enqueue(f.projectReply("exact-id", "Игра é"), 201)
            val reply = result<ProjectSummary> { catalog.createProject(scope, "exact-id", "  Игра e\u0301  ", it) }
            assertEquals("exact-id", (reply.result as CatalogResult.Success).value.id)
            assertTrue(catalog.isCurrent(scope))
            val request = f.server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("POST", request.method)
            assertEquals("/proxy/remote/api/v2/projects", request.path)
            val body = JSONObject(request.body.readUtf8())
            assertEquals(setOf("id", "display_name"), body.keys().asSequence().toSet())
            assertEquals("exact-id", body.getString("id")); assertEquals("Игра é", body.getString("display_name"))
            assertTrue(catalog.uncertainCreates(scope).isEmpty())
            assertEquals(1, f.server.requestCount)
        }
    }
    @Test fun invalidExactIdsNeverNormalizeAndNeverSend(): Unit = CatalogHttpFixture().use { f ->
        ProjectCatalog(f.client).use { catalog ->
            val scope = catalog.select(f.verified(), f.key)
            for (id in listOf(" A ", "A", "а", "a/b", "%61", "", "a".repeat(65))) {
                val create = result<ProjectSummary> { catalog.createProject(scope, id, "Label", it) }
                assertEquals(ServerProblem.INVALID_INPUT, (create.result as CatalogResult.Failed).problem)
                assertEquals(ServerProblem.INVALID_INPUT, (result<ProjectSummary> { catalog.getProject(scope, id, it) }.result as CatalogResult.Failed).problem)
            }
            assertEquals(0, f.server.requestCount)
        }
    }
    @Test fun unknownCreateRequiresGetOriginalIdThenExplicitCreateAfterMissingNeverReplays(): Unit = CatalogHttpFixture().use { f ->
        ProjectCatalog(f.client).use { catalog ->
            val scope = catalog.select(f.verified(), f.key)
            f.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            val first = result<ProjectSummary> { catalog.createProject(scope, "original", "Label", it) }
            assertEquals("original", (first.result as CatalogResult.CreateUncertain).originalId)
            assertTrue(result<ProjectSummary> { catalog.createProject(scope, "original", "Label", it) }.result is CatalogResult.CreateUncertain)
            assertEquals(1, f.server.requestCount)
            f.enqueue(f.error("project_not_found"), 404)
            assertEquals(ServerProblem.PROJECT_NOT_FOUND,
                (result<ProjectSummary> { catalog.getProject(scope, "original", it) }.result as CatalogResult.Failed).problem)
            assertTrue(catalog.uncertainCreates(scope).isEmpty())
            assertEquals(2, f.server.requestCount) // GET is not a retry or Select.
            f.enqueue(f.projectReply("original", "Label"), 201)
            assertTrue(result<ProjectSummary> { catalog.createProject(scope, "original", "Label", it) }.result is CatalogResult.Success)
            assertEquals(listOf("POST", "GET", "POST"), (1..3).map { f.server.takeRequest().method })
        }
    }
    @Test fun foundRaceWinnerIsInformationNotAutomaticSelectionOrOurCreate(): Unit = CatalogHttpFixture().use { f ->
        ProjectCatalog(f.client).use { catalog ->
            val scope = catalog.select(f.verified(), f.key)
            f.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            result<ProjectSummary> { catalog.createProject(scope, "original", "Our name", it) }
            f.enqueue(f.projectReply("original", "Other winner"))
            val found = result<ProjectSummary> { catalog.getProject(scope, "original", it) }
            assertEquals("Other winner", (found.result as CatalogResult.Success).value.displayName)
            assertEquals("Other winner", catalog.uncertainCreates(scope).single().observedProject!!.displayName)
            assertTrue(result<ProjectSummary> { catalog.createProject(scope, "original", "Our name", it) }.result is CatalogResult.CreateUncertain)
            assertTrue(catalog.isCurrent(scope)); assertEquals(2, f.server.requestCount)
        }
    }
    @Test fun cancellationFencesCreateButRetainsOriginalIdAcrossReselection(): Unit = CatalogHttpFixture().use { f ->
        ProjectCatalog(f.client).use { catalog ->
            val verified = f.verified()
            val old = catalog.select(verified, f.key)
            f.server.enqueue(MockResponse().setResponseCode(201).setBody(f.projectReply()).setBodyDelay(400, TimeUnit.MILLISECONDS))
            val q = LinkedBlockingQueue<CatalogReply<ProjectSummary>>()
            catalog.createProject(old, "game", "Игра") { q.offer(it) }
            f.server.takeRequest(2, TimeUnit.SECONDS)!!
            val fresh = catalog.select(verified, f.key)
            assertFalse(catalog.isCurrent(old)); assertTrue(catalog.isCurrent(fresh))
            assertEquals("game", catalog.uncertainCreates(fresh).single().id)
            assertTrue(result<ProjectSummary> { catalog.createProject(fresh, "game", "Игра", it) }.result is CatalogResult.CreateUncertain)
            assertNull(q.poll(600, TimeUnit.MILLISECONDS))
            assertEquals(1, f.server.requestCount)
        }
    }
    @Test fun lateListAndGetCannotCrossProfileCredentialRevisionOrCancel(): Unit = CatalogHttpFixture().use { f ->
        ProjectCatalog(f.client).use { catalog ->
            val old = catalog.select(f.verified(), f.key)
            f.server.enqueue(MockResponse().setBody(f.projectReply()).setBodyDelay(400, TimeUnit.MILLISECONDS))
            val q = LinkedBlockingQueue<CatalogReply<ProjectSummary>>()
            val rid = catalog.getProject(old, "game") { q.offer(it) }
            f.server.takeRequest(2, TimeUnit.SECONDS)!!
            catalog.cancelRequest(old, rid)
            val fresh = catalog.select(f.verified(f.identity(2)), "vxs_rotated_synthetic")
            f.enqueue(f.projectReply("next"))
            val reply = result<ProjectSummary> { catalog.getProject(fresh, "next", it) }
            assertEquals(2L, reply.scope.server.identity.credentialRevision)
            assertNull(q.poll(600, TimeUnit.MILLISECONDS))
            assertEquals(ServerProblem.STALE_REQUEST, (result<ProjectSummary> { catalog.getProject(old, "game", it) }.result as CatalogResult.Failed).problem)
            assertEquals(2, f.server.requestCount)
        }
    }
    @Test fun conflictAndErrorEnvelopeNeverExposeRawMessagesOrFallback(): Unit = CatalogHttpFixture().use { f ->
        ProjectCatalog(f.client).use { catalog ->
            val scope = catalog.select(f.verified(), f.key)
            f.enqueue(f.error("project_exists"), 409)
            assertEquals(ServerProblem.PROJECT_EXISTS, (result<ProjectSummary> { catalog.createProject(scope, "game", "Label", it) }.result as CatalogResult.Failed).problem)
            f.enqueue(f.error("project_not_found"), 404)
            assertEquals(ServerProblem.PROJECT_NOT_FOUND, (result<ProjectSummary> { catalog.getProject(scope, "game", it) }.result as CatalogResult.Failed).problem)
            f.enqueue(f.error("legacy_space_not_found"), 404)
            assertEquals(ServerProblem.LEGACY_NOT_FOUND, (result<ProjectSummary> { catalog.resolveLegacy(scope, "old", it) }.result as CatalogResult.Failed).problem)
            f.enqueue(f.error("unauthorised"), 401)
            val failed = result<ProjectPage> { catalog.listProjects(scope, callback = it) }
            assertEquals(ServerProblem.UNAUTHORIZED, (failed.result as CatalogResult.Failed).problem)
            assertFalse(failed.toString().contains("canary")); assertEquals(4, f.server.requestCount)
        }
    }
    @Test fun restoredUnknownOutcomeBlocksPostUntilOriginalIdRead(): Unit = CatalogHttpFixture().use { f ->
        ProjectCatalog(f.client).use { catalog ->
            val scope = catalog.select(f.verified(), f.key)
            catalog.restoreUncertainCreate(scope, "original")
            assertTrue(result<ProjectSummary> { catalog.createProject(scope, "original", "Label", it) }.result is CatalogResult.CreateUncertain)
            assertEquals(0, f.server.requestCount)
            f.enqueue(f.projectReply("original", "Other winner"))
            assertTrue(result<ProjectSummary> { catalog.getProject(scope, "original", it) }.result is CatalogResult.Success)
            assertNotNull(catalog.uncertainCreates(scope).single().observedProject)
            assertEquals(1, f.server.requestCount)
        }
    }
    @Test fun post503RetryAfterZeroIsNeverAutomaticallyReplayed(): Unit = CatalogHttpFixture().use { f ->
        ProjectCatalog(f.client).use { catalog ->
            val scope = catalog.select(f.verified(), f.key)
            f.server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setBody(f.error("unavailable")))
            f.enqueue(f.projectReply(), 201) // Must remain unconsumed.
            assertTrue(result<ProjectSummary> { catalog.createProject(scope, "game", "Игра", it) }.result is CatalogResult.CreateUncertain)
            assertEquals(1, f.server.requestCount)
        }
    }
    @Test fun oldServerCatalogDisabledBeforeAnyRequest(): Unit = CatalogHttpFixture().use { f ->
        val legacy = ProjectCatalogCodec.decodeLegacyMe("""{"protocol":1,"user":{"id":"u","name":"Old","color":"#123456"}}""", f.identity())
        ProjectCatalog(f.client).use { catalog ->
            val scope = catalog.select(legacy, f.key)
            assertEquals(ServerProblem.UNSUPPORTED, (result<ProjectPage> { catalog.listProjects(scope, callback = it) }.result as CatalogResult.Failed).problem)
            assertEquals(ServerProblem.UNSUPPORTED, (result<ProjectSummary> { catalog.createProject(scope, "game", "Label", it) }.result as CatalogResult.Failed).problem)
            assertEquals(0, f.server.requestCount)
        }
    }
    @Test fun codecPreservesHistoricalLabelsButRejectsIdentitySchemaAndPaginationErrors(): Unit = CatalogHttpFixture().use { f ->
        val raw = JSONObject(f.project("game")).put("display_name", "  historical\u202e label  ")
        val historical = ProjectCatalogCodec.decodeProject(JSONObject().put("protocol", 2).put("project", raw).toString())
        assertEquals("  historical\u202e label  ", historical.displayName)
        val valid = f.projectReply()
        for (bad in listOf(valid.replace("\"protocol\":2", "\"protocol\":2.0"),
            valid.replace("\"metadata_revision\":1", "\"metadata_revision\":true"),
            valid.replace("0123456789abcdef0123456789abcdef", "wrong"),
            valid.replace("\"id\":\"game\"", "\"id\":\"Game\""), valid + "x",
            valid.replace("\"protocol\":2", "\"protocol\":2,\"protocol\":2"))) {
            assertThrows(CatalogValidationException::class.java) { ProjectCatalogCodec.decodeProject(bad) }
        }
        assertThrows(CatalogValidationException::class.java) { ProjectCatalogCodec.decodeProject(valid, "other") }
        for (page in listOf(
            """{"protocol":2,"projects":[${f.project("a")},${f.project("a")}],"next_after":null}""",
            """{"protocol":2,"projects":[${f.project("b")},${f.project("a")}],"next_after":null}""",
            """{"protocol":2,"projects":[],"next_after":"a"}""",
            """{"protocol":2,"projects":[${f.project("a")}],"next_after":"wrong"}""")) {
            assertThrows(CatalogValidationException::class.java) { ProjectCatalogCodec.decodePage(page, 2) }
        }
        assertThrows(CatalogValidationException::class.java) { ProjectCatalogCodec.decodeLegacyResolution("""{"protocol":2,"legacy_space_name":"OLD","project":${f.project()}}""", "old") }
    }
    @Test fun boundedRefreshPaginatesAndNeverClaimsSnapshotOrWrites(): Unit = CatalogHttpFixture().use { f ->
        ProjectCatalog(f.client).use { catalog ->
            val scope = catalog.select(f.verified(), f.key)
            val first = (0..99).joinToString(",") { f.project("p" + it.toString().padStart(3, '0')) }
            f.enqueue("""{"protocol":2,"projects":[$first],"next_after":"p099"}""")
            f.enqueue("""{"protocol":2,"projects":[${f.project("p100")}],"next_after":null}""")
            val all = result<List<ProjectSummary>> { catalog.listAllProjects(scope, it) }
            val projects = (all.result as CatalogResult.Success).value
            assertEquals(101, projects.size); assertEquals(101, projects.map { it.id }.toSet().size)
            assertEquals("GET", f.server.takeRequest().method)
            val next = f.server.takeRequest()
            assertEquals("GET", next.method)
            assertEquals("p099", next.requestUrl!!.queryParameter("after"))
            assertEquals(2, f.server.requestCount)
        }
    }
    @Test fun catalogInvalidOrRedirectedRepliesDoNotProbeFallbackOrAcceptWrongIdentity(): Unit = CatalogHttpFixture().use { f ->
        ProjectCatalog(f.client).use { catalog ->
            val scope = catalog.select(f.verified(), f.key)
            f.enqueue(f.projectReply("wrong"))
            assertEquals(ServerProblem.INVALID_RESPONSE, (result<ProjectSummary> { catalog.getProject(scope, "game", it) }.result as CatalogResult.Failed).problem)
            f.server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", f.server.url("/other")))
            assertEquals(ServerProblem.REDIRECT, (result<ProjectSummary> { catalog.getProject(scope, "game", it) }.result as CatalogResult.Failed).problem)
            f.enqueue("x".repeat(1024 * 1024 + 1))
            assertEquals(ServerProblem.INVALID_RESPONSE, (result<ProjectPage> { catalog.listProjects(scope, callback = it) }.result as CatalogResult.Failed).problem)
            assertEquals(3, f.server.requestCount)
        }
    }
    @Test fun strictJsonRejectsAndroidPermissiveSyntaxDuplicateKeysAndDeepInput(): Unit = CatalogHttpFixture().use { f ->
        val valid = f.projectReply()
        val invalid = listOf(valid.replace("\"protocol\":2", "'protocol':2"),
            valid.replace("\"protocol\":2", "protocol:2"),
            valid.replace("\"protocol\":2", "\"protocol\":2,\"\\u0070rotocol\":2"),
            valid.replace("\"protocol\":2", "\"protocol\":02"),
            valid.replace("\"protocol\":2", "\"protocol\":2,"),
            "[".repeat(18) + "0" + "]".repeat(18))
        invalid.forEach { raw -> assertThrows(CatalogValidationException::class.java) { ProjectCatalogCodec.decodeProject(raw) } }
    }
    @Test fun unicodeLabelValidationMatchesRemoteWithoutChangingIds() {
        assertEquals("é", ProjectCatalogCodec.normalizeDisplayName("  e\u0301  "))
        assertEquals("😀".repeat(120), ProjectCatalogCodec.normalizeDisplayName("😀".repeat(120)))
        for (bad in listOf("", " ", "x".repeat(121), "a\n", "\ta", "a\u007f", "a\u0085", "a\u202e", "a\u2066", "a\u2028", "\ud800")) {
            assertThrows(CatalogValidationException::class.java) { ProjectCatalogCodec.normalizeDisplayName(bad) }
        }
    }
}



