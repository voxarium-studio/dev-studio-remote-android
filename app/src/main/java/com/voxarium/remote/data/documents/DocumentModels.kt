package com.voxarium.remote.data.documents

import com.voxarium.remote.data.transport.*
import java.security.MessageDigest
import com.voxarium.remote.data.security.validCanonicalIdentityUrl
import com.voxarium.remote.data.security.validIdentityValue
import com.voxarium.remote.data.security.validProjectIdentity
import com.voxarium.remote.data.security.validStorageEpoch

/** Exact UTF-8, never newline/frontmatter normalization (protocol §3.4). */
fun documentHash(content: String): String = MessageDigest.getInstance("SHA-256")
    .digest(content.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

data class DocumentNamespace(val server: String, val accountId: String, val spaceId: String, val storageEpoch: String? = null) {
    // Null retains the exact v1 encoding; never reinterpret a v1 key as a v2 partition.
    internal fun storageKey() = (if (storageEpoch == null) listOf(server, accountId, spaceId)
        else listOf("documents.partition.v2", "canonicalizer.1", server, spaceId, accountId, storageEpoch))
        .joinToString("") { "${it.length}:$it" }
    override fun toString() = "DocumentNamespace(redacted)"
}

enum class DraftKind { LOCAL, ROOM, CONFLICT_COPY }
data class DocumentDraft(
    val path: String, val content: String, val generation: Long,
    val base: DocumentVersion?, val kind: DraftKind,
) {
    override fun toString() = "DocumentDraft(redacted)"
}

enum class RecoveryReason { CONFLICT, OFFLINE_OR_RESTART, SPACE_REPLACED, REJECTED, REMOTE_RESET, SEQUENCE_ROLLBACK }
/** Both variants are durable BEFORE accepting Remote or offering a network write. */
data class DocumentRecovery(
    val id: Long, val spaceId: String, val path: String, val content: String,
    val generation: Long, val remote: DocumentVersion?, val reason: RecoveryReason,
    val copyPath: String?,
) {
    override fun toString() = "DocumentRecovery(redacted)"
}

data class PendingDocumentWrite(
    val id: Long, val path: String, val content: String, val generation: Long,
    val baseRev: Long, val live: String?, val connectionGeneration: Long,
) {
    val hash: String get() = documentHash(content)
    override fun toString() = "PendingDocumentWrite(redacted)"
}

data class DocumentsView(
    val namespace: DocumentNamespace?, val synced: Boolean, val cursor: Long,
    val documents: Map<String, DocumentVersion>, val drafts: Map<String, DocumentDraft>,
    val pending: Map<String, PendingDocumentWrite>, val recoveries: List<DocumentRecovery>,
    val caseCollisions: Set<String>,
) { override fun toString() = "DocumentsView(redacted)" }

/** Opaque, process/connection/room-incarnation-bound permission; never persist or expose to JS. */
class RoomWriteToken internal constructor(internal val nonce: String) {
    override fun toString() = "RoomWriteToken(redacted)"
}

/** Capture once with a native document intent. A replacement space.id invalidates this scope. */
class DocumentScope internal constructor(internal val owner: Long, internal val namespace: DocumentNamespace) {
    override fun toString() = "DocumentScope(redacted)"
}

data class DocumentSend(
    val connectionGeneration: Long, val frame: ClientFrame, val owner: Long,
    internal val roomNonce: String? = null,
    internal val namespace: DocumentNamespace? = null,
) {
    override fun toString() = "DocumentSend(redacted)"
}

enum class DocumentFailure { NOT_SYNCED, STALE_ROOM, NOT_SCRIBE, PATH_OCCUPIED, NO_DRAFT, IN_FLIGHT, INVALID_FRAME, CORRUPT_JOURNAL, STORAGE, NAME_EXHAUSTED }
class DocumentRepositoryException(val reason: DocumentFailure) : IllegalStateException("Documents: ${reason.name}")

internal data class Mirror(val complete: Boolean = false, val cursor: Long = 0, val files: Map<String, DocumentVersion> = emptyMap())
internal data class SpaceJournal(
    val drafts: Map<String, DocumentDraft> = emptyMap(),
    val pending: Map<String, PendingDocumentWrite> = emptyMap(),
    val recoveries: List<DocumentRecovery> = emptyList(),
)
internal data class Journal(
    val nextId: Long = 1, val nextGeneration: Long = 1,
    val spaces: Map<String, SpaceJournal> = emptyMap(), val lastSpace: Map<String, String> = emptyMap(),
)

/** Private durable identity, independent of UI/security DTOs (dev spec §16.8).
 * Values are exact persisted canonical values, never normalized by migration. */
data class DocumentPartition(
    val canonicalServerUrl: String, val projectId: String, val accountId: String, val storageEpoch: String,
) {
    init {
        require(validCanonicalIdentityUrl(canonicalServerUrl))
        require(validIdentityValue(accountId))
        require(validProjectIdentity(projectId))
        require(validStorageEpoch(storageEpoch))
    }
    val namespace get() = DocumentNamespace(canonicalServerUrl, accountId, projectId, storageEpoch)
    internal fun journalKey() = "partition-journal.v2:" + namespace.storageKey()
    override fun toString() = "DocumentPartition(redacted)"
}
