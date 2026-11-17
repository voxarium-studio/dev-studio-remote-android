package com.voxarium.remote.data.security

import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException

/** Deliberately has no underlying exception: platform/parser messages may contain secrets. */
class SecureStoreException(val reason: Reason) : Exception("Secure storage failure: ${reason.name}") {
    enum class Reason { STORAGE, CORRUPT, KEY_UNAVAILABLE, CRYPTO, INVALID_INPUT }
}

/** A single atomic blob. Implementations must distinguish missing from unreadable data. */
interface BlobStorage {
    fun read(): ByteArray?
    fun write(bytes: ByteArray)
}

/** App-private files only; AtomicFile recovers an interrupted replacement on the next read. */
internal class AtomicBlobStorage(file: File, private val maxBytes: Int) : BlobStorage {
    private val atomic = AtomicFile(file)

    override fun read(): ByteArray? = try {
        atomic.openRead().use { input ->
            val bytes = input.readBytesBounded(maxBytes)
            bytes
        }
    } catch (_: FileNotFoundException) {
        // Do not reinterpret an unreadable existing file as a new/empty vault.
        if (atomic.baseFile.exists() || File(atomic.baseFile.path + ".bak").exists()) {
            throw SecureStoreException(SecureStoreException.Reason.STORAGE)
        }
        null
    } catch (_: Exception) {
        throw SecureStoreException(SecureStoreException.Reason.STORAGE)
    }

    override fun write(bytes: ByteArray) {
        if (bytes.size > maxBytes) throw SecureStoreException(SecureStoreException.Reason.INVALID_INPUT)
        var stream: java.io.FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (_: Exception) {
            try { atomic.failWrite(stream) } catch (_: Exception) { /* preserve safe error */ }
            throw SecureStoreException(SecureStoreException.Reason.STORAGE)
        }
    }
}

private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) return out.toByteArray()
        if (out.size() + count > limit) throw SecureStoreException(SecureStoreException.Reason.CORRUPT)
        out.write(buffer, 0, count)
    }
}

internal const val MAX_RECORDS = 1000
internal const val MAX_BLOB_BYTES = 8 * 1024 * 1024
internal val STORE_LOCK = Any() // all instances in this app process share read/modify/write serialization

internal fun validReference(value: String): Boolean = value.matches(Regex("[A-Za-z0-9_-]{1,128}"))
internal fun invalid(): Nothing = throw SecureStoreException(SecureStoreException.Reason.INVALID_INPUT)
internal fun corrupt(): Nothing = throw SecureStoreException(SecureStoreException.Reason.CORRUPT)

internal fun encode(block: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().use { bytes ->
    DataOutputStream(bytes).use(block)
    bytes.toByteArray()
}

internal fun <T> decode(bytes: ByteArray, block: DataInputStream.() -> T): T {
    if (bytes.size > MAX_BLOB_BYTES) corrupt()
    return try {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val result = input.block()
            if (input.read() != -1) corrupt()
            result
        }
    } catch (_: Exception) {
        corrupt()
    }
}
