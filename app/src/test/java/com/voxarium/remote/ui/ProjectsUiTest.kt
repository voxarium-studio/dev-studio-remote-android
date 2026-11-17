package com.voxarium.remote.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.voxarium.remote.*
import com.voxarium.remote.R
import com.voxarium.remote.data.documents.*
import com.voxarium.remote.data.security.*
import com.voxarium.remote.data.transport.*
import com.voxarium.remote.ui.connection.*
import com.voxarium.remote.ui.editor.*
import com.voxarium.remote.ui.theme.VoxariumRemoteTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w360dp-h640dp-notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProjectsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val profile = ServerProfile.create("Work", "https://example.test/proxy", "credential", "server-1")
    private val a = ProjectSummary("project-a", "Same label", "a".repeat(32), 1, "2026-11-17T00:00:00Z")
    private val b = a.copy(id = "project-b", storageEpoch = "b".repeat(32))
    private fun ready() = RemoteUiState(selectedServer = profile, verifiedAccount = RemoteUser("alice", "Alice", "#112233"),
        verificationStatus = VerificationStatus.VERIFIED, catalogStatus = CatalogStatus.READY,
        projects = listOf(a, b), selectionGeneration = 1)

    private fun screen(state: RemoteUiState, select: (String) -> Unit = {}, create: (String, String) -> Unit = { _, _ -> },
        resolve: (String) -> Unit = {}, refresh: () -> Unit = {}) {
        compose.setContent { VoxariumRemoteTheme {
            ProjectsScreen(state, {}, refresh, {}, select, create, resolve, {}, {})
        } }
    }

    @Test fun duplicateNamesKeepExactIdsAndSelectionIsSeparateFromConnect() {
        val selected = mutableListOf<String>()
        screen(ready(), select = { selected += it })
        compose.onAllNodesWithText("Same label").assertCountEquals(2)
        compose.onNodeWithText("Project ID: project-a").assertExists()
        compose.onNodeWithText("Project ID: project-b").assertExists()
        compose.onAllNodesWithText("Select project").onLast().performScrollTo().performClick()
        assertTrue(selected.isEmpty())
        compose.onNodeWithText("Confirm project identity").assertExists()
        compose.onAllNodesWithText("Project ID: project-b").onLast().assertExists()
        compose.onAllNodesWithText("Select project").onLast().performClick()
        assertEquals(listOf("project-b"), selected)
        compose.onNodeWithText("Connect").assertDoesNotExist()
    }

    @Test fun creationRequiresReviewAndConfirmationAndUsesExactId() {
        val created = mutableListOf<Pair<String, String>>()
        screen(ready(), create = { id, name -> created += id to name })
        compose.onNodeWithText("Create project").performScrollTo().performClick()
        compose.onNodeWithText("Project display name").performScrollTo().performTextInput("  Cafe\u0301  ")
        compose.onNodeWithText("Project ID (exact)").performScrollTo().performTextReplacement("chosen-id")
        compose.onNodeWithText("Review creation").performScrollTo().performClick()
        assertTrue(created.isEmpty())
        compose.onNodeWithText("Confirm project creation").assertExists()
        compose.onNodeWithText("Project ID: chosen-id").assertExists()
        compose.onNodeWithText("Café").assertExists()
        compose.onNode(hasText("Cancel") and hasAnyAncestor(isDialog())).performClick()
        assertTrue(created.isEmpty())
        compose.onNodeWithText("Review creation").performScrollTo().performClick()
        compose.onNodeWithText("Confirm creation").performClick()
        assertEquals(listOf("chosen-id" to "Café"), created)
    }

    @Test fun refreshBackAndFormCancellationNeverCreate() {
        var creates = 0; var refreshes = 0
        screen(ready(), create = { _, _ -> creates++ }, refresh = { refreshes++ })
        compose.onNodeWithText("Refresh projects").performScrollTo().performClick()
        compose.onNodeWithText("Create project").performScrollTo().performClick()
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        compose.onNodeWithText("Servers").performScrollTo().performClick()
        assertEquals(1, refreshes); assertEquals(0, creates)
    }

    @Test fun serverCredentialAccountOrGenerationChangeRevokesCreateConfirmation() {
        var creates = 0
        val state = mutableStateOf(ready())
        compose.setContent { VoxariumRemoteTheme {
            ProjectsScreen(state.value, {}, {}, {}, {}, { _, _ -> creates++ }, {}, {}, {})
        } }
        compose.onNodeWithText("Create project").performScrollTo().performClick()
        compose.onNodeWithText("Project display name").performScrollTo().performTextInput("Example")
        compose.onNodeWithText("Review creation").performScrollTo().performClick()
        compose.onNodeWithText("Confirm creation").assertExists()
        compose.runOnIdle { state.value = state.value.copy(selectionGeneration = 2,
            verifiedAccount = RemoteUser("bob", "Bob", "#112233")) }
        compose.onNodeWithText("Confirm creation").assertDoesNotExist()
        assertEquals(0, creates)
    }

    @Test fun unsupportedAndOfflineStatesCannotSelectOrCreate() {
        val state = mutableStateOf(ready().copy(verificationStatus = VerificationStatus.UNSUPPORTED))
        compose.setContent { VoxariumRemoteTheme { ProjectsScreen(state.value, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}) } }
        compose.onNodeWithText(compose.activity.getString(R.string.catalog_unsupported)).assertExists()
        compose.onNodeWithText("Create project").performScrollTo().assertIsNotEnabled()
        compose.onAllNodesWithText("Select project").onFirst().performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(verificationStatus = VerificationStatus.UNVERIFIED, catalogStatus = CatalogStatus.IDLE) }
        compose.onNodeWithText(compose.activity.getString(R.string.projects_known)).assertExists()
        compose.onNodeWithText("Create project").performScrollTo().assertIsNotEnabled()
    }

    @Test fun loadingEmptyErrorAndKnownAreNotConflated() {
        val state = mutableStateOf(ready().copy(projects = emptyList(), catalogStatus = CatalogStatus.LOADING))
        compose.setContent { VoxariumRemoteTheme { ProjectsScreen(state.value, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}) } }
        compose.onNodeWithText(compose.activity.getString(R.string.projects_loading)).assertExists()
        compose.runOnIdle { state.value = state.value.copy(catalogStatus = CatalogStatus.READY) }
        compose.onNodeWithText(compose.activity.getString(R.string.projects_empty)).assertExists()
        compose.runOnIdle { state.value = state.value.copy(catalogStatus = CatalogStatus.FAILED, serverProblem = ServerProblem.TLS) }
        compose.onNodeWithText(compose.activity.getString(R.string.projects_error)).assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.error_tls)).assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.projects_empty)).assertDoesNotExist()
    }

    @Test fun unknownCreationOnlyChecksOriginalIdAndNeverSelectsAutomatically() {
        val resolutions = mutableListOf<String>(); val selections = mutableListOf<String>(); var creates = 0
        val intent = CreateIntent("attempt", profile.localProfileId, profile.canonicalUrl, "alice", profile.credentialId,
            profile.credentialRevision, "original-id", "Original", CreateOutcome.UNKNOWN)
        val state = mutableStateOf(ready().copy(createIntents = listOf(intent)))
        compose.setContent { VoxariumRemoteTheme {
            ProjectsScreen(state.value, {}, {}, {}, { selections += it }, { _, _ -> creates++ }, { resolutions += it }, {}, {})
        } }
        compose.onNodeWithText("Creation result unknown").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Check original ID (GET only)").performScrollTo().performClick()
        assertEquals(listOf("original-id"), resolutions)
        assertTrue(selections.isEmpty()); assertEquals(0, creates)
        compose.runOnIdle { state.value = state.value.copy(createIntents = listOf(intent.copy(observedProject = a.copy(id = "original-id")))) }
        assertTrue(selections.isEmpty()); assertEquals(0, creates)
        compose.onAllNodesWithText("Select project").onLast().performScrollTo().performClick()
        compose.onNodeWithText("Confirm project identity").assertExists()
        assertTrue(selections.isEmpty())
    }

    @Test fun idIsNeverTrimmedOrLowercased() {
        for (id in listOf(" Project-a", "Project-a", "project-a ", "", "a".repeat(65), "я")) assertFalse(validProjectId(id))
        assertTrue(validProjectId("a.b_2-3"))
    }

    @Test fun recoveryPreservesOriginalTupleGenerationRawOnlyAndExactMarkdown() {
        val original = DocumentNamespace("https://original.test/prefix", "original-account", "project-a", "a".repeat(32))
        val entry = RecoveryPreview("one", "same/path.md", "# Exact\r\n\nПривет  ", original, generation = 12)
        compose.setContent { VoxariumRemoteTheme { RecoveryPreviewScreen(entry, {}) } }
        for (text in listOf("Original server URL: https://original.test/prefix", "Original account ID: original-account",
            "Original project ID: project-a", "Original storage epoch: ${original.storageEpoch}", "Generation: 12", "Original path: same/path.md"))
            compose.onNodeWithText(text).assertExists()
        compose.onNodeWithText(entry.markdown!!).performScrollTo().assertExists()
        compose.onNodeWithText("Copy Markdown").performScrollTo().performClick()
        compose.runOnIdle {
            @Suppress("DEPRECATION")
            val clipboard = compose.activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            assertEquals(entry.markdown, clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
    }

    @Test fun recoveryFromDifferentNamespacesDoesNotCollapseSamePaths() {
        val current = DocumentNamespace("https://one.test", "alice", "project-a", "a".repeat(32))
        val state = ready().copy(documentNamespace = current,
            localDrafts = listOf(DocumentDraft("same.md", "newer", 2, null, DraftKind.LOCAL)),
            documentRecovery = listOf(DocumentRecovery(1, "project-a", "same.md", "old", 1, null, RecoveryReason.SEQUENCE_ROLLBACK, null)))
        val other = DocumentNamespace("https://two.test/prefix", "bob", "project-b", "b".repeat(32))
        val room = com.voxarium.remote.data.room.RoomRecoveryEntry("one", other, "same.md",
            com.voxarium.remote.data.room.EditorContext(3, 4, "room"),
            com.voxarium.remote.data.room.RoomSnapshot("other target", 3), emptyList())
        val previews = recoveryPreviews(state.copy(recovery = listOf(room)))
        assertEquals(3, previews.size); assertEquals(3, previews.map { it.id }.toSet().size)
        assertEquals(listOf(other, current, current), previews.map { it.originalNamespace })
        assertEquals(listOf(3L, 1L, 2L), previews.map { it.generation })
        val raw = RecoveryPreview("raw", "same.md", null, current, generation = 3)
        compose.setContent { VoxariumRemoteTheme { RecoveryPreviewScreen(raw, {}) } }
        compose.onNodeWithText("Copy Markdown").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(compose.activity.getString(R.string.editor_recovery_no_snapshot)).performScrollTo().assertIsDisplayed()
    }

    @Test fun offlineArchivePreviewIsSeparateFromVerificationAndActivation() {
        val id = "a".repeat(32)
        val n = DocumentNamespace(profile.canonicalUrl, "alice", id)
        val legacy = LegacyImportRecord.create(SavedProfile.create("Old", profile.canonicalUrl, "old-name",
            profile.credentialId, profile.localProfileId), "alice", id)
        val state = RemoteUiState(selectedServer = profile, legacyImports = listOf(legacy),
            migrationStatus = IdentityMigrationStatus(IdentityMigrationPhase.VERIFIED, "hash",
                listOf(LegacyPartitionCandidate(n, setOf("old-name"), false)), emptySet()))
        var prepared = 0; var loaded: String? = null; var activated = 0
        compose.setContent { VoxariumRemoteTheme {
            ProjectsScreen(state, {}, {}, {}, {}, { _, _ -> }, {}, {}, {},
                onPrepareArchive = { prepared++ }, onLoadArchive = { loaded = it }, onActivateLegacy = { activated++ })
        } }
        compose.onNodeWithText("Prepare archive offline (no verification)").performScrollTo().performClick()
        compose.onNodeWithText("Load original archive for preview").performScrollTo().performClick()
        assertEquals(1, prepared); assertEquals(id, loaded); assertEquals(0, activated)
        compose.onNodeWithText("Accept exact legacy mapping").assertDoesNotExist()
    }

    @Test fun archivedPendingNewerDraftAndRawDiagnosticsKeepOriginalProvenance() {
        val n = DocumentNamespace("https://old.test/prefix", "old-account", "old-project")
        val entries = listOf(
            LegacyArchiveEntry("pending", n, "same.md", "sent\r\n", 1, LegacyArchiveKind.PENDING),
            LegacyArchiveEntry("draft", n, "same.md", "newer\n", 2, LegacyArchiveKind.DRAFT),
            LegacyArchiveEntry("raw", n, "same.md", null, 3, LegacyArchiveKind.ROOM, 2))
        val previews = recoveryPreviews(ready().copy(legacyArchive = entries))
        assertEquals(3, previews.size); assertEquals(3, previews.map { it.id }.distinct().size)
        assertEquals(listOf(n,n,n), previews.map { it.originalNamespace })
        assertEquals(listOf("sent\r\n", "newer\n", null), previews.map { it.markdown })
        assertEquals(2, previews.last().rawItemCount)
        compose.setContent { VoxariumRemoteTheme { RecoveryPreviewScreen(previews.last(), {}) } }
        compose.onNodeWithText("Raw diagnostic items: 2 — no decoding or replay").assertExists()
        compose.onNodeWithText("Copy Markdown").performScrollTo().assertIsNotEnabled()
    }

    @Test fun knownProjectsNeverRevealAnotherAuthenticatedAccount() {
        val old = ProjectBinding.create(profile, "private-old", "alice", "a".repeat(32))
        val observed = ServerVerification.create(profile.canonicalUrl, profile.credentialId, profile.credentialRevision,
            2, "bob", 2, 0, "Bob", "#112233", 2)
        val fresh = ServerProfile.create(profile.name, profile.canonicalUrl, profile.credentialId,
            profile.localProfileId, profile.credentialRevision, observed)
        val state = mutableStateOf(ready().copy(selectedServer = fresh, projects = emptyList(), knownBindings = listOf(old),
            verifiedAccount = RemoteUser("bob", "Bob", "#112233")))
        compose.setContent { VoxariumRemoteTheme { ProjectsScreen(state.value, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}) } }
        compose.onNodeWithText("Project ID: private-old").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(verifiedAccount = null, verificationStatus = VerificationStatus.UNVERIFIED) }
        compose.onNodeWithText("Project ID: private-old").assertDoesNotExist()
    }

    @Test fun archiveReasonsAreNotRelabelledAsRestart() {
        val n = DocumentNamespace("https://old.test", "alice", "old-project")
        val reasons = RecoveryReason.entries
        val entries = reasons.mapIndexed { i, reason ->
            LegacyArchiveEntry("r$i", n, "same.md", "exact", 1, LegacyArchiveKind.RECOVERY, reason = reason)
        }
        val expected = listOf(R.string.recovery_reason_conflict, R.string.recovery_reason_restart,
            R.string.recovery_reason_replaced, R.string.recovery_reason_rejected, R.string.recovery_reason_reset,
            R.string.recovery_reason_rollback)
        assertEquals(expected, recoveryPreviews(ready().copy(legacyArchive = entries)).map { it.reasonLabel })
    }
}
