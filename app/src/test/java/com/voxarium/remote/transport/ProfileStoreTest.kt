package com.voxarium.remote.transport

import com.voxarium.remote.data.security.BlobStorage
import com.voxarium.remote.data.security.Credential
import com.voxarium.remote.data.security.CredentialKeyProvider
import com.voxarium.remote.data.security.CredentialStore
import com.voxarium.remote.data.security.ProfileStore
import com.voxarium.remote.data.security.SavedProfile
import com.voxarium.remote.data.security.SecureStoreException
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.KeyGenerator

class ProfileStoreTest {
    private class Memory : BlobStorage {
        var bytes: ByteArray? = null
        var fail = false
        override fun read() = bytes?.clone()
        override fun write(bytes: ByteArray) {
            if (fail) throw IllegalStateException("private storage details")
            this.bytes = bytes.clone()
        }
    }

    @Test fun stableProfileIdsCanonicalEndpointAndUpdatesSurviveReopen() {
        val memory = Memory()
        val store = ProfileStore(memory)
        val first = store.create(" My project ", "WSS://EXAMPLE.COM/prefix/", " Game ", "account")
        assertEquals("https://example.com/prefix", first.canonicalServerUrl)
        assertEquals("game", first.spaceName)
        assertEquals("My project", first.label)
        assertEquals("account", first.credentialId)
        assertTrue(first.id.isNotBlank())
        store.upsert(SavedProfile.create("Renamed", "example.com/prefix", "next", "account", id = first.id))
        val reopened = ProfileStore(memory)
        assertEquals(1, reopened.list().size)
        assertEquals(first.id, reopened.list().single().id)
        assertEquals("Renamed", reopened.get(first.id)!!.label)
        assertEquals("next", reopened.get(first.id)!!.spaceName)
        assertNull(reopened.get("missing"))
        assertTrue(reopened.remove(first.id))
        assertFalse(reopened.remove(first.id))
        assertTrue(store.list().isEmpty())
    }

    @Test fun multipleSpacesShareCredentialButRemainSeparateAndRemovalDoesNotDeleteKey() {
        val profiles = ProfileStore(Memory())
        val vaultMemory = Memory()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val vault = CredentialStore(vaultMemory, CredentialKeyProvider { key })
        val credentialId = vault.create(Credential.fromToken("vxs_test_secret"))
        val first = profiles.create("First", "example.com", "one", credentialId)
        val second = profiles.create("Second", "example.com", "two", credentialId)
        assertNotEquals(first.id, second.id)
        assertEquals(first.credentialId, second.credentialId)
        assertEquals(2, profiles.list().size)
        profiles.remove(first.id)
        assertEquals(second.id, profiles.list().single().id)
        assertEquals("vxs_test_secret", vault.get(credentialId)!!.useToken { it })
    }

    @Test fun checksumDetectsMetadataCorruptionAndDoesNotReset() {
        val memory = Memory()
        val store = ProfileStore(memory)
        store.create("Project", "example.com", "space", "account")
        val original = memory.bytes!!.clone()
        for (bytes in listOf(original.clone().apply { this[12] = (this[12].toInt() xor 1).toByte() },
            original.copyOf(10), byteArrayOf())) {
            memory.bytes = bytes
            assertEquals(SecureStoreException.Reason.CORRUPT,
                failure { store.create("New", "example.com", "space", "account") }.reason)
            assertTrue(bytes.contentEquals(memory.bytes))
        }
    }

    @Test fun failedMetadataWriteKeepsOldProfile() {
        val memory = Memory()
        val store = ProfileStore(memory)
        val first = store.create("Project", "example.com", "space", "account")
        val before = memory.bytes!!.clone()
        memory.fail = true
        val error = failure { store.remove(first.id) }
        assertEquals(SecureStoreException.Reason.STORAGE, error.reason)
        assertNull(error.cause)
        assertTrue(before.contentEquals(memory.bytes))
        assertEquals(first.id, store.list().single().id)
    }

    @Test fun silentDroppedWriteIsNotSuccessfulMetadataCommit() {
        val storage = object : BlobStorage {
            override fun read(): ByteArray? = null
            override fun write(bytes: ByteArray) = Unit
        }
        assertEquals(SecureStoreException.Reason.STORAGE,
            failure { ProfileStore(storage).create("One", "example.com", "space", "key") }.reason)
    }

    @Test fun invalidMetadataIsRejectedWithoutRetainingInputErrors() {
        for (url in listOf("https://user:secret@example.com", "https://example.com?token=secret",
            "http://example.com", "https://example.com/#secret")) {
            val error = failure { SavedProfile.create("Project", url, "space", "account") }
            assertEquals(SecureStoreException.Reason.INVALID_INPUT, error.reason)
            assertNull(error.cause)
            assertFalse(error.toString().contains("secret"))
        }
        failure { SavedProfile.create(" ", "example.com", "space", "account") }
        failure { SavedProfile.create("Project", "example.com", "../space", "account") }
        failure { SavedProfile.create("Project", "example.com", "space", "../account") }
    }

    private fun failure(block: () -> Unit): SecureStoreException {
        try { block() } catch (error: SecureStoreException) { return error }
        fail("Expected safe storage error")
        throw AssertionError()
    }
}
