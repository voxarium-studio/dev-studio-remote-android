package com.voxarium.remote.ui.connection

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.voxarium.remote.*
import com.voxarium.remote.R
import com.voxarium.remote.data.security.CreateOutcome
import com.voxarium.remote.data.transport.ProjectCatalogCodec
import com.voxarium.remote.data.transport.ProjectSummary
import java.util.UUID

/** Android identity design §7. No networking, secrets or implicit Select/Create/Connect. */
@Composable
fun ProjectsScreen(
    state: RemoteUiState, onBack: () -> Unit, onRefresh: () -> Unit,
    onLookup: (String) -> Unit, onSelect: (String) -> Unit,
    onCreate: (String, String) -> Unit, onResolve: (String) -> Unit,
    onOpenCache: () -> Unit, onRecovery: () -> Unit,
    modifier: Modifier = Modifier,
    onSelectKnown: (String) -> Unit = {}, onPrepareMigration: () -> Unit = {}, onActivateLegacy: (String) -> Unit = {},
    onPrepareArchive: () -> Unit = {}, onLoadArchive: (String) -> Unit = {},
    connectionContent: @Composable () -> Unit = {},
) {
    val identity = projectConfirmationIdentity(state)
    var migrationConfirmation by remember(identity, state.migrationStatus) { mutableStateOf<ProjectSummary?>(null) }
    var createForm by remember(identity) { mutableStateOf(false) }
    var displayName by rememberSaveable(identity) { mutableStateOf("") }
    var projectId by rememberSaveable(identity) { mutableStateOf(UUID.randomUUID().toString()) }
    var lookupId by rememberSaveable(identity) { mutableStateOf("") }
    var invalid by remember(identity) { mutableStateOf(false) }
    var selectConfirmation by remember(identity, state.projects) { mutableStateOf<ProjectSummary?>(null) }
    var createConfirmation by remember(identity, projectId, displayName) { mutableStateOf<Pair<String, String>?>(null) }
    var catalogPage by remember(identity, state.projects) { mutableIntStateOf(0) }
    val authoritative = state.verificationStatus == VerificationStatus.VERIFIED && state.catalogStatus == CatalogStatus.READY && !state.busy
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.profiles_title)) }
        Text(stringResource(R.string.projects_title), style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() })
        state.selectedServer?.let { Text(it.name); Text(it.canonicalUrl) }
        state.verifiedAccount?.let { Text(stringResource(R.string.server_account, it.name, it.id)) }
        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(when {
                state.verificationStatus == VerificationStatus.UNSUPPORTED -> R.string.catalog_unsupported
                state.catalogStatus == CatalogStatus.LOADING -> R.string.projects_loading
                state.catalogStatus == CatalogStatus.FAILED -> R.string.projects_error
                state.verificationStatus != VerificationStatus.VERIFIED -> R.string.projects_known
                state.catalogStatus != CatalogStatus.READY -> R.string.projects_stale
                state.projects.isEmpty() -> R.string.projects_empty
                else -> R.string.projects_current
            }))
            if (state.catalogStatus == CatalogStatus.LOADING) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.serverProblem?.let { Text(stringResource(serverProblemMessage(it)), color = MaterialTheme.colorScheme.error) }
        }
        OutlinedButton(onClick = onRefresh, enabled = state.verificationStatus == VerificationStatus.VERIFIED && !state.busy,
            modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.projects_refresh)) }
        if (state.migrationRequired) Text(stringResource(R.string.legacy_migration_required),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        val imports = state.legacyImports.filter { record ->
            val server = state.selectedServer
            server != null && record.localProfileId == server.localProfileId && record.canonicalUrl == server.canonicalUrl &&
                record.credentialId == server.credentialId
        }
        if (imports.isNotEmpty()) {
            Text(stringResource(R.string.legacy_imports), style = MaterialTheme.typography.titleSmall)
            imports.forEach { record ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.recovery_server, record.canonicalUrl))
                        Text(stringResource(R.string.legacy_selectors, record.spaceName))
                        Text(stringResource(R.string.recovery_account, record.lastAuthenticatedAccountId ?: stringResource(R.string.recovery_unknown)))
                        Text(stringResource(R.string.recovery_project, record.observedSpaceId ?: stringResource(R.string.recovery_unknown)))
                        Text(stringResource(R.string.legacy_unresolved))
                    }
                }
            }
        }
        val known = state.knownBindings.filter { binding ->
            val profile = state.selectedServer
            profile != null && binding.serverProfileId == profile.localProfileId && binding.canonicalUrl == profile.canonicalUrl &&
                binding.credentialId == profile.credentialId && binding.credentialRevision == profile.credentialRevision &&
                binding.expectedAccountId == profile.verification?.accountId &&
                (state.verifiedAccount == null || binding.expectedAccountId == state.verifiedAccount.id)
        }
        if (known.isNotEmpty()) {
            Text(stringResource(R.string.projects_known), style = MaterialTheme.typography.titleSmall)
            known.distinctBy { listOf(it.canonicalUrl, it.projectId, it.expectedAccountId, it.storageEpoch) }.forEach { binding ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.project_id, binding.projectId))
                        Text(stringResource(R.string.recovery_account, binding.expectedAccountId))
                        Text(stringResource(R.string.project_epoch, binding.storageEpoch))
                        OutlinedButton(onClick = { onSelectKnown(binding.bindingId) }, enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.open_documents)) }
                    }
                }
            }
        }
        if (state.projects.isNotEmpty()) {
            Text(stringResource(R.string.projects_page, catalogPage * 50 + 1, minOf((catalogPage + 1) * 50, state.projects.size), state.projects.size))
        }
        // Bounded local pages: a 10,000-row catalog must not mount 10,000 Compose cards.
        state.projects.drop(catalogPage * 50).take(50).forEach { project ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProjectIdentity(project)
                    if (state.selectedProject?.projectId == project.id && state.selectedProject.storageEpoch == project.storageEpoch)
                        Text(stringResource(R.string.project_selected))
                    OutlinedButton(onClick = { selectConfirmation = project }, enabled = authoritative,
                        modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.project_select)) }
                }
            }
        }
        if (state.projects.size > 50) {
            OutlinedButton(onClick = { catalogPage-- }, enabled = catalogPage > 0, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.projects_previous))
            }
            OutlinedButton(onClick = { catalogPage++ }, enabled = (catalogPage + 1) * 50 < state.projects.size, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.projects_next))
            }
        }
        OutlinedTextField(lookupId, { lookupId = it; invalid = false }, Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.project_lookup_id)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Ascii))
        OutlinedButton(onClick = {
            invalid = !validProjectId(lookupId)
            if (!invalid) onLookup(lookupId)
        }, enabled = state.verificationStatus == VerificationStatus.VERIFIED && !state.busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.project_lookup))
        }
        state.createIntents.forEach { intent ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(when (intent.outcome) {
                        CreateOutcome.UNKNOWN -> R.string.create_unknown
                        CreateOutcome.CREATED -> R.string.create_created
                        CreateOutcome.NOT_CREATED -> R.string.create_not_created
                        CreateOutcome.CONFLICT -> R.string.create_conflict
                    }), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    Text(stringResource(R.string.project_id, intent.id))
                    Text(intent.displayName)
                    Text(stringResource(R.string.create_resolution_notice))
                    intent.observedProject?.let { observed ->
                        ProjectIdentity(observed)
                        OutlinedButton(onClick = { selectConfirmation = observed }, enabled = authoritative,
                            modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.project_select)) }
                    }
                    OutlinedButton(onClick = { onResolve(intent.id) },
                        enabled = state.verificationStatus == VerificationStatus.VERIFIED && !state.busy && intent.id !in state.creatingProjectIds,
                        modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.create_check_original)) }
                }
            }
        }
        if (!createForm) {
            Button(onClick = { createForm = true }, enabled = authoritative, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.project_create))
            }
        } else {
            Text(stringResource(R.string.project_create), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.create_rules))
            OutlinedTextField(displayName, { displayName = it; invalid = false }, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.project_display_name)) }, singleLine = true, enabled = authoritative)
            OutlinedTextField(projectId, { projectId = it; invalid = false }, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.project_id_label)) }, singleLine = true, enabled = authoritative,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Ascii))
            Text(stringResource(R.string.project_id_rules), style = MaterialTheme.typography.bodySmall)
            Button(onClick = {
                val normalized = try { ProjectCatalogCodec.normalizeDisplayName(displayName) } catch (_: IllegalArgumentException) { null }
                invalid = !validProjectId(projectId) || normalized == null
                if (!invalid && normalized != null) createConfirmation = projectId to normalized
            }, enabled = authoritative, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.project_create_review)) }
            TextButton(onClick = { createForm = false; createConfirmation = null; invalid = false }) { Text(stringResource(R.string.cancel)) }
        }
        if (invalid) Text(stringResource(R.string.project_validation), color = MaterialTheme.colorScheme.error)
        if (imports.isNotEmpty()) {
            OutlinedButton(onClick = onPrepareArchive, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.legacy_prepare_offline))
            }
        }
        if (imports.isNotEmpty() || state.migrationRequired || state.migrationStatus != null) {
            OutlinedButton(onClick = onPrepareMigration,
                enabled = state.verificationStatus == VerificationStatus.VERIFIED && !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.legacy_prepare))
            }
        }
        state.migrationStatus?.let { migration ->
            Text(stringResource(R.string.legacy_notice))
            migration.candidates.forEach { candidate ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.recovery_server, candidate.namespace.server))
                        Text(stringResource(R.string.recovery_account, candidate.namespace.accountId))
                        Text(stringResource(R.string.recovery_project, candidate.namespace.spaceId))
                        Text(stringResource(R.string.legacy_selectors, candidate.selectors.joinToString(", ")))
                        Text(stringResource(if (candidate.archiveOnly) R.string.legacy_unresolved else R.string.legacy_candidate))
                        Text(stringResource(R.string.legacy_counts, candidate.counts.values.sum()))
                        OutlinedButton(onClick = { onLoadArchive(candidate.namespace.spaceId) }, enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.legacy_load_archive)) }
                        if (state.legacyArchive.any { it.namespace == candidate.namespace }) {
                            OutlinedButton(onClick = onRecovery, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.editor_recovery))
                            }
                        }
                        val project = state.projects.singleOrNull { it.id == candidate.namespace.spaceId && it.storageEpoch == candidate.namespace.spaceId }
                        if (!candidate.archiveOnly && project != null) {
                            OutlinedButton(onClick = { migrationConfirmation = project }, enabled = authoritative,
                                modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.legacy_accept)) }
                        }
                    }
                }
            }
        }
        connectionContent()
        OutlinedButton(onClick = onOpenCache, enabled = state.selectedProject != null, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.open_documents))
        }
        TextButton(onClick = onRecovery) { Text(stringResource(R.string.editor_recovery)) }
    }
    migrationConfirmation?.takeIf { authoritative }?.let { project ->
        IdentityConfirmation(state, project.id, project.displayName, project.storageEpoch,
            R.string.legacy_confirm_title, R.string.legacy_confirm_notice, R.string.legacy_accept,
            onDismiss = { migrationConfirmation = null }, onConfirm = { migrationConfirmation = null; onActivateLegacy(project.id) },
            extraContent = {
                state.migrationStatus?.candidates?.singleOrNull { it.namespace.spaceId == project.id }?.let { candidate ->
                    Text(stringResource(R.string.legacy_selectors, candidate.selectors.joinToString(", ")))
                    Text(stringResource(R.string.recovery_account, candidate.namespace.accountId))
                }
            })
    }
    selectConfirmation?.takeIf { authoritative }?.let { project ->
        IdentityConfirmation(state, project.id, project.displayName, project.storageEpoch,
            R.string.project_select_title, R.string.project_select_notice, R.string.project_select,
            onDismiss = { selectConfirmation = null }, onConfirm = { selectConfirmation = null; onSelect(project.id) })
    }
    createConfirmation?.takeIf { authoritative }?.let { (id, name) ->
        IdentityConfirmation(state, id, name, null, R.string.project_create_title, R.string.project_create_notice,
            R.string.project_create_confirm, onDismiss = { createConfirmation = null },
            onConfirm = { createConfirmation = null; createForm = false; onCreate(id, name) })
    }
}

