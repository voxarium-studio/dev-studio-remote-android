package com.voxarium.remote.ui.documents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.voxarium.remote.R
import com.voxarium.remote.data.transport.DocumentVersion

/** Pure cache navigation: this component has no repository, transport or write callbacks. */
@Composable
fun DocumentsList(
    documents: Map<String, DocumentVersion>,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit = {},
) {
    val tree = remember(documents) { documentTree(documents) }
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    val rows = remember(tree, expanded) { visibleRows(tree, expanded) }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "session") { header() }
        item(key = "title") {
            Text(
                stringResource(R.string.documents_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
        }
        if (tree.isEmpty()) {
            item(key = "empty") {
                Text(stringResource(R.string.documents_empty))
            }
        }
        items(rows, key = { "${it.folder}:${it.path}" }) { row ->
            val isExpanded = row.path in expanded
            val action = stringResource(
                if (row.folder) {
                    if (isExpanded) R.string.documents_folder_collapse else R.string.documents_folder_expand
                } else R.string.documents_open,
                row.path,
            )
            val status = if (row.folder) stringResource(
                if (isExpanded) R.string.documents_folder_expanded else R.string.documents_folder_collapsed,
            ) else null
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // Depth never consumes the narrow-screen reading area, even at 2x font.
                    .padding(start = (row.depth.coerceAtMost(3) * 8).dp)
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClickLabel = action) {
                        if (row.folder) {
                            expanded = if (isExpanded) expanded - row.path else expanded + row.path
                        } else onOpen(row.path)
                    }
                    .semantics(mergeDescendants = true) {
                        if (row.folder) heading()
                        if (status != null) stateDescription = status
                    }
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    row.path.substringAfterLast('/'),
                    style = if (row.folder) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                )
                if ('/' in row.path) {
                    Text(
                        row.path,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (status != null) Text(status, style = MaterialTheme.typography.labelMedium)
            }
            HorizontalDivider()
        }
    }
}

internal data class DocumentTreeRow(val path: String, val folder: Boolean, val depth: Int)

/** Keys are POSIX, case-sensitive protocol paths, never OS paths; tombstones create no folders. */
internal fun documentTree(documents: Map<String, DocumentVersion>): List<DocumentTreeRow> {
    val folders = mutableSetOf<String>()
    val leaves = documents.filterValues { !it.deleted }.keys
    leaves.forEach { path ->
        path.indices.filter { path[it] == '/' }.forEach { folders += path.substring(0, it) }
    }
    return (folders.map { DocumentTreeRow(it, true, it.count { c -> c == '/' }) } +
        leaves.map { DocumentTreeRow(it, false, it.count { c -> c == '/' }) })
        .sortedWith(compareBy<DocumentTreeRow>({ it.path.substringBeforeLast('/', "") }, { !it.folder }, { it.path }))
}

internal fun visibleRows(tree: List<DocumentTreeRow>, expanded: Set<String>): List<DocumentTreeRow> {
    val children = tree.groupBy { it.path.substringBeforeLast('/', "") }
    val result = mutableListOf<DocumentTreeRow>()
    fun visit(parent: String) {
        children[parent].orEmpty().forEach { row ->
            result += row
            if (row.folder && row.path in expanded) visit(row.path)
        }
    }
    visit("")
    return result
}
