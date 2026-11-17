package com.voxarium.remote

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.voxarium.remote.data.security.*
import com.voxarium.remote.data.transport.*
import com.voxarium.remote.data.documents.IdentityMigrationStatus
import com.voxarium.remote.data.transport.DocumentVersion
import com.voxarium.remote.ui.connection.RemoteSession
import com.voxarium.remote.ui.connection.EditorChannel
import com.voxarium.remote.data.room.RoomStatus
import com.voxarium.remote.data.room.RoomRecoveryEntry
import com.voxarium.remote.data.documents.DocumentRecovery
import com.voxarium.remote.data.documents.DocumentDraft
import com.voxarium.remote.ui.editor.bridge.SecureEditorView
import com.voxarium.remote.ui.editor.bridge.EditorFormat

enum class VerificationStatus { UNVERIFIED, VERIFYING, VERIFIED, UNSUPPORTED, FAILED }
enum class CatalogStatus { IDLE, LOADING, READY, FAILED }

enum class IdentityWarning { PROJECT_IDENTITY_CHANGED, SEQUENCE_ROLLBACK, PROJECT_NOT_FOUND }
enum class LegacyArchiveKind { MIRROR, DRAFT, PENDING, RECOVERY, ROOM }
data class LegacyArchiveEntry(
    val id: String, val namespace: com.voxarium.remote.data.documents.DocumentNamespace,
    val path: String, val markdown: String?, val generation: Long?, val kind: LegacyArchiveKind,
    val rawItemCount: Int = 0,
    val reason: com.voxarium.remote.data.documents.RecoveryReason? = null,
) { override fun toString() = "LegacyArchiveEntry(redacted)" }

enum class RemoteStatus { DISCONNECTED, CONNECTING, SYNCING, ONLINE, OFFLINE }

/** Safe categories only: never put a server message, exception or credential in UI state. */
enum class RemoteError {
    INVALID_PROFILE, MISSING_CREDENTIAL, AUTHENTICATION, NETWORK, TLS,
    PROTOCOL_VERSION, INVALID_DATA, STORAGE, CREDENTIAL_STORAGE, SERVER,
    EDITOR_UNSUPPORTED, EDITOR_PORT,
}

data class RemoteUiState(
    val selectionGeneration: Long = 0L,
    val documentNamespace: com.voxarium.remote.data.documents.DocumentNamespace? = null,
    val serverProfiles: List<ServerProfile> = emptyList(),
    val selectedServer: ServerProfile? = null,
    val verificationStatus: VerificationStatus = VerificationStatus.UNVERIFIED,
    val verifiedAccount: RemoteUser? = null,
    val projects: List<ProjectSummary> = emptyList(),
    val catalogStatus: CatalogStatus = CatalogStatus.IDLE,
    val selectedProject: ProjectBinding? = null,
    val knownBindings: List<ProjectBinding> = emptyList(),
    val selectedProjectSummary: ProjectSummary? = null,
    val createIntents: List<CreateIntent> = emptyList(),
    val creatingProjectIds: Set<String> = emptySet(),
    val migrationStatus: IdentityMigrationStatus? = null,
    val migrationRequired: Boolean = false,
    val legacyArchive: List<LegacyArchiveEntry> = emptyList(),
    val identityWarning: IdentityWarning? = null,
    val legacyImports: List<LegacyImportRecord> = emptyList(),
    val serverProblem: ServerProblem? = null,
    val profiles: List<SavedProfile> = emptyList(),
    val selectedProfile: SavedProfile? = null,
    val status: RemoteStatus = RemoteStatus.DISCONNECTED,
    val error: RemoteError? = null,
    val documents: Map<String, DocumentVersion> = emptyMap(),
    val selectedPath: String? = null,
    val busy: Boolean = false,
    val cacheAvailable: Boolean = false,
    val connectionActive: Boolean = false,
    val documentEpoch: Long = 0L,
    val hasCaseCollisions: Boolean = false,
    val editor: EditorChannel? = null,
    val roomStatus: RoomStatus? = null,
    val recovery: List<RoomRecoveryEntry> = emptyList(),
    val documentRecovery: List<DocumentRecovery> = emptyList(),
    val localDrafts: List<DocumentDraft> = emptyList(),
) {
    override fun toString() = "RemoteUiState(status=$status, error=$error, busy=$busy)"
}

