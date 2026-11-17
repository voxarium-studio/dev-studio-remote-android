package com.voxarium.remote

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.os.ConfigurationCompat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.viewinterop.AndroidView
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.voxarium.remote.ui.connection.EditorChannel
import com.voxarium.remote.ui.editor.*
import com.voxarium.remote.data.room.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import com.voxarium.remote.ui.connection.*
import com.voxarium.remote.ui.documents.DocumentsList
import com.voxarium.remote.ui.documents.MarkdownReader

/** One lifecycle-owned session: online paths join rooms; offline cache stays a native reader. */
@Composable
fun VoxariumRemoteApp() {
    val context = LocalContext.current
    val owner = context as ViewModelStoreOwner
    val model = remember(owner) {
        ViewModelProvider(owner, RemoteViewModel.factory(context.applicationContext))[RemoteViewModel::class.java]
    }
    val state = model.state.value
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle,model) {
        val observer=LifecycleEventObserver { _,event -> when(event) {
            Lifecycle.Event.ON_STOP -> model.backgroundEditor()
            Lifecycle.Event.ON_START -> model.foregroundEditor()
            else -> Unit
        } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state.editor) { if(state.editor == null) model.releaseClosedEditor() }
    RemoteScreen(state, model::saveServerProfile, model::selectServerProfile,
        { model.connect(state.selectedServer?.localProfileId, state.selectionGeneration) }, model::disconnect,
        { model.retry(state.selectedServer?.localProfileId, state.selectionGeneration) },
        { path -> if(state.status == RemoteStatus.ONLINE) model.openEditor(path,state.documentEpoch)
            else model.openDocument(path,state.documentEpoch) }, model::closeDocument,
        onOpenEditor={ path -> model.openEditor(path,state.documentEpoch) },
        onLoadRecovery=model::loadRecovery,
        onVerify = { model.verifyServer(state.selectionGeneration) }, onRefresh = { model.refreshProjects(state.selectionGeneration) },
        onLookup = { model.lookupProject(it, state.selectionGeneration) }, onSelectProject = { model.selectProject(it, state.selectionGeneration) },
        onCreate = { id, name -> model.createProject(id, name, state.selectionGeneration) }, onResolveCreate = { model.resolveCreate(it, state.selectionGeneration) },
        onReplaceKey = { id, key, saved -> model.replaceServerKey(id, key, expectedGeneration = state.selectionGeneration, onSaved = saved) },
        onSelectKnown = { model.selectKnownProject(it, state.selectionGeneration) },
        onPrepareMigration = { model.prepareLegacyMigration(expectedGeneration = state.selectionGeneration) },
        onPrepareArchive = { model.prepareLegacyArchive(state.selectionGeneration) },
        onLoadArchive = { model.loadLegacyArchive(it, state.selectionGeneration) },
        onActivateLegacy = { model.activateLegacyProject(it, state.selectionGeneration) },
        editorContent={ channel ->
            val view=remember(channel) { model.editorView(context,channel) }
            val dark=isSystemInDarkTheme()
            val language=if(ConfigurationCompat.getLocales(LocalConfiguration.current)[0]?.language == "ru") "ru" else "en"
            if(view != null) {
                SideEffect { view.configure(dark,language) }
                AndroidView(factory={
                    (view.parent as? ViewGroup)?.removeView(view)
                    view
                },modifier=Modifier.fillMaxSize())
            } else Text(stringResource(R.string.editor_status_closing))
        },
        onFormat={ action -> state.editor?.let { channel ->
            model.formatEditor(context,channel,action)
        } })
}

