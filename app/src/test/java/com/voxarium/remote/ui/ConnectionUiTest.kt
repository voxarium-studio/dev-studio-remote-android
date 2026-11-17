package com.voxarium.remote.ui

import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.voxarium.remote.*
import com.voxarium.remote.R
import com.voxarium.remote.data.security.*
import com.voxarium.remote.data.transport.*
import com.voxarium.remote.ui.connection.*
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
class ConnectionUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val profile = ServerProfile.create("Work", "https://example.test/proxy", "credential", localProfileId = "server-1")
    private val project = ProjectSummary("project-a", "Same label", "a".repeat(32), 1, "2026-11-17T00:00:00Z")
    private val binding = ProjectBinding.create(profile, project.id, "account-a", project.storageEpoch)
    private fun ready() = RemoteUiState(serverProfiles = listOf(profile), selectedServer = profile,
        selectedProject = binding, selectedProjectSummary = project, projects = listOf(project),
        verifiedAccount = RemoteUser("account-a", "Alice", "#112233"), verificationStatus = VerificationStatus.VERIFIED,
        catalogStatus = CatalogStatus.READY, selectionGeneration = 1)

    private fun screen(state: RemoteUiState, connect: () -> Unit = {}, retry: () -> Unit = {}) {
        compose.setContent { VoxariumRemoteTheme {
            RemoteScreen(state, { _, _, _, _ -> }, {}, connect, {}, retry, {}, {})
        } }
    }

    @Test fun formValidatesServerWithoutAnySpaceOrProjectSelector() {
        assertEquals(ProfileFormError.LABEL, validateProfileForm(" ", "example.test"))
        for (url in listOf("http://example.test", "https://key@example.test", "https://example.test?token=x", "https://example.test#x"))
            assertEquals(ProfileFormError.SERVER, validateProfileForm("Work", url))
        assertNull(validateProfileForm("Work", "example.test/proxy"))
        compose.setContent { VoxariumRemoteTheme { ProfileForm(false, {}, { _, _, _ -> }) } }
        compose.onNodeWithText("Space name").assertDoesNotExist()
        compose.onNodeWithText("Project ID (exact)").assertDoesNotExist()
        compose.onNodeWithText("Save server").performScrollTo().assertIsDisplayed()
    }

    @Test fun connectRequiresExactIdAccountEpochAndExplicitConfirmation() {
        var connects = 0
        screen(ready(), { connects++ })
        compose.onNodeWithText("Connect").performScrollTo().performClick()
        assertEquals(0, connects)
        compose.onNodeWithText("Connect selected project?").assertIsDisplayed()
        compose.onAllNodesWithText("Project ID: project-a").onLast().assertExists()
        compose.onAllNodesWithText("https://example.test/proxy").onLast().assertExists()
        compose.onAllNodesWithText("Account: Alice · ID: account-a").onLast().assertExists()
        compose.onAllNodesWithText("Storage epoch: ${project.storageEpoch}").onLast().assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, connects)
        compose.onNodeWithText("Connect").performScrollTo().performClick()
        compose.onNodeWithText("Confirm and connect").performClick()
        assertEquals(1, connects)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Documents"))
        compose.onNodeWithText("Documents").assertExists()
    }

    @Test fun changedGenerationOrCredentialRevisionRevokesConfirmation() {
        var connects = 0
        val state = mutableStateOf(ready())
        compose.setContent { VoxariumRemoteTheme {
            RemoteScreen(state.value, { _, _, _, _ -> }, {}, { connects++ }, {}, {}, {}, {})
        } }
        compose.onNodeWithText("Connect").performScrollTo().performClick()
        compose.runOnIdle { state.value = state.value.copy(selectionGeneration = 2) }
        compose.onNodeWithText("Confirm and connect").assertDoesNotExist()
        compose.onNodeWithText("Connect").performScrollTo().performClick()
        compose.runOnIdle {
            state.value = state.value.copy(selectedServer = ServerProfile.create(profile.name, profile.canonicalUrl,
                profile.credentialId, profile.localProfileId, credentialRevision = 2))
        }
        compose.onNodeWithText("Confirm and connect").assertDoesNotExist()
        assertEquals(0, connects)
    }

    @Test fun passwordHasNativeProtectionsAndIsClearedBeforeLocalSave() {
        var saves = 0
        lateinit var input: EditText
        compose.setContent { VoxariumRemoteTheme {
            ProfileForm(false, {}, { _, _, key ->
                assertEquals("test-only-value", key)
                assertEquals("", input.text.toString())
                saves++
            })
        } }
        compose.onNodeWithText("Server name").performTextInput("Work")
        compose.onNodeWithText("Server URL (HTTPS)").performTextInput("example.test")
        compose.runOnIdle {
            input = findPassword(compose.activity.window.decorView)!!
            assertFalse(input.isSaveEnabled)
            assertFalse(input.isSaveFromParentEnabled)
            assertEquals(View.NO_ID, input.id)
            assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, input.importantForAutofill)
            assertTrue(input.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
            assertTrue(input.transformationMethod is android.text.method.PasswordTransformationMethod)
            input.setText("test-only-value")
        }
        compose.onAllNodesWithText("test-only-value").assertCountEquals(0)
        compose.onNodeWithText("Save server").performScrollTo().performClick()
        assertEquals(1, saves)
        compose.onNodeWithText("Save server").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.validation_key)).assertExists()
        assertEquals(1, saves)
    }

    @Test fun savingDoesNotVerifySelectOrConnectAndVerifyIsSeparate() {
        var saves = 0; var verifies = 0; var selects = 0; var connects = 0
        compose.setContent { VoxariumRemoteTheme {
            RemoteScreen(ready(), { _, _, _, done -> saves++; done() }, { selects++ }, { connects++ }, {}, {}, {}, {},
                onVerify = { verifies++ })
        } }
        compose.onNodeWithText("Add server").performScrollTo().performClick()
        compose.onNodeWithText("Server name").performTextInput("New")
        compose.onNodeWithText("Server URL (HTTPS)").performTextInput("new.example.test")
        compose.runOnIdle { findPassword(compose.activity.window.decorView)!!.setText("test-only") }
        compose.onNodeWithText("Save server").performScrollTo().performClick()
        assertEquals(1, saves); assertEquals(0, verifies); assertEquals(0, selects); assertEquals(0, connects)
        compose.onNodeWithText("Verify server").performScrollTo().performClick()
        assertEquals(1, verifies); assertEquals(0, connects)
    }

    @Test fun safeErrorsAndExplicitRetryRetainIdentityConfirmation() {
        var retries = 0
        screen(ready().copy(status = RemoteStatus.OFFLINE, error = RemoteError.NETWORK), retry = { retries++ })
        compose.onNodeWithText("Retry connection").performScrollTo().performClick()
        assertEquals(0, retries)
        compose.onNodeWithText("Confirm and connect").performClick()
        assertEquals(1, retries)
        assertNotEquals(errorMessage(RemoteError.AUTHENTICATION), errorMessage(RemoteError.NETWORK))
        assertNotEquals(serverProblemMessage(ServerProblem.TIMEOUT), serverProblemMessage(ServerProblem.TLS))
        assertEquals(R.string.error_storage, errorMessage(RemoteError.CREDENTIAL_STORAGE))
    }

    @Test fun automaticBackoffStillOffersDisconnect() {
        var disconnects = 0
        compose.setContent { VoxariumRemoteTheme {
            RemoteScreen(ready().copy(status = RemoteStatus.OFFLINE, error = RemoteError.NETWORK, connectionActive = true),
                { _, _, _, _ -> }, {}, {}, { disconnects++ }, {}, {}, {})
        } }
        compose.onNodeWithText("Disconnect").performScrollTo().performClick()
        assertEquals(1, disconnects)
    }

    private fun findPassword(view: View): EditText? = when (view) {
        is EditText -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findPassword(view.getChildAt(it)) }
        else -> null
    }
}
