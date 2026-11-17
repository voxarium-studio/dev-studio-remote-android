package com.voxarium.remote.data.security

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.Collections

/** Single atomic metadata transaction, not an activation manifest for Documents/rooms.
 * Android identity design §§5.1/5.2: v1 is read-only input; never touch credentials.v1/Keystore.
 * Call on one ordered IO worker. STORE_LOCK serializes all instances within the app process;
 * multiple OS processes are not supported, as with the existing vault/repository stores.
 */
class ServerProfileStore(private val storage: BlobStorage) {
    class Snapshot internal constructor(
        profiles: List<ServerProfile>, bindings: List<ProjectBinding>, imports: List<LegacyImportRecord>,
        val legacySourceSha256: String?, val legacySourceCount: Int,
    ) {
        val profiles: List<ServerProfile> = Collections.unmodifiableList(profiles.toList())
        val bindings: List<ProjectBinding> = Collections.unmodifiableList(bindings.toList())
        val legacyImports: List<LegacyImportRecord> = Collections.unmodifiableList(imports.toList())
        override fun toString() = "IdentityMetadata([redacted, inactive])"
    }

    fun snapshot(): Snapshot = synchronized(STORE_LOCK) { read() }

    /** One atomic copy+verified-commit, so there is no partly copied/active intermediate state.
     * A committed marker prevents stale v1 re-import, including after edits/removal in v2.
     * Observations must come from exact authenticated legacy bindings, never URL/name guessing.
     * This API does not read secrets, verify accounts, resolve mapping or activate any binding.
     */
    fun migrateLegacy(legacy: ProfileStore, observations: List<LegacyImportRecord> = emptyList()): Snapshot =
        synchronized(STORE_LOCK) {
            val current = read()
            if (current.legacySourceSha256 != null) return@synchronized current
            val (profiles, sourceHash) = legacy.migrationSnapshot()
            if (observations.map { it.localProfileId }.distinct().size != observations.size) invalid()
            val byId = observations.associateBy { it.localProfileId }
            if (byId.keys.any { id -> profiles.none { it.id == id } }) invalid()
            val imports = profiles.map { profile ->
                val observation = byId[profile.id] ?: LegacyImportRecord.create(profile)
                if (observation.canonicalUrl != profile.canonicalServerUrl || observation.credentialId != profile.credentialId ||
                    observation.spaceName != profile.spaceName) invalid()
                observation
            }
            // Any pre-existing conflicting ID must be handled explicitly, never overwritten.
            if (current.profiles.any { old -> profiles.any { it.id == old.localProfileId } } ||
                current.legacyImports.any { old -> imports.any { it.recordId == old.recordId } }) invalid()
            val migrated = profiles.map {
                ServerProfile.create(it.label, it.canonicalServerUrl, it.credentialId, localProfileId = it.id)
            }
            val next = Snapshot(current.profiles + migrated, current.bindings, current.legacyImports + imports,
                sourceHash, profiles.size)
            write(next)
            next
        }

    /** Local-only save. A key replacement must increment revision, even if credentialId is reused.
     * Historical verification cannot survive a URL/credential/revision change.
     */
    fun upsert(profile: ServerProfile) = synchronized(STORE_LOCK) {
        val state = read()
        val old = state.profiles.find { it.localProfileId == profile.localProfileId }
        if (old != null) {
            if (profile.credentialRevision < old.credentialRevision ||
                (profile.credentialId != old.credentialId && profile.credentialRevision <= old.credentialRevision)) invalid()
            if ((old.canonicalUrl != profile.canonicalUrl || old.credentialId != profile.credentialId ||
                    old.credentialRevision != profile.credentialRevision) && profile.verification != null) invalid()
        }
        write(Snapshot(state.profiles.filterNot { it.localProfileId == profile.localProfileId } + profile,
            state.bindings, state.legacyImports, state.legacySourceSha256, state.legacySourceCount))
    }

    /** Explicit metadata registration only, NOT selection/activation. Existing IDs cannot retarget.
     * Fresh verification/hello and migration activation barriers belong to the integration owner.
     */
    fun addBinding(binding: ProjectBinding) = synchronized(STORE_LOCK) {
        val state = read()
        validateBindingReference(state, binding)
        if (state.bindings.any { it.bindingId == binding.bindingId }) invalid()
        write(Snapshot(state.profiles, state.bindings + binding, state.legacyImports,
            state.legacySourceSha256, state.legacySourceCount))
    }

