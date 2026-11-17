package com.voxarium.remote.data.documents

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.FileInputStream
import com.voxarium.remote.data.room.RoomRecoveryStore
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Opaque repository snapshots. Cache and drafts are separate transactions, not one transaction. */
interface DocumentStorage {
    /** Ordered migration transaction owner; implementations sharing a root must share this lock. */
    fun <T> exclusive(block: () -> T): T = synchronized(this) { block() }
    /** Strict, non-repairing migration reads: null means physically absent, never corrupt.
     * Virtual adapters may delegate; filesystem adapters must override forgiving runtime reads. */
    fun readCacheForMigration(namespace: String): ByteArray? = readCache(namespace)
    fun readDraftsForMigration(namespace: String): ByteArray? = readDrafts(namespace)
    /** Complete validated room namespace discovery. Virtual stores may supply an inventory;
     * AtomicDocumentStorage discovers legacy on-disk orphan/raw-only rooms automatically. */
    fun migrationRoomNamespaces(): Set<DocumentNamespace> = emptySet()
    fun readCache(namespace: String): ByteArray?
    fun writeCache(namespace: String, bytes: ByteArray)
    fun readDrafts(namespace: String): ByteArray?
    fun writeDrafts(namespace: String, bytes: ByteArray)
}

/** Deliberately carries neither an underlying exception nor a path, namespace or document text. */
class DocumentStorageException(val reason: Reason) : IOException(
    when (reason) {
        Reason.CORRUPT -> "Documents storage is corrupt."
        Reason.STORAGE -> "Documents storage is unavailable."
        Reason.TOO_LARGE -> "Documents snapshot exceeds the storage limit."
    },
) {
    enum class Reason { CORRUPT, STORAGE, TOO_LARGE }
}

/**
 * App-private checksum envelopes, with Android's API-23-compatible atomic replacement/recovery.
 * All instances with the same canonical root share a process lock. This is not an interprocess
 * lock: only the app process may own this directory. Call on an IO worker, not the main thread.
 * Checksums detect damage, not malicious modification; snapshots are not encrypted.
 */
class AtomicDocumentStorage(root: File) : DocumentStorage {
    private val root = guarded { root.canonicalFile }
    private val lock = locks.getOrPut(this.root.path) { Any() }

    override fun readCache(namespace: String): ByteArray? = access {
        read(file(namespace, CACHE), durable = false)
    }

    override fun writeCache(namespace: String, bytes: ByteArray) = access {
        write(file(namespace, CACHE), bytes, durable = false)
    }

    override fun readDrafts(namespace: String): ByteArray? = access {
        read(file(namespace, DRAFTS), durable = true)
    }

    override fun writeDrafts(namespace: String, bytes: ByteArray) = access {
        write(file(namespace, DRAFTS), bytes, durable = true)
    }

    override fun readCacheForMigration(namespace: String): ByteArray? = access { readForMigration(file(namespace, CACHE)) }
    override fun readDraftsForMigration(namespace: String): ByteArray? = access { readForMigration(file(namespace, DRAFTS)) }

    /** Read-only inventory of the actual opaque v1 root; no caller hints are needed for rooms.
     * Unknown entries/schema, symlinks, malformed envelopes/payloads and limits fail closed.
     * Account selection filters only AFTER complete validation, never skips unreadable buckets. */
    override fun migrationRoomNamespaces(): Set<DocumentNamespace> = access { inventory {
        val rootStat = statForMigration(root) ?: return@inventory emptySet()
        requireDirectory(rootStat.st_mode)
        val directories = root.listFiles() ?: throw DocumentStorageException(DocumentStorageException.Reason.STORAGE)
        if (directories.size > MAX_INVENTORY_DIRECTORIES) throw DocumentStorageException(DocumentStorageException.Reason.TOO_LARGE)
        val result = linkedSetOf<DocumentNamespace>()
        var total = 0L
        val rooms = RoomRecoveryStore(this)
        for (directory in directories.sortedBy { it.name }) {
            requireDirectory((statForMigration(directory) ?: throw DocumentStorageException(DocumentStorageException.Reason.STORAGE)).st_mode)
            if (!Regex("[0-9a-f]{64}").matches(directory.name)) throw DocumentStorageException(DocumentStorageException.Reason.CORRUPT)
            val entries = directory.listFiles() ?: throw DocumentStorageException(DocumentStorageException.Reason.STORAGE)
            if (entries.size > 6 || entries.any { it.name !in setOf(CACHE, "$CACHE.bak", "$CACHE.new", DRAFTS, "$DRAFTS.bak", "$DRAFTS.new") })
                throw DocumentStorageException(DocumentStorageException.Reason.CORRUPT)
            for (entry in entries) {
                val stat = statForMigration(entry) ?: throw DocumentStorageException(DocumentStorageException.Reason.STORAGE)
                if (!OsConstants.S_ISREG(stat.st_mode)) throw DocumentStorageException(DocumentStorageException.Reason.STORAGE)
                total += stat.st_size
                if (total > MAX_INVENTORY_BYTES) throw DocumentStorageException(DocumentStorageException.Reason.TOO_LARGE)
            }
            readForMigration(File(directory, CACHE))?.let { DocumentStateCodec.mirror(it) }
            val bytes = readForMigration(File(directory, DRAFTS)) ?: continue
            val o = DocumentStateCodec.strictObject(bytes)
            if (o.has("version")) {
                val n = rooms.inventoryNamespace(bytes)
                if (file(rooms.key(n), DRAFTS).parentFile != directory) throw DocumentStorageException(DocumentStorageException.Reason.CORRUPT)
                result.add(n)
            } else if (o.has("spaces")) DocumentStateCodec.journal(bytes)
            else DocumentStateCodec.identity(bytes)
        }
        result
    } }

