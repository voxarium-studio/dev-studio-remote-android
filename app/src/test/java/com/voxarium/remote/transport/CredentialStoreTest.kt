package com.voxarium.remote.transport

import com.voxarium.remote.data.security.BlobStorage
import com.voxarium.remote.data.security.Credential
import com.voxarium.remote.data.security.CredentialKeyProvider
import com.voxarium.remote.data.security.CredentialStore
import com.voxarium.remote.data.security.SecureStoreException
import com.voxarium.remote.data.security.ProfileStore
import com.voxarium.remote.data.security.ServerProfileStore
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** JVM AES/GCM + injected storage/key provider; NOT an Android Keystore/device test. */
class CredentialStoreTest {
    private class Memory : BlobStorage {
        var bytes: ByteArray? = null
        var writes = 0
        var fail = false
        override fun read() = bytes?.clone()
        override fun write(bytes: ByteArray) {
            if (fail) throw IllegalStateException(TOKEN)
            this.bytes = bytes.clone()
            writes++
        }
    }

    private class Keys : CredentialKeyProvider {
        var current: SecretKey? = null
        val requests = mutableListOf<Boolean>()
        override fun key(allowCreate: Boolean): SecretKey {
            requests.add(allowCreate)
            if (current == null) {
                if (!allowCreate) throw IllegalStateException(TOKEN)
                current = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            }
            return current!!
        }
    }

    @Test fun ciphertextRoundTripAndRestartWithoutPlaintext() {
        val memory = Memory()
        val keys = Keys()
        val store = CredentialStore(memory, keys)
        val id = store.create(Credential.fromToken(TOKEN))
        assertTrue(keys.requests.contains(true))
        assertFalse(String(memory.bytes!!, Charsets.ISO_8859_1).contains(TOKEN))
        val reopened = CredentialStore(memory, keys)
        assertEquals(TOKEN, reopened.get(id)!!.useToken { it })
        assertNull(reopened.get("missing"))
        assertFalse(reopened.get(id).toString().contains(TOKEN))
        assertTrue(store.remove(id))
        assertFalse(store.remove(id))
        assertNull(reopened.get(id))
    }

    @Test fun everyRewriteUsesFreshGcmIv() {
        val memory = Memory()
        val store = CredentialStore(memory, Keys())
        store.put("account", Credential.fromToken(TOKEN))
        val first = memory.bytes!!.clone()
        store.put("account", Credential.fromToken(TOKEN))
        assertFalse(first.contentEquals(memory.bytes))
        assertEquals(TOKEN, store.get("account")!!.useToken { it })
    }

    @Test fun missingKeyNeverRecreatedForExistingCiphertextIncludingEmptyVault() {
        val memory = Memory()
        val keys = Keys()
        val store = CredentialStore(memory, keys)
        store.put("account", Credential.fromToken(TOKEN))
        store.remove("account")
        keys.current = null
        keys.requests.clear()
        val before = memory.bytes!!.clone()
        val error = failure { CredentialStore(memory, keys).put("other", Credential.fromToken(TOKEN)) }
        assertEquals(SecureStoreException.Reason.KEY_UNAVAILABLE, error.reason)
        assertEquals(listOf(false), keys.requests)
        assertTrue(before.contentEquals(memory.bytes))
        assertNull(error.cause)
        assertFalse(error.stackTraceToString().contains(TOKEN))
    }

    @Test fun corruptTruncatedAndUnknownVersionVaultsAreNeverOverwritten() {
        val memory = Memory()
        val keys = Keys()
        val store = CredentialStore(memory, keys)
        store.put("account", Credential.fromToken(TOKEN))
        val original = memory.bytes!!.clone()
        val tampered = original.clone().apply { this[lastIndex] = (last().toInt() xor 1).toByte() }
        val wrongHeader = original.clone().apply { this[3] = 99 }
        for (bytes in listOf(tampered, wrongHeader, original.copyOf(4), byteArrayOf())) {
            memory.bytes = bytes
            val count = memory.writes
            assertEquals(SecureStoreException.Reason.CORRUPT,
                failure { store.put("new", Credential.fromToken(TOKEN)) }.reason)
            assertEquals(count, memory.writes)
            assertTrue(bytes.contentEquals(memory.bytes))
        }
    }

    @Test fun wrongExistingKeyDoesNotReplaceCiphertext() {
        val memory = Memory()
        val keys = Keys()
        val store = CredentialStore(memory, keys)
        store.put("account", Credential.fromToken(TOKEN))
        keys.current = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val before = memory.bytes!!.clone()
        assertEquals(SecureStoreException.Reason.CORRUPT, failure { store.get("account") }.reason)
        assertTrue(before.contentEquals(memory.bytes))
    }

