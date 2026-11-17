package com.voxarium.remote.ui.editor

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.voxarium.remote.R
import com.voxarium.remote.data.room.RoomRecoveryEntry
import com.voxarium.remote.data.room.RoomStatus

/** Presentation only. Host owns safeDrawing/IME insets, Back handling and all room operations.
 * A Back click requests coordinator close, never disposes the injected content directly.
 */
@Composable
fun EditorScreen(
    path: String,
    status: RoomStatus?,
    closing: Boolean,
    onBack: () -> Unit,
    onFormat: (String) -> Unit,
    onRecovery: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val editable = !closing && status in setOf(
        RoomStatus.EDITING, RoomStatus.ROOM_ONLY, RoomStatus.SAVING, RoomStatus.REMOTE_SAVED,
    )
    Surface(modifier = modifier) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val chromeMaxHeight = maxHeight * .45f
            Column(Modifier.fillMaxSize()) {
                // Bound chrome at large font sizes; its text/actions remain scrollable.
                Column(Modifier.fillMaxWidth().heightIn(max = chromeMaxHeight)
                    .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        TextButton(onClick = onBack, enabled = !closing) { Text(stringResource(R.string.editor_back)) }
                        TextButton(onClick = onRecovery) { Text(stringResource(R.string.editor_recovery)) }
                    }
                    Text(path, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    Text(
                        stringResource(editorStatusLabel(if (closing) RoomStatus.CLOSING else status)),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 8.dp).testTag("editor-status")
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("editor-toolbar")) {
                    formatActions.forEach { (action, label) ->
                        TextButton(onClick = { onFormat(action) }, enabled = editable) {
                            Text(stringResource(label))
                        }
                    }
                }
                HorizontalDivider()
                Box(Modifier.fillMaxWidth().weight(1f).testTag("editor-content")) { content() }
            }
        }
    }
}

private val formatActions = listOf(
    "bold" to R.string.editor_bold,
    "italic" to R.string.editor_italic,
    "bulletList" to R.string.editor_list,
    "code" to R.string.editor_code,
    "undo" to R.string.editor_undo,
    "redo" to R.string.editor_redo,
)

/** Exhaustive and deliberately conservative: room membership/local recovery are not save ACKs. */
@StringRes
internal fun editorStatusLabel(status: RoomStatus?): Int = when (status) {
    null, RoomStatus.WAITING_SYNC -> R.string.editor_status_waiting
    RoomStatus.JOINING -> R.string.editor_status_joining
    RoomStatus.SEEDING -> R.string.editor_status_seeding
    RoomStatus.MOUNTING -> R.string.editor_status_mounting
    RoomStatus.EDITING -> R.string.editor_status_editing
    RoomStatus.ROOM_ONLY -> R.string.editor_status_room_only
    RoomStatus.SAVING -> R.string.editor_status_saving
    RoomStatus.REMOTE_SAVED -> R.string.editor_status_saved
    RoomStatus.CLOSING -> R.string.editor_status_closing
    RoomStatus.CLOSED_CONFIRMED -> R.string.editor_status_closed_confirmed
    RoomStatus.CLOSED_RECOVERY_UNCONFIRMED -> R.string.editor_status_closed_recovery
    RoomStatus.RECOVERY_UNCONFIRMED -> R.string.editor_status_recovery
    RoomStatus.MISSING -> R.string.editor_status_missing
    RoomStatus.STALE -> R.string.editor_status_stale
    RoomStatus.SCHEMA_BLOCKED -> R.string.editor_status_schema
    RoomStatus.WRITE_GATE_CLOSED -> R.string.editor_status_gate
    RoomStatus.RECONNECT_REQUIRED -> R.string.editor_status_reconnect
}

/** Read-only local recovery list; selection is navigation only, never an apply/restore action.
 * Load entries for the current immutable namespace off the main thread in the host.
 */
@Composable
fun RecoveryScreen(
    entries: List<RoomRecoveryEntry>,
    onBack: () -> Unit,
    onSelect: (RoomRecoveryEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item { TextButton(onClick = onBack) { Text(stringResource(R.string.editor_recovery_back)) } }
            item { Text(stringResource(R.string.editor_recovery_title), style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() }) }
            item { Text(stringResource(R.string.editor_recovery_notice), modifier = Modifier.padding(vertical = 12.dp)) }
            if (entries.isEmpty()) item { Text(stringResource(R.string.editor_recovery_empty)) }
            items(entries, key = { it.id }) { entry ->
                TextButton(onClick = { onSelect(entry) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(entry.path)
                        Text(entry.id, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

/** Raw Markdown is never parsed, executed, replayed or written to Remote. Copy is explicit. */
@Suppress("DEPRECATION")
@Composable
fun RecoveryEntryScreen(entry: RoomRecoveryEntry, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    val markdown = entry.snapshot?.markdown
    Surface(modifier) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.editor_recovery_back)) }
            Text(entry.path, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.editor_recovery_notice), modifier = Modifier.padding(vertical = 12.dp))
            TextButton(onClick = { markdown?.let { clipboard.setText(AnnotatedString(it)) } }, enabled = markdown != null) {
                Text(stringResource(R.string.editor_recovery_copy))
            }
            Text(stringResource(R.string.editor_recovery_markdown), modifier = Modifier.semantics { heading() })
            if (markdown == null) Text(stringResource(R.string.editor_recovery_no_snapshot))
            else if (markdown.isEmpty()) Text(stringResource(R.string.editor_recovery_empty_markdown))
            else SelectionContainer {
                Text(markdown, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().testTag("recovery-markdown"))
            }
        }
    }
}