    /** Select does not append unreachable copies of an identical durable identity (VS-182 RS-3).
     * Reuse happens atomically before the capacity check, including at MAX_RECORDS. It neither
     * changes the old binding nor reuses any live repository/transport owner or generation.
     */
    fun findOrAddBinding(binding: ProjectBinding): ProjectBinding = synchronized(STORE_LOCK) {
        val state = read()
        validateBindingReference(state, binding)
        if (state.bindings.any { it.bindingId == binding.bindingId && !sameBindingIdentity(it, binding) }) invalid()
        state.bindings.firstOrNull { sameBindingIdentity(it, binding) }?.let { return@synchronized it }
        write(Snapshot(state.profiles, state.bindings + binding, state.legacyImports,
            state.legacySourceSha256, state.legacySourceCount))
        binding
    }

    private fun validateBindingReference(state: Snapshot, binding: ProjectBinding) {
        val profile = state.profiles.singleOrNull { it.localProfileId == binding.serverProfileId } ?: invalid()
        if (profile.canonicalUrl != binding.canonicalUrl || profile.credentialId != binding.credentialId ||
            profile.credentialRevision != binding.credentialRevision ||
            (binding.legacyImportRecordId != null && state.legacyImports.none {
                it.recordId == binding.legacyImportRecordId && it.localProfileId == binding.serverProfileId
            })) invalid()
    }

    private fun sameBindingIdentity(a: ProjectBinding, b: ProjectBinding) =
        a.serverProfileId == b.serverProfileId && a.canonicalUrl == b.canonicalUrl &&
        a.credentialId == b.credentialId && a.credentialRevision == b.credentialRevision &&
        a.expectedAccountId == b.expectedAccountId && a.projectId == b.projectId &&
        a.storageEpoch == b.storageEpoch && a.catalogContractVersion == b.catalogContractVersion &&
        a.legacyImportRecordId == b.legacyImportRecordId

    private fun read(): Snapshot {
        val bytes = safeRead() ?: return Snapshot(emptyList(), emptyList(), emptyList(), null, 0)
        if (bytes.size < HEADER.size + 32 || bytes.size > MAX_BLOB_BYTES) corrupt()
        val payload = bytes.copyOfRange(0, bytes.size - 32)
        if (!payload.copyOfRange(0, HEADER.size).contentEquals(HEADER) ||
            !MessageDigest.isEqual(digest(payload), bytes.copyOfRange(bytes.size - 32, bytes.size))) corrupt()
        return decode(payload.copyOfRange(HEADER.size, payload.size)) {
            val sourceHash = if (readBoolean()) readUTF().also { if (!HASH.matches(it)) corrupt() } else null
            val sourceCount = readInt().also { if (it !in 0..MAX_RECORDS || (sourceHash == null && it != 0)) corrupt() }
            fun count() = readInt().also { if (it !in 0..MAX_RECORDS) corrupt() }
            val profiles = List(count()) {
                val id = readUTF(); val name = readUTF(); val url = readUTF(); val credential = readUTF()
                val revision = readLong(); val canonicalizer = readInt()
                val verification = if (readBoolean()) {
                    val generation = readLong(); val account = readUTF(); val protocol = readInt(); val time = readLong()
                    val catalog = readInt().let { if (it == 0) null else it }
                    ServerVerification.create(url, credential, revision, generation, account, protocol, time,
                        readUTF(), readUTF(), catalog)
                } else null
                ServerProfile.create(name, url, credential, id, revision, verification, canonicalizer)
            }
            val imports = List(count()) {
                val id = readUTF(); val url = readUTF(); val credential = readUTF(); val selector = readUTF()
                val profile = SavedProfile.create(label = "Legacy", id = id, serverUrl = url,
                    credentialId = credential, spaceName = selector)
                if (profile.canonicalServerUrl != url || profile.spaceName != selector) corrupt()
                val account = if (readBoolean()) readUTF() else null
                val spaceId = if (readBoolean()) readUTF() else null
                LegacyImportRecord.create(profile, account, spaceId)
            }
            val bindings = List(count()) {
                val id = readUTF(); val profile = readUTF(); val project = readUTF(); val account = readUTF()
                val epoch = readUTF(); val contract = readInt(); val provenance = if (readBoolean()) readUTF() else null
                val captured = ServerProfile.create("Binding", readUTF(), readUTF(), profile, readLong())
                ProjectBinding.create(captured, project, account, epoch, id, contract, provenance)
            }
            Snapshot(profiles, bindings, imports, sourceHash, sourceCount).also { validate(it) }
        }
    }

