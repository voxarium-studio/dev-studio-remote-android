package com.voxarium.remote.ui.editor

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.voxarium.remote.R
import com.voxarium.remote.RemoteUiState
import com.voxarium.remote.LegacyArchiveKind
import com.voxarium.remote.data.documents.DocumentNamespace
import com.voxarium.remote.data.documents.RecoveryReason

/** Presentation only: exact provenance does not convey original-write or replay authority. */
data class RecoveryPreview(
    val id: String, val path: String, val markdown: String?,
    val originalNamespace: DocumentNamespace? = null,
    val originalProjectId: String? = originalNamespace?.spaceId,
    val generation: Long? = null, val rawItemCount: Int = 0, val reasonLabel: Int = R.string.recovery_reason_local,
) {
    override fun toString() = "RecoveryPreview(redacted)"
}

fun recoveryPreviews(state: RemoteUiState): List<RecoveryPreview> =
    state.recovery.map {
        RecoveryPreview("room:${namespaceIdentity(it.namespace)}:${it.id}", it.path, it.snapshot?.markdown,
            it.namespace, generation = it.snapshot?.generation ?: it.context.documentGeneration,
            reasonLabel = R.string.recovery_reason_room)
    } + state.documentRecovery.map {
        // V2 repository validates that every recovery belongs to this exact partition.
        // Legacy journals are account-wide; retain the record ID but never invent a v2 epoch.
        val original = state.documentNamespace?.let { n ->
            if (n.storageEpoch == null) DocumentNamespace(n.server, n.accountId, it.spaceId)
            else n.takeIf { n.spaceId == it.spaceId }
        }
        RecoveryPreview("file:${original?.let(::namespaceIdentity)}:${it.spaceId}:${it.id}", it.path, it.content,
            original, originalProjectId = it.spaceId, generation = it.generation, reasonLabel = when (it.reason) {
                RecoveryReason.CONFLICT -> R.string.recovery_reason_conflict
                RecoveryReason.OFFLINE_OR_RESTART -> R.string.recovery_reason_restart
                RecoveryReason.SPACE_REPLACED -> R.string.recovery_reason_replaced
                RecoveryReason.REJECTED -> R.string.recovery_reason_rejected
                RecoveryReason.REMOTE_RESET -> R.string.recovery_reason_reset
                RecoveryReason.SEQUENCE_ROLLBACK -> R.string.recovery_reason_rollback
            })
    } + state.localDrafts.map {
        RecoveryPreview("draft:${state.documentNamespace?.let(::namespaceIdentity)}:${it.generation}:${it.path}",
            it.path, it.content, state.documentNamespace, generation = it.generation)
    } + state.legacyArchive.map {
        RecoveryPreview("archive:${namespaceIdentity(it.namespace)}:${it.id}", it.path, it.markdown,
            it.namespace, generation = it.generation, rawItemCount = it.rawItemCount,
            reasonLabel = when(it.kind) {
                LegacyArchiveKind.MIRROR -> R.string.recovery_reason_mirror
                LegacyArchiveKind.PENDING -> R.string.recovery_reason_pending
                LegacyArchiveKind.DRAFT -> R.string.recovery_reason_local
                LegacyArchiveKind.RECOVERY -> when(it.reason) {
                    RecoveryReason.CONFLICT -> R.string.recovery_reason_conflict
                    RecoveryReason.OFFLINE_OR_RESTART -> R.string.recovery_reason_restart
                    RecoveryReason.SPACE_REPLACED -> R.string.recovery_reason_replaced
                    RecoveryReason.REJECTED -> R.string.recovery_reason_rejected
                    RecoveryReason.REMOTE_RESET -> R.string.recovery_reason_reset
                    RecoveryReason.SEQUENCE_ROLLBACK -> R.string.recovery_reason_rollback
                    null -> R.string.recovery_unknown
                }
                LegacyArchiveKind.ROOM -> R.string.recovery_reason_room
            })
    }

/** Length-prefix preserves tuple boundaries even for punctuation/Unicode in server/account. */
private fun namespaceIdentity(n: DocumentNamespace) = listOf(n.server, n.accountId, n.spaceId, n.storageEpoch.orEmpty())
    .joinToString("") { "${it.length}:$it" }

@Composable
fun RecoveryHub(entries: List<RecoveryPreview>, onBack: () -> Unit, onSelect: (RecoveryPreview) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { TextButton(onClick = onBack) { Text(stringResource(R.string.editor_recovery_back)) } }
        item { Text(stringResource(R.string.editor_recovery_title), style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() }) }
        item { Text(stringResource(R.string.editor_recovery_notice)) }
        if (entries.isEmpty()) item { Text(stringResource(R.string.editor_recovery_empty)) }
        entries.groupBy { entry ->
            // Missing provenance never merges records merely because their paths/IDs look alike.
            entry.originalNamespace?.let(::namespaceIdentity) ?: "unknown:${entry.id}"
        }.forEach { (group, originals) ->
            item(key = "identity:$group") { RecoveryIdentity(originals.first()) }
            items(originals, key = { it.id }) { entry ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        RecoveryDetails(entry)
                        OutlinedButton(onClick = { onSelect(entry) }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.editor_recovery_markdown))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecoveryIdentity(entry: RecoveryPreview) {
    val unknown = stringResource(R.string.recovery_unknown)
    Text(stringResource(R.string.recovery_original_identity), style = MaterialTheme.typography.titleSmall)
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.recovery_server, entry.originalNamespace?.server ?: unknown))
            Text(stringResource(R.string.recovery_project, entry.originalProjectId ?: unknown))
            Text(stringResource(R.string.recovery_account, entry.originalNamespace?.accountId ?: unknown))
            val epoch = if (entry.originalNamespace == null) unknown
                else entry.originalNamespace.storageEpoch ?: stringResource(R.string.recovery_legacy_epoch)
            Text(stringResource(R.string.recovery_epoch, epoch))
        }
    }
}

@Composable
private fun RecoveryDetails(entry: RecoveryPreview) {
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.recovery_path, entry.path))
            entry.generation?.let { Text(stringResource(R.string.recovery_generation, it)) }
                ?: Text(stringResource(R.string.recovery_unknown))
            Text(stringResource(entry.reasonLabel))
            if (entry.rawItemCount > 0) Text(stringResource(R.string.recovery_raw_count, entry.rawItemCount))
        }
    }
}

@Suppress("DEPRECATION")
@Composable
fun RecoveryPreviewScreen(entry: RecoveryPreview, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.editor_recovery_back)) }
        RecoveryIdentity(entry)
        RecoveryDetails(entry)
        Text(stringResource(R.string.editor_recovery_notice))
        TextButton(onClick = { entry.markdown?.let { clipboard.setText(AnnotatedString(it)) } }, enabled = entry.markdown != null) {
            Text(stringResource(R.string.editor_recovery_copy))
        }
        val markdown = entry.markdown
        if (markdown == null) Text(stringResource(R.string.editor_recovery_no_snapshot))
        else if (markdown.isEmpty()) Text(stringResource(R.string.editor_recovery_empty_markdown))
        else SelectionContainer { Text(markdown, fontFamily = FontFamily.Monospace) }
    }
}