/** Injectable state/intents for tests; native owners fence every captured identity again. */
@Composable
fun RemoteScreen(
    state: RemoteUiState,
    onSave: (String, String, String, () -> Unit) -> Unit,
    onSelect: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRetry: () -> Unit,
    onOpen: (String) -> Unit,
    onCloseDocument: () -> Unit,
    onOpenEditor: ((String) -> Unit)? = null,
    onLoadRecovery: () -> Unit = {},
    editorContent: (@Composable (EditorChannel) -> Unit)? = null,
    onFormat: (String) -> Unit = {},
    onVerify: () -> Unit = {}, onRefresh: () -> Unit = {},
    onLookup: (String) -> Unit = {}, onSelectProject: (String) -> Unit = {},
    onCreate: (String, String) -> Unit = { _, _ -> }, onResolveCreate: (String) -> Unit = {},
    onReplaceKey: (String, String, () -> Unit) -> Unit = { _, _, _ -> },
    onSelectKnown: (String) -> Unit = {}, onPrepareMigration: () -> Unit = {}, onActivateLegacy: (String) -> Unit = {},
    onPrepareArchive: () -> Unit = {}, onLoadArchive: (String) -> Unit = {},
) {
    val identity = projectConfirmationIdentity(state)
    val binding = state.selectedProject
    val server = state.selectedServer
    var recoveryPage by remember(identity, state.documentEpoch) { mutableStateOf(false) }
    var recoveryEntry by remember(identity, state.documentEpoch) { mutableStateOf<RecoveryPreview?>(null) }
    val showRecovery = { onLoadRecovery(); recoveryPage = true }
    var page by rememberSaveable { mutableStateOf("servers") }
    var newProfile by rememberSaveable { mutableStateOf(false) }
    var replaceKeyId by remember(identity) { mutableStateOf<String?>(null) }
    var confirmBindingId by remember(identity, binding?.bindingId) { mutableStateOf<String?>(null) }
    var retryRequested by remember { mutableStateOf(false) }
    val requestRetry = { retryRequested = true; confirmBindingId = binding?.bindingId }
    val requestConnect = { retryRequested = false; confirmBindingId = binding?.bindingId }
    val selected = state.selectedPath?.let { state.documents[it] }
    BackHandler(enabled = recoveryPage || state.editor != null || newProfile || replaceKeyId != null || page != "servers" || selected != null || confirmBindingId != null) {
        when {
            recoveryEntry != null -> recoveryEntry = null
            recoveryPage -> recoveryPage = false
            state.editor != null -> onCloseDocument()
            confirmBindingId != null -> confirmBindingId = null
            newProfile -> newProfile = false
            replaceKeyId != null -> replaceKeyId = null
            selected != null -> onCloseDocument()
            else -> page = "servers"
        }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime)),
            contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 680.dp).fillMaxSize().padding(horizontal = 16.dp)) {
                if (recoveryPage) {
                    val entry = recoveryEntry
                    Column(Modifier.fillMaxSize()) {
                        state.error?.let { Text(stringResource(errorMessage(it)), color = MaterialTheme.colorScheme.error) }
                        if (entry == null) RecoveryHub(recoveryPreviews(state), { recoveryPage = false }, { recoveryEntry = it }, Modifier.weight(1f))
                        else RecoveryPreviewScreen(entry, { recoveryEntry = null }, Modifier.weight(1f))
                    }
                } else if (state.editor != null && editorContent != null) {
                    Column(Modifier.fillMaxSize()) {
                        state.error?.let { Text(stringResource(errorMessage(it)), color = MaterialTheme.colorScheme.error) }
                        EditorScreen(state.selectedPath.orEmpty(), state.roomStatus, state.roomStatus == RoomStatus.CLOSING || state.error != null,
                            onCloseDocument, onFormat, showRecovery, Modifier.weight(1f)) { editorContent(state.editor) }
                    }
                } else if (newProfile || replaceKeyId != null) {
                    ProfileForm(state.busy, onCancel = { newProfile = false; replaceKeyId = null }, onSave = { label, url, key ->
                        val replacement = replaceKeyId
                        if (replacement != null) onReplaceKey(replacement, key) { replaceKeyId = null }
                        else onSave(label, url, key) { newProfile = false }
                    }, error = state.error?.let { stringResource(errorMessage(it)) },
                        initialName = if (replaceKeyId != null) server?.name.orEmpty() else "",
                        initialServer = if (replaceKeyId != null) server?.canonicalUrl.orEmpty() else "",
                        keyOnly = replaceKeyId != null)
                } else if (selected != null) {
                    MarkdownReader(state.selectedPath, selected, onCloseDocument, Modifier.fillMaxSize()) {
                        SessionStatus(state, requestRetry)
                        ConnectionAction(state, requestConnect, onDisconnect)
                        if (state.status == RemoteStatus.ONLINE && onOpenEditor != null) {
                            Button(onClick = { onOpenEditor(state.selectedPath) }) { Text(stringResource(R.string.editor_open)) }
                        }
                        TextButton(onClick = showRecovery) { Text(stringResource(R.string.editor_recovery)) }
                        state.roomStatus?.let { Text(stringResource(editorStatusLabel(it))) }
                    }
                } else if (page == "servers") {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.profiles_title), style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.semantics { heading() })
                        Text(stringResource(R.string.profiles_explanation))
                        SessionStatus(state, requestRetry)
                        if (state.serverProfiles.isEmpty()) Text(stringResource(R.string.profiles_empty))
                        state.serverProfiles.forEach { saved ->
                            OutlinedButton(onClick = { onCloseDocument(); onSelect(saved.localProfileId) }, modifier = Modifier.fillMaxWidth(), enabled = !state.busy) {
                                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(saved.name, style = MaterialTheme.typography.titleMedium)
                                    Text(saved.canonicalUrl)
                                    saved.verification?.let { history ->
                                        Text(stringResource(R.string.server_historical, history.accountDisplayName, history.accountId,
                                            java.text.DateFormat.getDateTimeInstance().format(java.util.Date(history.checkedAtEpochMillis))))
                                    } ?: Text(stringResource(R.string.server_unverified))
                                    if (server?.localProfileId == saved.localProfileId) Text(stringResource(R.string.profile_selected))
                                }
                            }
                        }
                        Button(onClick = { newProfile = true }, modifier = Modifier.fillMaxWidth(), enabled = !state.busy) {
                            Text(stringResource(R.string.profile_add))
                        }
                        if (server != null) {
                            ServerVerificationStatus(state)
                            Button(onClick = onVerify, enabled = !state.busy && state.verificationStatus != VerificationStatus.VERIFYING,
                                modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.server_verify)) }
                            OutlinedButton(onClick = { page = "projects" }, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.server_projects))
                            }
                            OutlinedButton(onClick = { replaceKeyId = server.localProfileId }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.server_replace_key))
                            }
                            SelectedProjectIdentity(state)
                            ConnectionAction(state, requestConnect, onDisconnect)
                            OutlinedButton(onClick = { page = "documents" }, enabled = binding != null, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.open_documents))
                            }
                        }
                        TextButton(onClick = showRecovery) { Text(stringResource(R.string.editor_recovery)) }
                        Text(stringResource(R.string.privacy_note), style = MaterialTheme.typography.bodySmall)
                    }
                } else if (page == "projects") {
                    ProjectsScreen(state, { page = "servers" }, onRefresh, onLookup, onSelectProject, onCreate, onResolveCreate,
                        { page = "documents" }, showRecovery,
                        onSelectKnown = { onSelectKnown(it); page = "documents" },
                        onPrepareMigration = onPrepareMigration, onActivateLegacy = onActivateLegacy,
                        onPrepareArchive = onPrepareArchive, onLoadArchive = onLoadArchive, connectionContent = {
                            SelectedProjectIdentity(state)
                            SessionStatus(state, requestRetry)
                            ConnectionAction(state, requestConnect, onDisconnect)
                        })
                } else {
                    key(binding?.bindingId, state.documentEpoch) {
                        DocumentsList(state.documents, onOpen, Modifier.fillMaxSize(), header = {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                TextButton(onClick = { page = "projects" }) { Text(stringResource(R.string.projects_title)) }
                                SelectedProjectIdentity(state)
                                SessionStatus(state, requestRetry)
                                ConnectionAction(state, requestConnect, onDisconnect)
                                Text(stringResource(R.string.reader_scope))
                                TextButton(onClick = showRecovery) { Text(stringResource(R.string.editor_recovery)) }
                                state.roomStatus?.let { Text(stringResource(editorStatusLabel(it))) }
                                if (state.documents.isEmpty() && state.status != RemoteStatus.ONLINE)
                                    Text(stringResource(if (state.status == RemoteStatus.SYNCING || state.status == RemoteStatus.CONNECTING)
                                        R.string.documents_loading else R.string.documents_no_cache))
                            }
                        })
                    }
                }
            }
        }
    }
    if (confirmBindingId != null && binding != null && binding.bindingId == confirmBindingId && canConnectProject(state)) {
        IdentityConfirmation(state, binding.projectId, state.selectedProjectSummary?.displayName.orEmpty(), binding.storageEpoch,
            R.string.project_connect_title, R.string.project_connect_notice, R.string.project_connect_confirm,
            onDismiss = { confirmBindingId = null }, onConfirm = {
                confirmBindingId = null
                page = "documents"
                if (retryRequested) onRetry() else onConnect()
            })
    }
}