    @Test fun metadataMigrationKeepsCredentialReferenceCiphertextAndMissingKeyFailure() {
        val memory = Memory(); val keys = Keys(); val vault = CredentialStore(memory, keys)
        vault.put("shared", Credential.fromToken(TOKEN))
        val before = memory.bytes!!.clone()
        val profiles = ProfileStore(Memory())
        repeat(2) { profiles.create("Same", "example.com", "space", "shared") }
        keys.current = null; keys.requests.clear()
        val metadata = ServerProfileStore(Memory()).migrateLegacy(profiles)
        assertEquals(2, metadata.profiles.size)
        assertTrue(metadata.profiles.all { it.credentialId == "shared" && it.credentialRevision == 1L })
        assertTrue(keys.requests.isEmpty())
        assertArrayEquals(before, memory.bytes)
        assertEquals(SecureStoreException.Reason.KEY_UNAVAILABLE, failure { vault.get("shared") }.reason)
        assertEquals(listOf(false), keys.requests)
        assertArrayEquals(before, memory.bytes)
    }

    @Test fun metadataMigrationDoesNotResetCorruptVaultOrTurnItIntoNewCredentials() {
        val memory = Memory(); val keys = Keys(); val vault = CredentialStore(memory, keys)
        vault.put("shared", Credential.fromToken(TOKEN))
        memory.bytes = memory.bytes!!.clone().apply { this[lastIndex] = (last().toInt() xor 1).toByte() }
        val corrupt = memory.bytes!!.clone()
        val profiles = ProfileStore(Memory())
        profiles.create("Old", "example.com", "space", "shared")
        keys.requests.clear()
        ServerProfileStore(Memory()).migrateLegacy(profiles)
        assertTrue(keys.requests.isEmpty())
        assertEquals(SecureStoreException.Reason.CORRUPT,
            failure { vault.put("shared", Credential.fromToken("replacement")) }.reason)
        assertArrayEquals(corrupt, memory.bytes)
        assertFalse(keys.requests.contains(true))
    }

    @Test fun writeFailurePreservesPreviousVaultAndSanitizesException() {
        val memory = Memory()
        val store = CredentialStore(memory, Keys())
        store.put("account", Credential.fromToken(TOKEN))
        val before = memory.bytes!!.clone()
        memory.fail = true
        val error = failure { store.put("account", Credential.fromToken("replacement")) }
        assertEquals(SecureStoreException.Reason.STORAGE, error.reason)
        assertNull(error.cause)
        assertFalse(error.stackTraceToString().contains(TOKEN))
        assertTrue(before.contentEquals(memory.bytes))
        assertEquals(TOKEN, store.get("account")!!.useToken { it })
    }

    @Test fun readAndKeyProviderErrorsAreSanitized() {
        val error = failure {
            CredentialStore(object : BlobStorage {
                override fun read(): ByteArray? = throw IllegalStateException(TOKEN)
                override fun write(bytes: ByteArray) = Unit
            }, Keys()).get("account")
        }
        assertEquals(SecureStoreException.Reason.STORAGE, error.reason)
        assertNull(error.cause)
        assertFalse(error.stackTraceToString().contains(TOKEN))
        val keyError = failure {
            CredentialStore(Memory(), CredentialKeyProvider { throw IllegalStateException(TOKEN) })
                .put("account", Credential.fromToken(TOKEN))
        }
        assertEquals(SecureStoreException.Reason.KEY_UNAVAILABLE, keyError.reason)
        assertNull(keyError.cause)
        assertFalse(keyError.stackTraceToString().contains(TOKEN))
    }

    @Test fun invalidCredentialsAndReferencesNeverIncludeInputInErrors() {
        for (value in listOf("", "secret\r\nHeader", "secret token", "secret\u0000", "юникод")) {
            val error = failure { Credential.fromToken(value) }
            assertEquals(SecureStoreException.Reason.INVALID_INPUT, error.reason)
            assertNull(error.cause)
            if (value.isNotEmpty()) assertFalse(error.toString().contains(value))
        }
        val error = failure { CredentialStore(Memory(), Keys()).get("../$TOKEN") }
        assertFalse(error.toString().contains(TOKEN))
    }

    private fun failure(block: () -> Unit): SecureStoreException {
        try { block() } catch (error: SecureStoreException) { return error }
        fail("Expected safe storage error")
        throw AssertionError()
    }

    private companion object { const val TOKEN = "vxs_not_a_real_test_secret_123456" }
}