    private fun validate(state: Snapshot) {
        if (state.profiles.size > MAX_RECORDS || state.bindings.size > MAX_RECORDS || state.legacyImports.size > MAX_RECORDS ||
            (state.legacySourceSha256 == null && state.legacyImports.isNotEmpty()) ||
            (state.legacySourceSha256 != null && state.legacySourceCount != state.legacyImports.size) ||
            state.profiles.map { it.localProfileId }.distinct().size != state.profiles.size ||
            state.bindings.map { it.bindingId }.distinct().size != state.bindings.size ||
            state.legacyImports.map { it.recordId }.distinct().size != state.legacyImports.size ||
            state.bindings.any { b -> state.profiles.none { it.localProfileId == b.serverProfileId } ||
                (b.legacyImportRecordId != null && state.legacyImports.none {
                    it.recordId == b.legacyImportRecordId && it.localProfileId == b.serverProfileId
                }) }) invalid()
    }

    private fun write(state: Snapshot) {
        validate(state)
        val payload = try { HEADER + encode {
            writeBoolean(state.legacySourceSha256 != null)
            state.legacySourceSha256?.let { writeUTF(it) }
            writeInt(state.legacySourceCount)
            writeInt(state.profiles.size)
            state.profiles.forEach { p ->
                writeUTF(p.localProfileId); writeUTF(p.name); writeUTF(p.canonicalUrl); writeUTF(p.credentialId)
                writeLong(p.credentialRevision); writeInt(p.canonicalizerVersion); writeBoolean(p.verification != null)
                p.verification?.let { v ->
                    writeLong(v.requestGeneration); writeUTF(v.accountId); writeInt(v.protocol)
                    writeLong(v.checkedAtEpochMillis); writeInt(v.catalogContractVersion ?: 0)
                    writeUTF(v.accountDisplayName); writeUTF(v.accountColor)
                }
            }
            writeInt(state.legacyImports.size)
            state.legacyImports.forEach { i ->
                writeUTF(i.localProfileId); writeUTF(i.canonicalUrl); writeUTF(i.credentialId); writeUTF(i.spaceName)
                writeBoolean(i.lastAuthenticatedAccountId != null); i.lastAuthenticatedAccountId?.let { writeUTF(it) }
                writeBoolean(i.observedSpaceId != null); i.observedSpaceId?.let { writeUTF(it) }
            }
            writeInt(state.bindings.size)
            state.bindings.forEach { b ->
                writeUTF(b.bindingId); writeUTF(b.serverProfileId); writeUTF(b.projectId); writeUTF(b.expectedAccountId)
                writeUTF(b.storageEpoch); writeInt(b.catalogContractVersion)
                writeBoolean(b.legacyImportRecordId != null); b.legacyImportRecordId?.let { writeUTF(it) }
                writeUTF(b.canonicalUrl); writeUTF(b.credentialId); writeLong(b.credentialRevision)
            }
        } } catch (_: Exception) { invalid() }
        val bytes = payload + digest(payload)
        if (bytes.size > MAX_BLOB_BYTES) invalid()
        try {
            storage.write(bytes)
            if (storage.read()?.contentEquals(bytes) != true) throw IllegalStateException()
        } catch (_: Exception) { throw SecureStoreException(SecureStoreException.Reason.STORAGE) }
    }

    private fun safeRead(): ByteArray? = try { storage.read() }
        catch (_: Exception) { throw SecureStoreException(SecureStoreException.Reason.STORAGE) }

    companion object {
        private val HEADER = byteArrayOf(0x56, 0x58, 0x49, 2)
        private val HASH = Regex("[0-9a-f]{64}")
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        fun open(context: Context) = ServerProfileStore(IdentityAtomicStorage(File(context.noBackupFilesDir, "identity.v2")))
    }
}

/** Checked fsync and readback; v1 AtomicBlobStorage/vault format are deliberately unchanged. */
internal class IdentityAtomicStorage(file: File) : BlobStorage {
    private val atomic = AtomicFile(file)
    override fun read(): ByteArray? = try {
        atomic.openRead().use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (out.size() + n > MAX_BLOB_BYTES) corrupt()
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }
    } catch (_: FileNotFoundException) {
        if (atomic.baseFile.exists() || File(atomic.baseFile.path + ".bak").exists() ||
            File(atomic.baseFile.path + ".new").exists()) throw SecureStoreException(SecureStoreException.Reason.STORAGE)
        null
    } catch (_: Exception) { throw SecureStoreException(SecureStoreException.Reason.STORAGE) }

    override fun write(bytes: ByteArray) {
        if (bytes.size > MAX_BLOB_BYTES) invalid()
        var stream: java.io.FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(bytes)
            stream.fd.sync()
            atomic.finishWrite(stream)
            if (read()?.contentEquals(bytes) != true) throw IllegalStateException()
        } catch (_: Exception) {
            try { atomic.failWrite(stream) } catch (_: Exception) { /* no unsafe diagnostics */ }
            throw SecureStoreException(SecureStoreException.Reason.STORAGE)
        }
    }
}