    private inline fun <T> inventory(block: () -> T): T = try { block() }
    catch (e: DocumentStorageException) { throw e }
    catch (_: com.voxarium.remote.data.room.RoomRecoveryException) { throw DocumentStorageException(DocumentStorageException.Reason.CORRUPT) }
    catch (_: org.json.JSONException) { throw DocumentStorageException(DocumentStorageException.Reason.CORRUPT) }
    catch (_: IllegalArgumentException) { throw DocumentStorageException(DocumentStorageException.Reason.CORRUPT) }
    catch (_: IllegalStateException) { throw DocumentStorageException(DocumentStorageException.Reason.CORRUPT) }
    catch (_: java.nio.charset.CharacterCodingException) { throw DocumentStorageException(DocumentStorageException.Reason.CORRUPT) }

    private fun statForMigration(file: File): android.system.StructStat? = try { Os.lstat(file.path) }
    catch (e: ErrnoException) {
        if (e.errno == OsConstants.ENOENT) null else throw DocumentStorageException(DocumentStorageException.Reason.STORAGE)
    }
    private fun requireDirectory(mode: Int) {
        if (!OsConstants.S_ISDIR(mode)) throw DocumentStorageException(DocumentStorageException.Reason.STORAGE)
    }

    /** Select the last committed base/legacy backup without calling AtomicFile.openRead:
     * it performs recovery renames/deletions. An orphan .new is NOT a missing slot. */
    private fun readForMigration(base: File): ByteArray? {
        val candidates = listOf(base, File(base.path + ".bak"), File(base.path + ".new"))
        val present = candidates.map { f -> statForMigration(f)?.also {
            if (!OsConstants.S_ISREG(it.st_mode)) throw DocumentStorageException(DocumentStorageException.Reason.STORAGE)
        } }
        val selected = when {
            present[1] != null -> candidates[1]
            present[0] != null -> base
            present[2] != null -> throw DocumentStorageException(DocumentStorageException.Reason.CORRUPT)
            else -> return null
        }
        try {
            DataInputStream(FileInputStream(selected)).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION) return corrupt(true)
                val length = input.readInt()
                if (length !in 0..MAX_PAYLOAD_BYTES) return corrupt(true)
                val expected = ByteArray(32).also(input::readFully)
                val bytes = ByteArray(length).also(input::readFully)
                if (input.read() != -1 || !MessageDigest.isEqual(expected, sha256(bytes))) return corrupt(true)
                return bytes
            }
        } catch (_: EOFException) { return corrupt(true) }
    }

    internal fun sharesRoot(other: DocumentStorage) = other is AtomicDocumentStorage && root == other.root
    override fun <T> exclusive(block: () -> T): T = synchronized(lock) { block() }
    private fun <T> access(block: () -> T): T = synchronized(lock) { guarded(block) }

    private fun file(namespace: String, kind: String): File {
        // Hash UTF-16 code units without normalization: even unpaired surrogate strings remain
        // opaque/distinct, rather than aliasing through a UTF-8 encoder's replacement character.
        val digest = MessageDigest.getInstance("SHA-256")
        for (char in namespace) {
            digest.update((char.code ushr 8).toByte())
            digest.update(char.code.toByte())
        }
        val directory = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return File(File(root, directory), kind)
    }

    private fun read(base: File, durable: Boolean): ByteArray? {
        // File.exists()/AtomicFile.readFully() cannot distinguish ENOENT from access/IO errors,
        // and readFully() can allocate an unbounded attacker/corruption-controlled array.
        regularFileOrMissing(base)
        val backup = File(base.path + ".bak")
        regularFileOrMissing(backup)
        regularFileOrMissing(File(base.path + ".new"))
        val atomic = AtomicFile(base)
        val stream = try {
            atomic.openRead()
        } catch (_: FileNotFoundException) {
            if (!regularFileOrMissing(base) && !regularFileOrMissing(backup)) return null
            throw DocumentStorageException(DocumentStorageException.Reason.STORAGE)
        }
        stream.use {
            // AtomicFile logs (rather than throws) some failed recovery renames.
            if (regularFileOrMissing(backup)) {
                throw DocumentStorageException(DocumentStorageException.Reason.STORAGE)
            }
            try {
                val input = DataInputStream(it)
                if (input.readInt() != MAGIC || input.readInt() != VERSION) return corrupt(durable)
                val length = input.readInt()
                if (length !in 0..MAX_PAYLOAD_BYTES) return corrupt(durable)
                val expected = ByteArray(32).also(input::readFully)
                val payload = ByteArray(length).also(input::readFully)
                if (input.read() != -1 || !MessageDigest.isEqual(expected, sha256(payload))) {
                    return corrupt(durable)
                }
                return payload
            } catch (_: EOFException) {
                return corrupt(durable)
            }
        }
    }

    private fun write(base: File, bytes: ByteArray, durable: Boolean) {
        if (bytes.size > MAX_PAYLOAD_BYTES) {
            throw DocumentStorageException(DocumentStorageException.Reason.TOO_LARGE)
        }
        // Validate/recover BEFORE startWrite; corrupt durable drafts are never overwritten,
        // including when the caller did not read them first or is clearing to an empty payload.
        read(base, durable)
        val payload = bytes.clone()
        val parent = base.parentFile!!
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            throw DocumentStorageException(DocumentStorageException.Reason.STORAGE)
        }
        val atomic = AtomicFile(base)
        var stream: FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            val output = DataOutputStream(stream)
            output.writeInt(MAGIC)
            output.writeInt(VERSION)
            output.writeInt(payload.size)
            output.write(sha256(payload))
            output.write(payload)
            output.flush()
            // AtomicFile.finishWrite logs fsync errors instead of throwing; do a checked sync
            // first, while failWrite can still restore the previous committed snapshot.
            stream.fd.sync()
            atomic.finishWrite(stream)
            stream = null
        } finally {
            // Includes exceptional/aborted writes, not only IOExceptions.
            if (stream != null) atomic.failWrite(stream)
        }
        // finishWrite also logs rename errors. Never report success for a lost replacement.
        val committed = read(base, durable)
        if (committed == null || !committed.contentEquals(payload)) {
            throw DocumentStorageException(DocumentStorageException.Reason.STORAGE)
        }
    }

    private fun corrupt(durable: Boolean): ByteArray? {
        if (durable) throw DocumentStorageException(DocumentStorageException.Reason.CORRUPT)
        return null
    }

    private fun regularFileOrMissing(file: File): Boolean {
        val stat = try {
            Os.stat(file.path)
        } catch (error: ErrnoException) {
            if (error.errno == OsConstants.ENOENT) return false
            throw DocumentStorageException(DocumentStorageException.Reason.STORAGE)
        }
        if (!OsConstants.S_ISREG(stat.st_mode)) {
            throw DocumentStorageException(DocumentStorageException.Reason.STORAGE)
        }
        return true
    }

    companion object {
        const val MAX_PAYLOAD_BYTES: Int = 64 * 1024 * 1024
        const val MAX_INVENTORY_DIRECTORIES: Int = 4096
        const val MAX_INVENTORY_BYTES: Long = 256L * 1024 * 1024
        private const val MAGIC = 0x56584453 // VXDS
        private const val VERSION = 1
        private const val CACHE = "cache.bin"
        private const val DRAFTS = "drafts.bin"
        private val locks = ConcurrentHashMap<String, Any>()

        fun open(context: Context): AtomicDocumentStorage = guarded {
            val privateRoot = context.noBackupFilesDir
                ?: throw DocumentStorageException(DocumentStorageException.Reason.STORAGE)
            AtomicDocumentStorage(File(privateRoot, "documents.v1"))
        }

        /** Explicit opt-in factory only: does not migrate, activate or open a network session. */
        fun openV2(context: Context): AtomicDocumentStorage = guarded {
            val privateRoot = context.noBackupFilesDir
                ?: throw DocumentStorageException(DocumentStorageException.Reason.STORAGE)
            AtomicDocumentStorage(File(privateRoot, "documents.v2"))
        }

        private fun sha256(bytes: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(bytes)

        private fun <T> guarded(block: () -> T): T = try {
            block()
        } catch (error: DocumentStorageException) {
            // A resource's close() can add an unsafe suppressed IO exception to a safe error.
            throw DocumentStorageException(error.reason)
        } catch (_: IOException) {
            throw DocumentStorageException(DocumentStorageException.Reason.STORAGE)
        } catch (_: SecurityException) {
            throw DocumentStorageException(DocumentStorageException.Reason.STORAGE)
        }
    }
}
