package com.voxarium.remote.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Not a data class: generated toString/component methods must never reveal a Bearer key. */
class Credential private constructor(private val token: String) {
    fun <T> useToken(block: (String) -> T): T = block(token)
    override fun toString(): String = "Credential([REDACTED])"

    companion object {
        fun fromToken(token: String): Credential {
            // Bearer keys are opaque, single header values; do not trim or alter them.
            if (token.isEmpty() || token.length > 8192 || token.any { it.code !in 33..126 }) invalid()
            return Credential(token)
        }
    }
}

fun interface CredentialKeyProvider {
    /** allowCreate=false MUST fail if the key has disappeared, never replace it. */
    fun key(allowCreate: Boolean): SecretKey
}

/** API 23 AES/GCM, system Keystore; no StrongBox/hardware-backed guarantee is made. */
internal class AndroidCredentialKeyProvider : CredentialKeyProvider {
    override fun key(allowCreate: Boolean): SecretKey = try {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(ALIAS)) {
            store.getKey(ALIAS, null) as? SecretKey
                ?: throw SecureStoreException(SecureStoreException.Reason.KEY_UNAVAILABLE)
        } else {
            if (!allowCreate) throw SecureStoreException(SecureStoreException.Reason.KEY_UNAVAILABLE)
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .setKeySize(256)
                    .build())
            }.generateKey()
        }
    } catch (_: Exception) {
        throw SecureStoreException(SecureStoreException.Reason.KEY_UNAVAILABLE)
    }

    private companion object { const val ALIAS = "com.voxarium.remote.credentials.v1" }
}

/** Separate credential IDs allow several profiles/spaces to share one server/account key.
 * Single-process synchronous API: call off the main thread. No automatic reset/recovery/deletion.
 */
class CredentialStore(private val storage: BlobStorage, private val keys: CredentialKeyProvider) {
    fun create(credential: Credential): String = synchronized(STORE_LOCK) {
        val id = UUID.randomUUID().toString()
        put(id, credential)
        id
    }

    fun get(id: String): Credential? = synchronized(STORE_LOCK) {
        if (!validReference(id)) invalid()
        read()[id]
    }

    fun put(id: String, credential: Credential) = synchronized(STORE_LOCK) {
        if (!validReference(id)) invalid()
        val records = read().toMutableMap()
        records[id] = credential
        if (records.size > MAX_RECORDS) invalid()
        write(records)
    }

    /** Explicit removal only; callers must first account for all profiles using this ID. */
    fun remove(id: String): Boolean = synchronized(STORE_LOCK) {
        if (!validReference(id)) invalid()
        val records = read().toMutableMap()
        if (records.remove(id) == null) return@synchronized false
        write(records)
        true
    }

    private fun read(): Map<String, Credential> {
        val bytes = safeRead() ?: return emptyMap()
        if (bytes.size < HEADER.size + 12 + 16 || bytes.size > MAX_BLOB_BYTES ||
            !bytes.copyOfRange(0, HEADER.size).contentEquals(HEADER)) corrupt()
        val key = safeKey(false)
        val plaintext = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(HEADER.size, HEADER.size + 12)))
                updateAAD(HEADER)
                doFinal(bytes, HEADER.size + 12, bytes.size - HEADER.size - 12)
            }
        } catch (_: Exception) { corrupt() }
        return try {
            decode(plaintext) {
                val count = readInt()
                if (count !in 0..MAX_RECORDS) corrupt()
                val records = linkedMapOf<String, Credential>()
                repeat(count) {
                    val id = readUTF()
                    if (!validReference(id) || records.containsKey(id)) corrupt()
                    records[id] = Credential.fromToken(readUTF())
                }
                records
            }
        } finally { plaintext.fill(0) }
    }

    private fun write(records: Map<String, Credential>) {
        // Key creation is permitted only before any ciphertext exists, including an empty vault.
        val key = safeKey(safeRead() == null)
        val plaintext = encode {
            writeInt(records.size)
            records.forEach { (id, credential) -> writeUTF(id); credential.useToken { writeUTF(it) } }
        }
        val encrypted = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, key)
                updateAAD(HEADER)
                if (iv.size != 12) throw SecureStoreException(SecureStoreException.Reason.CRYPTO)
                HEADER + iv + doFinal(plaintext)
            }
        } catch (_: Exception) {
            throw SecureStoreException(SecureStoreException.Reason.CRYPTO)
        } finally { plaintext.fill(0) }
        try { storage.write(encrypted) } catch (_: Exception) {
            throw SecureStoreException(SecureStoreException.Reason.STORAGE)
        }
    }

    private fun safeRead(): ByteArray? = try { storage.read() } catch (_: Exception) {
        throw SecureStoreException(SecureStoreException.Reason.STORAGE)
    }

    private fun safeKey(allowCreate: Boolean): SecretKey = try { keys.key(allowCreate) } catch (_: Exception) {
        throw SecureStoreException(SecureStoreException.Reason.KEY_UNAVAILABLE)
    }

    companion object {
        private val HEADER = byteArrayOf(0x56, 0x58, 0x43, 1)
        fun open(context: Context): CredentialStore = CredentialStore(
            AtomicBlobStorage(File(context.noBackupFilesDir, "credentials.v1"), MAX_BLOB_BYTES),
            AndroidCredentialKeyProvider(),
        )
    }
}
