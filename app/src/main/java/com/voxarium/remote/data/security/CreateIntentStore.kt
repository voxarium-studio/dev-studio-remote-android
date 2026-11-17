package com.voxarium.remote.data.security

import android.content.Context
import com.voxarium.remote.data.transport.ProjectSummary
import java.io.File
import java.security.MessageDigest
import java.util.UUID

enum class CreateOutcome { UNKNOWN, CREATED, NOT_CREATED, CONFLICT }

/** Durable evidence of an explicit POST intent, never a project selection or replay grant. */
data class CreateIntent(
    val attemptId: String, val profileId: String, val canonicalUrl: String,
    val accountId: String, val credentialId: String, val credentialRevision: Long,
    val id: String, val displayName: String, val outcome: CreateOutcome = CreateOutcome.UNKNOWN,
    val observedProject: ProjectSummary? = null,
) {
    override fun toString() = "CreateIntent([redacted], outcome=$outcome)"
}

/** Android identity design §4.2: fsync + exact readback BEFORE POST. A process restart sees
 * UNKNOWN even if the response was lost. Resolution is GET of this original ID, never replay.
 * Scoped by exact canonical URL/account/ID, not profile label or key reference. */
class CreateIntentStore(private val storage: BlobStorage) {
    fun list(): List<CreateIntent> = synchronized(STORE_LOCK) { read() }

    fun prepare(profile: ServerProfile, accountId: String, id: String, displayName: String): CreateIntent =
        synchronized(STORE_LOCK) {
            val current = read()
            val previous = current.find { it.canonicalUrl == profile.canonicalUrl && it.accountId == accountId && it.id == id }
            // Only a definitive original-ID 404 permits a NEW explicit attempt.
            if (previous != null && previous.outcome != CreateOutcome.NOT_CREATED) invalid()
            val next = CreateIntent(UUID.randomUUID().toString(), profile.localProfileId, profile.canonicalUrl,
                accountId, profile.credentialId, profile.credentialRevision, id, displayName)
            validate(next)
            write(current.filterNot { it === previous } + next)
            next
        }

    /** A late reply for an older attempt cannot change a newer explicit attempt. */
    fun resolve(intent: CreateIntent, outcome: CreateOutcome, project: ProjectSummary? = null): CreateIntent =
        synchronized(STORE_LOCK) {
            val current = read()
            val old = current.singleOrNull { it.attemptId == intent.attemptId } ?: invalid()
            if (old != intent) invalid()
            val next = old.copy(outcome = outcome, observedProject = project)
            validate(next)
            write(current.map { if (it.attemptId == old.attemptId) next else it })
            next
        }

    private fun validate(v: CreateIntent) {
        if (!validReference(v.attemptId) || !validReference(v.profileId) || !validCanonicalIdentityUrl(v.canonicalUrl) ||
            !validIdentityValue(v.accountId) || !validReference(v.credentialId) || v.credentialRevision < 1 ||
            !validProjectIdentity(v.id) || v.displayName.isEmpty() || v.displayName.length > 1024 ||
            v.displayName.toByteArray(Charsets.UTF_8).size > 4096) invalid()
        val p = v.observedProject
        if (p != null && (p.id != v.id || !validStorageEpoch(p.storageEpoch) || p.displayName.length > 4096 ||
                p.metadataRevision < 1 || p.createdAt.length > 256 || v.outcome != CreateOutcome.CREATED)) invalid()
        if ((v.outcome == CreateOutcome.CREATED) != (p != null)) invalid()
    }
    private fun read(): List<CreateIntent> {
        val bytes = try { storage.read() } catch (_: Exception) { throw SecureStoreException(SecureStoreException.Reason.STORAGE) }
            ?: return emptyList()
        if (bytes.size < 40 || bytes.size > MAX_BLOB_BYTES) corrupt()
        val payload = bytes.copyOfRange(0, bytes.size - 32)
        if (!payload.take(HEADER.size).toByteArray().contentEquals(HEADER) ||
            !MessageDigest.isEqual(hash(payload), bytes.copyOfRange(bytes.size - 32, bytes.size))) corrupt()
        return decode(payload.copyOfRange(HEADER.size, payload.size)) {
            val count = readInt().also { if (it !in 0..256) corrupt() }
            List(count) {
                val attempt = readUTF(); val profile = readUTF(); val url = readUTF(); val account = readUTF()
                val credential = readUTF(); val revision = readLong(); val id = readUTF(); val name = readUTF()
                val outcome = CreateOutcome.entries.getOrNull(readInt()) ?: corrupt()
                val observed = if (readBoolean()) ProjectSummary(id, readUTF(), readUTF(), readLong(), readUTF()) else null
                CreateIntent(attempt, profile, url, account, credential, revision, id, name, outcome, observed).also(::validate)
            }.also { rows ->
                if (rows.map { Triple(it.canonicalUrl, it.accountId, it.id) }.distinct().size != rows.size ||
                    rows.map { it.attemptId }.distinct().size != rows.size) corrupt()
            }
        }
    }
    private fun write(rows: List<CreateIntent>) {
        if (rows.size > 256) invalid()
        val payload = HEADER + encode {
            writeInt(rows.size)
            rows.forEach { v ->
                validate(v)
                writeUTF(v.attemptId); writeUTF(v.profileId); writeUTF(v.canonicalUrl); writeUTF(v.accountId)
                writeUTF(v.credentialId); writeLong(v.credentialRevision); writeUTF(v.id); writeUTF(v.displayName)
                writeInt(v.outcome.ordinal); writeBoolean(v.observedProject != null)
                v.observedProject?.let { writeUTF(it.displayName); writeUTF(it.storageEpoch); writeLong(it.metadataRevision); writeUTF(it.createdAt) }
            }
        }
        val bytes = payload + hash(payload)
        if (bytes.size > MAX_BLOB_BYTES) invalid()
        try {
            storage.write(bytes)
            if (storage.read()?.contentEquals(bytes) != true) throw IllegalStateException()
        } catch (_: Exception) { throw SecureStoreException(SecureStoreException.Reason.STORAGE) }
    }
    companion object {
        private val HEADER = byteArrayOf(0x56, 0x58, 0x43, 2)
        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        fun open(context: Context) = CreateIntentStore(IdentityAtomicStorage(File(context.noBackupFilesDir, "create-intents.v2")))
    }
}