internal fun canConnectProject(state: RemoteUiState): Boolean {
    val binding = state.selectedProject ?: return false
    val server = state.selectedServer ?: return false
    return state.verificationStatus == VerificationStatus.VERIFIED && !state.busy && !state.migrationRequired &&
        binding.serverProfileId == server.localProfileId && binding.canonicalUrl == server.canonicalUrl &&
        binding.credentialId == server.credentialId && binding.credentialRevision == server.credentialRevision &&
        binding.expectedAccountId == state.verifiedAccount?.id
}

@Composable
private fun SelectedProjectIdentity(state: RemoteUiState) {
    state.selectedProject?.let {
        state.selectedServer?.let { server -> Text(server.canonicalUrl) }
        state.selectedProjectSummary?.let { summary -> Text(summary.displayName, style = MaterialTheme.typography.titleMedium) }
        Text(stringResource(R.string.project_id, it.projectId))
        Text(stringResource(R.string.server_account, state.verifiedAccount?.name.orEmpty(), it.expectedAccountId))
        Text(stringResource(R.string.project_epoch, it.storageEpoch))
    }
}

@Composable
private fun ServerVerificationStatus(state: RemoteUiState) {
    Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(when (state.verificationStatus) {
            VerificationStatus.UNVERIFIED -> R.string.server_unverified
            VerificationStatus.VERIFYING -> R.string.server_verifying
            VerificationStatus.VERIFIED -> R.string.server_verified
            VerificationStatus.UNSUPPORTED -> R.string.catalog_unsupported
            VerificationStatus.FAILED -> R.string.server_failed
        }))
        state.verifiedAccount?.let { Text(stringResource(R.string.server_account, it.name, it.id)) }
        state.serverProblem?.let { Text(stringResource(serverProblemMessage(it)), color = MaterialTheme.colorScheme.error) }
        if (state.verificationStatus == VerificationStatus.VERIFYING) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
