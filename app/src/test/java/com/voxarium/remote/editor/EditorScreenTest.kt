package com.voxarium.remote.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.voxarium.remote.R
import com.voxarium.remote.data.documents.DocumentNamespace
import com.voxarium.remote.data.room.*
import com.voxarium.remote.ui.editor.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w320dp-h480dp-notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun everyStatusIsLocalizedAndOnlyConfirmedStatesClaimSaved() {
        val current = mutableStateOf<RoomStatus?>(null)
        compose.setContent { MaterialTheme {
            EditorScreen("note.md", current.value, false, {}, {}, {}, Modifier.fillMaxSize()) { Text("injected editor") }
        } }
        compose.onNodeWithText("injected editor").assertIsDisplayed()
        val app = RuntimeEnvironment.getApplication()
        for (status in RoomStatus.entries) {
            compose.runOnIdle { current.value = status }
            compose.onNodeWithTag("editor-status").assertTextEquals(app.getString(editorStatusLabel(status)))
        }
        assertEquals(R.string.editor_status_saved, editorStatusLabel(RoomStatus.REMOTE_SAVED))
        assertEquals(R.string.editor_status_closed_confirmed, editorStatusLabel(RoomStatus.CLOSED_CONFIRMED))
        val confirmed = setOf(R.string.editor_status_saved, R.string.editor_status_closed_confirmed)
        RoomStatus.entries.filter { it !in setOf(RoomStatus.REMOTE_SAVED, RoomStatus.CLOSED_CONFIRMED) }
            .forEach { assertFalse(editorStatusLabel(it) in confirmed) }
    }

    @Test fun toolbarScrollsAndForwardsExactBridgeActionsOnlyOnClick() {
        val actions = mutableListOf<String>()
        var back = 0
        var recovery = 0
        compose.setContent { MaterialTheme {
            EditorScreen("note.md", RoomStatus.EDITING, false, { back++ }, actions::add, { recovery++ }, Modifier.fillMaxSize()) {}
        } }
        assertTrue(actions.isEmpty())
        listOf("Bold" to "bold", "Italic" to "italic", "Bullet list" to "bulletList", "Inline code" to "code", "Undo" to "undo", "Redo" to "redo")
            .forEach { (label, _) -> compose.onNodeWithText(label).performScrollTo().assertIsDisplayed().performClick() }
        assertEquals(listOf("bold", "italic", "bulletList", "code", "undo", "redo"), actions)
        compose.onNodeWithText("Close editor").performClick()
        compose.onNodeWithText("Local recovery").performClick()
        assertEquals(1, back)
        assertEquals(1, recovery)
    }

    @Test fun closingDisablesFormattingAndCloseButKeepsRecoveryAccessible() {
        compose.setContent { MaterialTheme {
            EditorScreen("note.md", RoomStatus.REMOTE_SAVED, true, {}, {}, {}, Modifier.fillMaxSize()) {}
        } }
        compose.onNodeWithText("Bold").assertIsNotEnabled()
        compose.onNodeWithText("Close editor").assertIsNotEnabled()
        compose.onNodeWithText("Local recovery").assertIsEnabled()
        compose.onNodeWithTag("editor-status").assertTextContains("Closing", substring = true)
    }

    @Test @Config(qualifiers = "ru-rRU-w320dp-h480dp-night-mdpi")
    fun russianDarkDoubleFontKeepsToolbarActionsAndEditorReachable() {
        var action = ""
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                MaterialTheme(colorScheme = darkColorScheme()) { Box(Modifier.size(320.dp, 480.dp)) {
                    EditorScreen("folder/note.md", RoomStatus.EDITING, false, {}, { action = it }, {}, Modifier.fillMaxSize()) { Text("CONTENT") }
                } }
            }
        }
        val app = RuntimeEnvironment.getApplication()
        compose.onNodeWithText(app.getString(R.string.editor_redo)).performScrollTo().assertIsDisplayed().performClick()
        assertEquals("redo", action)
        compose.onNodeWithText(app.getString(R.string.editor_bold)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("CONTENT").assertIsDisplayed()
    }

    @Test fun writeGateDoesNotOfferFormatting() {
        compose.setContent { MaterialTheme {
            EditorScreen("note.md", RoomStatus.WRITE_GATE_CLOSED, false, {}, {}, {}, Modifier.fillMaxSize()) {}
        } }
        compose.onNodeWithText("Bold").assertIsNotEnabled()
    }

    @Test fun recoveryShowsExactReadOnlyMarkdownWithoutAutomaticAction() {
        val raw = "# Raw\n\n**not rendered**\n<script>not executed</script>\n"
        val entry = RoomRecoveryEntry("id", DocumentNamespace("https://example.invalid", "account", "space"),
            "note.md", EditorContext(1, 1, "sid"), RoomSnapshot(raw, 1), emptyList())
        var back = 0
        var copied: AnnotatedString? = null
        val clipboard = object : ClipboardManager {
            override fun getText(): AnnotatedString? = copied
            override fun setText(annotatedString: AnnotatedString) { copied = annotatedString }
        }
        compose.setContent {
            CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                MaterialTheme { RecoveryEntryScreen(entry, { back++ }, Modifier.fillMaxSize()) }
            }
        }
        compose.onNodeWithTag("recovery-markdown").performScrollTo().assertTextEquals(raw)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.SetText))
        assertEquals(0, back)
        assertEquals(raw, entry.snapshot?.markdown)
        assertNull(copied)
        compose.onNodeWithText("Copy Markdown").performScrollTo().assertIsEnabled().performClick()
        assertEquals(raw, copied?.text)
    }
}