/** Activity recreation keeps this owner, its one repository and its active socket intact. */
class RemoteViewModel internal constructor(
    sessionFactory: ((RemoteUiState) -> Unit) -> RemoteSession,
) : ViewModel() {
    private val mutableState = mutableStateOf(RemoteUiState(busy = true))
    val state: State<RemoteUiState> = mutableState
    private val session = sessionFactory { mutableState.value = it }
    private var retainedChannel: EditorChannel? = null
    // Only applicationContext is accepted below; never an Activity. Explicit channel retirement
    // releases this retained renderer after the native checkpoint/Dispose barrier.
    @android.annotation.SuppressLint("StaticFieldLeak")
    private var retainedView: SecureEditorView? = null

    /** Application context avoids retaining a destroyed Activity across rotation. The renderer
     * survives reattachment and is destroyed only after coordinator disposal/loss handling. */
    fun editorView(context: Context, channel: EditorChannel): SecureEditorView? {
        if(state.value.editor !== channel || channel.isRetired) return null
        if (retainedChannel !== channel) {
            retainedView?.destroy()
            retainedChannel=channel
            retainedView=SecureEditorView(context.applicationContext,onEvent=channel::event,onFailure={
                channel.failure(it == com.voxarium.remote.ui.editor.bridge.EditorBridgeFailure.UNSUPPORTED_WEBVIEW)
            })
            channel.onRetired {
                if(retainedChannel === channel) {
                    retainedView?.destroy();retainedView=null;retainedChannel=null
                }
            }
            channel.onHalt {
                if(retainedChannel === channel) {
                    retainedView?.findFocus()?.clearFocus()
                    retainedView?.visibility=android.view.View.INVISIBLE
                }
            }
            retainedView?.let { channel.attach(it) }
        }
        return retainedView?.takeUnless { channel.isRetired }
    }

    fun releaseClosedEditor() {
        if (state.value.editor == null) {
            retainedView?.destroy();retainedView=null;retainedChannel=null
        }
    }
    fun formatEditor(context: Context, channel: EditorChannel, action: String) {
        if (state.value.editor !== channel) return
        EditorFormat.entries.firstOrNull { it.wireName == action }?.let { editorView(context,channel)?.format(it) }
    }

    fun saveProfile(label: String, server: String, space: String, key: String, onSaved: () -> Unit) =
        session.saveProfile(label, server, space, key, onSaved)
    fun selectProfile(id: String) = session.selectProfile(id)
    fun saveServerProfile(name: String, server: String, key: String, onSaved: () -> Unit) =
        session.saveServerProfile(name, server, key, onSaved)
    fun selectServerProfile(id: String) = session.selectServerProfile(id)
    fun replaceServerKey(id: String, key: String, expectedGeneration: Long = state.value.selectionGeneration, onSaved: () -> Unit) =
        session.replaceServerKey(id, key, expectedGeneration, onSaved)
    fun verifyServer(expectedGeneration: Long = state.value.selectionGeneration) = session.verifyServer(expectedGeneration)
    fun refreshProjects(expectedGeneration: Long = state.value.selectionGeneration) = session.refreshProjects(expectedGeneration)
    fun lookupProject(id: String, expectedGeneration: Long = state.value.selectionGeneration) = session.lookupProject(id, expectedGeneration)
    fun createProject(id: String, displayName: String, expectedGeneration: Long = state.value.selectionGeneration) = session.createProject(id, displayName, expectedGeneration)
    fun resolveCreate(id: String, expectedGeneration: Long = state.value.selectionGeneration) = session.resolveCreate(id, expectedGeneration)
    fun selectProject(id: String, expectedGeneration: Long = state.value.selectionGeneration) = session.selectProject(id, expectedGeneration)
    fun selectKnownProject(bindingId: String, expectedGeneration: Long = state.value.selectionGeneration) = session.selectKnownProject(bindingId, expectedGeneration)
    fun prepareLegacyArchive(expectedGeneration: Long = state.value.selectionGeneration) = session.prepareLegacyArchive(expectedGeneration)
    fun loadLegacyArchive(projectId: String, expectedGeneration: Long = state.value.selectionGeneration) = session.loadLegacyArchive(projectId, expectedGeneration)
    fun prepareLegacyMigration(expectedGeneration: Long = state.value.selectionGeneration) = session.prepareLegacyMigration(expectedGeneration)
    fun activateLegacyProject(id: String, expectedGeneration: Long = state.value.selectionGeneration) = session.activateLegacyProject(id, expectedGeneration)
    /** Explicit connect of the selected, verified v2 identity; never creates a project. */
    fun connect() = connect(state.value.selectedServer?.localProfileId)
    fun connect(expectedProfileId: String?, expectedGeneration: Long = state.value.selectionGeneration) = session.connect(expectedProfileId, expectedGeneration)
    fun disconnect() = session.disconnect()
    fun retry() = retry(state.value.selectedServer?.localProfileId)
    fun retry(expectedProfileId: String?, expectedGeneration: Long = state.value.selectionGeneration) = session.connect(expectedProfileId, expectedGeneration)
    fun openDocument(path: String) = openDocument(path, state.value.documentEpoch)
    fun openDocument(path: String, expectedEpoch: Long) = session.openDocument(path, expectedEpoch)
    fun closeDocument() = session.closeDocument()
    fun openEditor(path: String, expectedEpoch: Long) = session.openEditor(path, expectedEpoch)
    fun backgroundEditor() = session.backgroundEditor()
    fun foregroundEditor() = session.foregroundEditor()
    fun tickEditor() = session.tickEditor()
    fun loadRecovery() = session.loadRecovery()

    override fun onCleared() { session.close();releaseClosedEditor() }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val application = context.applicationContext
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(RemoteViewModel::class.java))
                    val main = Handler(Looper.getMainLooper())
                    @Suppress("UNCHECKED_CAST")
                    return RemoteViewModel { observer ->
                        RemoteSession.open(application, { main.post(it) }, observer)
                    } as T
                }
            }
        }
    }
}