private fun ConnectionAction(state: RemoteUiState, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    if (state.connectionActive || state.status in setOf(RemoteStatus.CONNECTING, RemoteStatus.SYNCING, RemoteStatus.ONLINE)) {
        OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.disconnect)) }
    } else if (state.selectedProject != null) {
        Button(onClick = onConnect, enabled = canConnectProject(state), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.connect)) }
    }
}

@Composable
fun SessionStatus(state: RemoteUiState, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(when (state.status) {
            RemoteStatus.DISCONNECTED -> R.string.status_disconnected
            RemoteStatus.CONNECTING -> R.string.status_connecting
            RemoteStatus.SYNCING -> R.string.status_syncing
            RemoteStatus.ONLINE -> R.string.status_online
            RemoteStatus.OFFLINE -> R.string.status_offline
        }), style = MaterialTheme.typography.titleSmall)
        if (state.busy || state.status == RemoteStatus.CONNECTING || state.status == RemoteStatus.SYNCING) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        state.error?.let {
            Text(stringResource(errorMessage(it)), color = MaterialTheme.colorScheme.error)
            if (canConnectProject(state) && !state.connectionActive) {
                OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            }
        }
        if (state.cacheAvailable && state.documents.isNotEmpty() && state.status != RemoteStatus.ONLINE)
            Text(stringResource(R.string.cache_offline_notice))
        if (state.connectionActive && state.status == RemoteStatus.OFFLINE) Text(stringResource(R.string.reconnect_pending))
        if (state.hasCaseCollisions) Text(stringResource(R.string.case_collision_warning))
        state.identityWarning?.let { warning -> Text(stringResource(when(warning) {
            IdentityWarning.PROJECT_IDENTITY_CHANGED -> R.string.project_identity_changed
            IdentityWarning.SEQUENCE_ROLLBACK -> R.string.recovery_reason_rollback
            IdentityWarning.PROJECT_NOT_FOUND -> R.string.error_project_missing
        }), color = MaterialTheme.colorScheme.error) }
    }
}

