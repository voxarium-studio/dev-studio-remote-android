package com.voxarium.remote.ui.documents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.voxarium.remote.R
import com.voxarium.remote.data.transport.DocumentVersion

/** Displays the exact cache bytes as text, not a parser/editor or a live room. */
@Composable
fun MarkdownReader(
    path: String,
    version: DocumentVersion,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit = {},
) {
    key(path) {
        Column(
            modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.documents_back)) }
            header()
            Text(
                path,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.documents_snapshot_warning), modifier = Modifier.padding(12.dp))
            }
            Text(
                stringResource(R.string.documents_revision, version.rev),
                style = MaterialTheme.typography.labelLarge,
            )
            val label = stringResource(R.string.documents_markdown_label)
            Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            val content = version.content.orEmpty()
            if (content.isEmpty()) {
                Text(stringResource(R.string.documents_markdown_empty))
            } else {
                SelectionContainer {
                    Text(
                        text = content,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().testTag("raw-markdown").semantics {
                            contentDescription = label
                        },
                    )
                }
            }
        }
    }
}