fun validProjectId(id: String): Boolean = try { ProjectCatalogCodec.checkId(id); true } catch (_: IllegalArgumentException) { false }

/** Full metadata identity, never a display label. No credential value/hash is included. */
internal fun projectConfirmationIdentity(state: RemoteUiState): List<Any?> = listOf(
    state.selectedServer?.localProfileId, state.selectedServer?.canonicalUrl, state.selectedServer?.credentialId,
    state.selectedServer?.credentialRevision, state.verifiedAccount?.id, state.selectionGeneration,
    state.verificationStatus, state.catalogStatus,
)

@Composable
fun ProjectIdentity(project: ProjectSummary) {
    Text(project.displayName, style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.project_id, project.id))
    Text(stringResource(R.string.project_epoch, project.storageEpoch), style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun IdentityConfirmation(
    state: RemoteUiState, id: String, name: String, epoch: String?, title: Int, notice: Int, action: Int,
    onDismiss: () -> Unit, onConfirm: () -> Unit, extraContent: @Composable () -> Unit = {},
) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                state.selectedServer?.let { Text(it.name); Text(it.canonicalUrl) }
                state.verifiedAccount?.let { Text(stringResource(R.string.server_account, it.name, it.id)) }
                Text(name)
                Text(stringResource(R.string.project_id, id))
                epoch?.let { Text(stringResource(R.string.project_epoch, it)) }
                extraContent()
                Text(stringResource(notice))
            }
        }, confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(action)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}