/** Enumerated safe native errors only, never server exception bodies or credentials. */
fun errorMessage(error: RemoteError): Int = when (error) {
    RemoteError.AUTHENTICATION, RemoteError.MISSING_CREDENTIAL -> R.string.error_auth
    RemoteError.PROTOCOL_VERSION -> R.string.error_version
    RemoteError.TLS -> R.string.error_tls
    RemoteError.INVALID_PROFILE -> R.string.error_configuration
    RemoteError.NETWORK -> R.string.error_network
    RemoteError.STORAGE, RemoteError.CREDENTIAL_STORAGE -> R.string.error_storage
    RemoteError.INVALID_DATA, RemoteError.SERVER -> R.string.error_protocol
    RemoteError.EDITOR_UNSUPPORTED -> R.string.editor_error_unsupported
    RemoteError.EDITOR_PORT -> R.string.editor_error_port
}

/** Stable native categories only; never display server message/reason/body or throwable text. */
fun serverProblemMessage(problem: com.voxarium.remote.data.transport.ServerProblem): Int = when (problem) {
    com.voxarium.remote.data.transport.ServerProblem.UNAUTHORIZED -> R.string.error_auth
    com.voxarium.remote.data.transport.ServerProblem.TLS -> R.string.error_tls
    com.voxarium.remote.data.transport.ServerProblem.TIMEOUT -> R.string.error_timeout
    com.voxarium.remote.data.transport.ServerProblem.NETWORK -> R.string.error_network
    com.voxarium.remote.data.transport.ServerProblem.REDIRECT -> R.string.error_redirect
    com.voxarium.remote.data.transport.ServerProblem.UNSUPPORTED -> R.string.catalog_unsupported
    com.voxarium.remote.data.transport.ServerProblem.INVALID_INPUT -> R.string.project_validation
    com.voxarium.remote.data.transport.ServerProblem.STALE_REQUEST -> R.string.error_stale
    com.voxarium.remote.data.transport.ServerProblem.PROJECT_EXISTS -> R.string.error_project_exists
    com.voxarium.remote.data.transport.ServerProblem.PROJECT_NOT_FOUND,
    com.voxarium.remote.data.transport.ServerProblem.LEGACY_NOT_FOUND -> R.string.error_project_missing
    com.voxarium.remote.data.transport.ServerProblem.CREATE_UNCERTAIN -> R.string.create_unknown
    com.voxarium.remote.data.transport.ServerProblem.INVALID_RESPONSE,
    com.voxarium.remote.data.transport.ServerProblem.HTTP_ERROR -> R.string.error_protocol
}
