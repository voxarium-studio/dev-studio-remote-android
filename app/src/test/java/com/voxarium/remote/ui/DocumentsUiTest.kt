package com.voxarium.remote.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.hasText
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.voxarium.remote.data.transport.DocumentVersion
import com.voxarium.remote.ui.documents.DocumentsList
import com.voxarium.remote.ui.documents.MarkdownReader
import com.voxarium.remote.ui.documents.documentTree
import com.voxarium.remote.ui.documents.visibleRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w320dp-h480dp-notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DocumentsUiTest {
    @get:Rule val compose = createComposeRule()

    private fun version(content: String = "# Source\n") = DocumentVersion(42, "a".repeat(64), false, content)

    @Test fun treeExcludesTombstonesAndKeepsCaseSensitivePaths() {
        val tree = documentTree(mapOf(
            "gone/only.md" to DocumentVersion(43, null, true, null),
            "A/same.md" to version(),
            "a/same.md" to version(),
            "root.md" to version(),
        ))
        assertFalse(tree.any { it.path.startsWith("gone") })
        assertEquals(listOf("A", "a", "root.md"), visibleRows(tree, emptySet()).map { it.path })
        assertEquals(listOf("A", "A/same.md", "a", "a/same.md", "root.md"),
            visibleRows(tree, setOf("A", "a")).map { it.path })
    }

    @Test fun foldersExpandCollapseAndOpeningOnlyReturnsExactPath() {
        val documents = mapOf("folder/note.md" to version(), "deleted.md" to DocumentVersion(9, null, true, null))
        val before = documents.toMap()
        val opened = mutableListOf<String>()
        compose.setContent { MaterialTheme { DocumentsList(documents, opened::add, Modifier.fillMaxSize()) } }
        compose.onNodeWithText("deleted.md").assertDoesNotExist()
        compose.onNodeWithText("folder/note.md").assertDoesNotExist()
        compose.onNodeWithText("folder").performClick()
        compose.onNodeWithText("folder/note.md").assertIsDisplayed().performClick()
        assertEquals(listOf("folder/note.md"), opened)
        compose.onNodeWithText("folder").performClick()
        compose.onNodeWithText("folder/note.md").assertDoesNotExist()
        assertEquals(before, documents)
    }

    @Test fun tombstoneOnlyCacheShowsEmptyState() {
        compose.setContent { MaterialTheme {
            DocumentsList(mapOf("gone/note.md" to DocumentVersion(9, null, true, null)), {})
        } }
        compose.onNodeWithText("No cached documents").assertIsDisplayed()
        compose.onNodeWithText("gone").assertDoesNotExist()
    }

    // Robolectric API35 has no backing Surface for Magnifier.destroy; API23 exercises
    // real SelectionContainer gestures without that host-only platform popup failure.
    @Test @Config(sdk = [23]) fun readerPreservesMarkdownAndSupportsSelectionWithoutAnEditor() {
        val raw = "# Not rendered\n\n**bold**  \n[link](https://example.invalid)\n"
        val snapshot = version(raw)
        var back = 0
        compose.setContent { MaterialTheme { MarkdownReader("folder/note.md", snapshot, { back++ }, Modifier.fillMaxSize()) } }
        compose.onNodeWithText("Read-only cache snapshot. This is not a live editor; recent changes may be missing.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Revision 42").performScrollTo().assertIsDisplayed()
        val source = compose.onNodeWithTag("raw-markdown")
        source.performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(AnnotatedString(raw))))
        source.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.SetText))
        source.performTouchInput { longClick() }
        assertEquals(raw, snapshot.content)
        assertEquals(0, back)
        // Dismiss selection before navigating; opening/selecting offers no write callback at all.
        compose.onNodeWithText("Back to documents").performScrollTo().performClick()
        assertEquals(1, back)
    }

    @Test fun emptyMarkdownHasAnExplicitIndication() {
        compose.setContent { MaterialTheme { MarkdownReader("empty.md", version(""), {}, Modifier.fillMaxSize()) } }
        compose.onNodeWithText("This document contains empty Markdown.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("raw-markdown").assertDoesNotExist()
    }

    @Test fun deepTreeAtDoubleFontKeepsFullPathWrappedAndReachable() {
        val segments = (1..9).map { "folder$it" }
        val path = segments.joinToString("/") + "/note.md"
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                MaterialTheme { Box(Modifier.size(320.dp, 480.dp)) {
                    DocumentsList(mapOf(path to version()), {}, Modifier.fillMaxSize())
                } }
            }
        }
        segments.forEachIndexed { index, _ ->
            val folder = segments.take(index + 1).joinToString("/")
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
                .performScrollToNode(hasText(folder))
            compose.onNodeWithText(folder).performClick()
        }
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
            .performScrollToNode(hasText(path))
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(path, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        assertTrue(layouts.isNotEmpty())
        layouts.forEach { assertFalse("Full path must wrap without clipping", it.hasVisualOverflow) }
    }
}
