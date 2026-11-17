package com.voxarium.remote.data.security

import android.content.Context
import com.voxarium.remote.data.transport.RemoteEndpoint
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Native local configuration, not a server project catalog (protocol §2.3). No key material. */
class SavedProfile private constructor(
    val id: String,
    val label: String,
    val canonicalServerUrl: String,
    val spaceName: String,
    val credentialId: String,
) {
    override fun toString(): String = "SavedProfile([configuration redacted])"

    companion object {
        fun create(
            label: String,
            serverUrl: String,
            spaceName: String,
            credentialId: String,
            id: String = UUID.randomUUID().toString(),
        ): SavedProfile {
            if (!validReference(id) || !validReference(credentialId) || label.trim().isEmpty() ||
                label.length > 256 || label.any { it.code < 32 } || serverUrl.length > 16384) invalid()
            val endpoint = try { RemoteEndpoint.create(serverUrl, spaceName) } catch (_: Exception) { invalid() }
            return SavedProfile(id, label.trim(), endpoint.serverUrl, endpoint.space, credentialId)
        }
    }
}

/** Stable IDs survive edits/restarts. Profile removal never implicitly removes shared credentials.
 * Checksummed atomic metadata in noBackupFilesDir. This is NOT an authenticated/untrusted-user store.
 */
class ProfileStore(private val storage: BlobStorage) {
    fun list(): List<SavedProfile> = synchronized(STORE_LOCK) { read() }
    fun get(id: String): SavedProfile? = synchronized(STORE_LOCK) {
        if (!validReference(id)) invalid()
        read().find { it.id == id }
    }

    fun create(label: String, serverUrl: String, spaceName: String, credentialId: String): SavedProfile =
        synchronized(STORE_LOCK) {
            val profile = SavedProfile.create(label, serverUrl, spaceName, credentialId)
            upsert(profile)
            profile
        }

    /** Edit by constructing SavedProfile.create(..., id=existing.id), then upsert. */
    fun upsert(profile: SavedProfile) = synchronized(STORE_LOCK) {
        val profiles = read().toMutableList()
        val index = profiles.indexOfFirst { it.id == profile.id }
        if (index < 0) profiles.add(profile) else profiles[index] = profile
        if (profiles.size > MAX_RECORDS) invalid()
        write(profiles)
    }

    fun remove(id: String): Boolean = synchronized(STORE_LOCK) {
        if (!validReference(id)) invalid()
        val profiles = read().toMutableList()
        if (!profiles.removeAll { it.id == id }) return@synchronized false
        write(profiles)
        true
    }

    /** One exact source snapshot, serialized with all metadata writers in this process. */
    internal fun migrationSnapshot(): Pair<List<SavedProfile>, String> = synchronized(STORE_LOCK) {
        val bytes = sourceBytes()
        val profiles = read(bytes)
        profiles to digest(byteArrayOf(if (bytes == null) 0 else 1) + (bytes ?: byteArrayOf()))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun sourceBytes(): ByteArray? = try { storage.read() } catch (_: Exception) {
            throw SecureStoreException(SecureStoreException.Reason.STORAGE)
        }

    private fun read(bytes: ByteArray? = sourceBytes()): List<SavedProfile> {
        if (bytes == null) return emptyList()
        if (bytes.size < HEADER.size + 4 + 32 || bytes.size > MAX_BLOB_BYTES) corrupt()
        val payload = bytes.copyOfRange(0, bytes.size - 32)
        if (!payload.copyOfRange(0, HEADER.size).contentEquals(HEADER) ||
            !MessageDigest.isEqual(digest(payload), bytes.copyOfRange(bytes.size - 32, bytes.size))) corrupt()
        return decode(payload.copyOfRange(HEADER.size, payload.size)) {
            val count = readInt()
            if (count !in 0..MAX_RECORDS) corrupt()
            val seen = hashSetOf<String>()
            List(count) {
                val id = readUTF()
                val label = readUTF()
                val url = readUTF()
                val space = readUTF()
                val credentialId = readUTF()
                val profile = SavedProfile.create(label, url, space, credentialId, id)
                if (!seen.add(id) || profile.label != label || profile.canonicalServerUrl != url ||
                    profile.spaceName != space) corrupt()
                profile
            }
        }
    }

    private fun write(profiles: List<SavedProfile>) {
        val payload = try {
            HEADER + encode {
                writeInt(profiles.size)
                profiles.forEach {
                    writeUTF(it.id)
                    writeUTF(it.label)
                    writeUTF(it.canonicalServerUrl)
                    writeUTF(it.spaceName)
                    writeUTF(it.credentialId)
                }
            }
        } catch (_: Exception) { invalid() }
        val bytes = payload + digest(payload)
        if (bytes.size > MAX_BLOB_BYTES) invalid()
        try {
            storage.write(bytes)
            if (storage.read()?.contentEquals(bytes) != true) {
                throw SecureStoreException(SecureStoreException.Reason.STORAGE)
            }
        } catch (_: Exception) {
            throw SecureStoreException(SecureStoreException.Reason.STORAGE)
        }
    }

    companion object {
        private val HEADER = byteArrayOf(0x56, 0x58, 0x50, 1)
        private fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
        fun open(context: Context): ProfileStore = ProfileStore(
            AtomicBlobStorage(File(context.noBackupFilesDir, "profiles.v1"), MAX_BLOB_BYTES),
        )
    }
}
