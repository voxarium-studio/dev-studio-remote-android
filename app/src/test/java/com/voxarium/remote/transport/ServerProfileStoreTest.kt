package com.voxarium.remote.transport

import com.voxarium.remote.data.security.*
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

/** Pure JVM metadata/fault tests; no network, Keystore, device or multi-file activation claims. */
class ServerProfileStoreTest {
    private class Memory : BlobStorage {
        var bytes: ByteArray? = null
        var writes = 0
        var failWrite = false
        var failRead = false
        var dropWrite = false
        var failReadAfterWrite = false
        override fun read(): ByteArray? {
            if (failRead) throw IllegalStateException("private details")
            return bytes?.clone()
        }
        override fun write(bytes: ByteArray) {
            if (failWrite) throw IllegalStateException("private details")
            writes++
            if (!dropWrite) this.bytes = bytes.clone()
            if (failReadAfterWrite) failRead = true
        }
    }

    @Test fun importsEveryProfileWithoutUrlNameOrCredentialDedupe() {
        val v1 = Memory(); val legacy = ProfileStore(v1); val v2 = Memory()
        repeat(2) { legacy.create("Same", "https://example.com/Prefix", "same", "shared") }
        val source = v1.bytes!!.clone()
        val snapshot = ServerProfileStore(v2).migrateLegacy(legacy)
        assertEquals(2, snapshot.profiles.size)
        assertEquals(2, snapshot.legacyImports.size)
        assertEquals(2, snapshot.legacySourceCount)
        assertTrue(snapshot.bindings.isEmpty())
        assertTrue(snapshot.profiles.all { it.canonicalUrl == "https://example.com/Prefix" &&
            it.credentialId == "shared" && it.credentialRevision == 1L && it.verification == null })
        assertEquals(legacy.list().map { it.id }, snapshot.profiles.map { it.localProfileId })
        assertTrue(snapshot.legacyImports.all { it.observedSpaceId == null && it.lastAuthenticatedAccountId == null })
        assertArrayEquals(source, v1.bytes)
        assertNotNull(snapshot.legacySourceSha256)
    }

    @Test fun observedLegacyIdIsCandidateWithExactProvenanceNotBinding() {
        val legacy = ProfileStore(Memory())
        val profile = legacy.create("One", "example.com/a", "old-name", "key")
        val observation = LegacyImportRecord.create(profile, "account", EPOCH)
        val storage = Memory()
        ServerProfileStore(storage).migrateLegacy(legacy, listOf(observation))
        val reloaded = ServerProfileStore(storage).snapshot()
        val record = reloaded.legacyImports.single()
        assertEquals(profile.id, record.recordId)
        assertEquals("old-name", record.spaceName)
        assertEquals("account", record.lastAuthenticatedAccountId)
        assertEquals(EPOCH, record.observedSpaceId)
        assertTrue(reloaded.bindings.isEmpty())
    }

    @Test fun idempotentAfterRestartDoesNotImportStaleV1OrOverwriteV2Edits() {
        val v1 = Memory(); val legacy = ProfileStore(v1); val v2 = Memory()
        val old = legacy.create("Old", "example.com", "name", "key")
        val first = ServerProfileStore(v2).migrateLegacy(legacy)
        val store = ServerProfileStore(v2)
        store.upsert(ServerProfile.create("New", "https://example.com", "key", old.id))
        legacy.create("Late legacy", "example.com", "two", "key")
        val writes = v2.writes
        val again = ServerProfileStore(v2).migrateLegacy(legacy)
        assertEquals(writes, v2.writes)
        assertEquals(first.legacySourceSha256, again.legacySourceSha256)
        assertEquals(1, again.profiles.size)
        assertEquals("New", again.profiles.single().name)
    }

    @Test fun emptyInstallationGetsDurableMarkerWithoutInventingIdentity() {
        val v2 = Memory(); val store = ServerProfileStore(v2)
        val snapshot = store.migrateLegacy(ProfileStore(Memory()))
        assertTrue(snapshot.profiles.isEmpty())
        assertTrue(snapshot.legacyImports.isEmpty())
        assertTrue(snapshot.bindings.isEmpty())
        assertEquals(0, snapshot.legacySourceCount)
        assertNotNull(snapshot.legacySourceSha256)
        assertEquals(snapshot.legacySourceSha256, ServerProfileStore(v2).snapshot().legacySourceSha256)
    }

    @Test fun corruptLegacyDoesNotPublishDestinationOrOverwriteSource() {
        val v1 = Memory(); val legacy = ProfileStore(v1)
        legacy.create("Old", "example.com", "name", "key")
        v1.bytes = v1.bytes!!.copyOf(9)
        val before = v1.bytes!!.clone(); val v2 = Memory()
        assertEquals(SecureStoreException.Reason.CORRUPT, failure { ServerProfileStore(v2).migrateLegacy(legacy) }.reason)
        assertEquals(0, v2.writes)
        assertNull(v2.bytes)
        assertArrayEquals(before, v1.bytes)
    }

    @Test fun corruptUnknownVersionAndTruncatedV2AreNotEmptyOrOverwritten() {
        val legacy = ProfileStore(Memory()); val v2 = Memory()
        legacy.create("Old", "example.com", "name", "key")
        ServerProfileStore(v2).migrateLegacy(legacy)
        val original = v2.bytes!!.clone()
        val future = original.clone().apply { this[3] = 99 }
        val payload = future.copyOfRange(0, future.size - 32)
        MessageDigest.getInstance("SHA-256").digest(payload).copyInto(future, future.size - 32)
        for (bad in listOf(byteArrayOf(), original.copyOf(10), future,
            original.clone().apply { this[lastIndex] = (last().toInt() xor 1).toByte() })) {
            v2.bytes = bad
            val writes = v2.writes
            assertEquals(SecureStoreException.Reason.CORRUPT, failure { ServerProfileStore(v2).migrateLegacy(legacy) }.reason)
            assertEquals(writes, v2.writes)
            assertArrayEquals(bad, v2.bytes)
        }
    }

    @Test fun diskFullPreservesSourceAndPreviousV2AndRetryIsSingleCopy() {
        val v1 = Memory(); val legacy = ProfileStore(v1); val v2 = Memory()
        legacy.create("Old", "example.com", "name", "key")
        val store = ServerProfileStore(v2)
        store.upsert(ServerProfile.create("Independent", "https://example.com", "key", "independent"))
        val source = v1.bytes!!.clone(); val destination = v2.bytes!!.clone()
        v2.failWrite = true
        assertEquals(SecureStoreException.Reason.STORAGE, failure { store.migrateLegacy(legacy) }.reason)
        assertArrayEquals(source, v1.bytes); assertArrayEquals(destination, v2.bytes)
        assertNull(store.snapshot().legacySourceSha256)
        v2.failWrite = false
        assertEquals(2, ServerProfileStore(v2).migrateLegacy(legacy).profiles.size)
        assertEquals(1, ServerProfileStore(v2).snapshot().legacyImports.size)
    }

    @Test fun failedReadbackNeverReturnsSuccessfulMigrationAndCanResumeCommittedMetadata() {
        val v1 = Memory(); val legacy = ProfileStore(v1); val v2 = Memory()
        legacy.create("Old", "example.com", "name", "key")
        val before = v1.bytes!!.clone()
        v2.failReadAfterWrite = true
        val error = failure { ServerProfileStore(v2).migrateLegacy(legacy) }
        assertEquals(SecureStoreException.Reason.STORAGE, error.reason)
        assertNull(error.cause)
        assertFalse(error.stackTraceToString().contains("private details"))
        assertArrayEquals(before, v1.bytes)
        v2.failRead = false; v2.failReadAfterWrite = false
        val writes = v2.writes
        val resumed = ServerProfileStore(v2).migrateLegacy(legacy)
        assertEquals(1, resumed.profiles.size)
        assertTrue(resumed.bindings.isEmpty())
        assertEquals(writes, v2.writes)
    }

    @Test fun silentlyDroppedWriteFailsClosedAndReadFailureIsNotMissing() {
        val legacy = ProfileStore(Memory()); val v2 = Memory()
        legacy.create("Old", "example.com", "name", "key")
        v2.dropWrite = true
        assertEquals(SecureStoreException.Reason.STORAGE, failure { ServerProfileStore(v2).migrateLegacy(legacy) }.reason)
        assertNull(v2.bytes)
        v2.dropWrite = false; v2.failRead = true
        val writes = v2.writes
        assertEquals(SecureStoreException.Reason.STORAGE, failure { ServerProfileStore(v2).migrateLegacy(legacy) }.reason)
        assertEquals(writes, v2.writes)
    }

    @Test fun observationMustMatchExactProfileUrlCredentialAndSelector() {
        val legacy = ProfileStore(Memory()); val p = legacy.create("Old", "example.com/a", "name", "key")
        val foreign = SavedProfile.create("Old", "example.com/b", "name", "key", p.id)
        val v2 = Memory()
        failure { ServerProfileStore(v2).migrateLegacy(legacy, listOf(LegacyImportRecord.create(foreign, "account", EPOCH))) }
        failure { ServerProfileStore(v2).migrateLegacy(legacy, listOf(LegacyImportRecord.create(p), LegacyImportRecord.create(p))) }
        assertEquals(0, v2.writes)
        failure { LegacyImportRecord.create(p, observedSpaceId = EPOCH) }
    }

    @Test fun existingConflictingLocalIdDoesNotGetSilentlyOverwritten() {
        val legacy = ProfileStore(Memory()); val p = legacy.create("Old", "example.com", "name", "key")
        val v2 = Memory(); val store = ServerProfileStore(v2)
        store.upsert(ServerProfile.create("Independent", "https://different.com", "other", p.id))
        val before = v2.bytes!!.clone()
        failure { store.migrateLegacy(legacy) }
        assertArrayEquals(before, v2.bytes)
    }

    @Test fun projectIdsAndEpochsAreExactAndBindingsCaptureCredentialAndUrl() {
        val v2 = Memory(); val store = ServerProfileStore(v2)
        val profile = ServerProfile.create("Server", "https://example.com/Prefix", "key", "server", 4)
        store.upsert(profile)
        val a = ProjectBinding.create(profile, "a", "account", EPOCH, "binding-a")
        store.addBinding(a)
        store.addBinding(ProjectBinding.create(profile, "b", "account", EPOCH, "binding-b"))
        failure { store.addBinding(ProjectBinding.create(profile, "b", "account", EPOCH, "binding-a")) }
        store.upsert(ServerProfile.create("Renamed", "https://example.com/Other", "key", "server", 5))
        val reopened = ServerProfileStore(v2).snapshot()
        val old = reopened.bindings.first()
        assertEquals("a", old.projectId); assertEquals("https://example.com/Prefix", old.canonicalUrl)
        assertEquals(4L, old.credentialRevision); assertEquals("key", old.credentialId)
        failure { store.addBinding(ProjectBinding.create(profile, "c", "account", EPOCH, "stale")) }
        for (id in listOf(" A", "A", "a ", "а", "a/b", "a".repeat(65))) {
            failure { ProjectBinding.create(profile, id, "account", EPOCH) }
        }
        for (epoch in listOf("", EPOCH.uppercase(), "0".repeat(31), "00000000-0000-0000-0000-000000000000")) {
            failure { ProjectBinding.create(profile, "a", "account", epoch) }
        }
    }

    @Test fun historicalVerificationIsExactAndCredentialRevisionCannotGoBackwards() {
        val v2 = Memory(); val store = ServerProfileStore(v2)
        val verification = ServerVerification.create("https://example.com", "key", 2, 1, "account", 2, 123, "Account", "#123456", 2)
        store.upsert(ServerProfile.create("One", "https://example.com", "key", "server", 2, verification))
        val history = ServerProfileStore(v2).snapshot().profiles.single().verification!!
        assertEquals("account", history.accountId); assertEquals(123L, history.checkedAtEpochMillis)
        assertEquals("Account", history.accountDisplayName); assertEquals("#123456", history.accountColor)
        failure { ServerProfile.create("One", "https://other.com", "key", "server", 2, verification) }
        failure { store.upsert(ServerProfile.create("One", "https://example.com", "key", "server", 1)) }
        failure { store.upsert(ServerProfile.create("One", "https://example.com", "different", "server", 2)) }
        store.upsert(ServerProfile.create("One", "https://example.com", "key", "server", 3))
        assertNull(store.snapshot().profiles.single().verification)
    }

    @Test fun valuesAreValidatedSnapshotsDoNotExposeMutableCollectionsOrSensitiveToString() {
        val v2 = Memory(); val store = ServerProfileStore(v2)
        val profile = ServerProfile.create("Private", "https://example.com", "credential-private", "server")
        store.upsert(profile)
        val snapshot = store.snapshot()
        try { (snapshot.profiles as MutableList).clear(); fail("Mutable snapshot") }
        catch (_: UnsupportedOperationException) { }
        assertFalse(profile.toString().contains("Private"))
        assertFalse(profile.toString().contains("credential-private"))
        for (url in listOf("example.com", "https://EXAMPLE.com", "https://example.com/", "https://u@example.com",
            "http://example.com", "https://example.com/?key=private")) {
            failure { ServerProfile.create("One", url, "key") }
        }
        failure { ServerProfile.create("One", "https://example.com", "key", credentialRevision = 0) }
        failure { ServerProfile.create("One", "https://example.com", "key", canonicalizerVersion = 2) }
    }

    @Test fun twoStoreOwnersSerializeMigrationWithoutDuplicateCopies() {
        val source = ProfileStore(Memory()); source.create("Old", "example.com", "space", "key")
        val v2 = Memory(); val start = java.util.concurrent.CountDownLatch(1)
        val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val workers = List(2) {
            Thread {
                try { start.await(); ServerProfileStore(v2).migrateLegacy(source) }
                catch (e: Throwable) { errors.add(e) }
            }.also { it.start() }
        }
        start.countDown(); workers.forEach { it.join(5000); assertFalse(it.isAlive) }
        assertTrue(errors.isEmpty())
        assertEquals(1, v2.writes)
        assertEquals(1, ServerProfileStore(v2).snapshot().legacyImports.size)
    }

    @Test fun malformedDestinationsAndDanglingBindingsNeverRegister() {
        val profile = ServerProfile.create("One", "https://example.com", "key", "server")
        val v2 = Memory(); val store = ServerProfileStore(v2)
        failure { store.addBinding(ProjectBinding.create(profile, "a", "account", EPOCH)) }
        store.upsert(profile)
        failure { store.addBinding(ProjectBinding.create(profile, "a", "account", EPOCH, legacyImportRecordId = "missing")) }
        assertTrue(store.snapshot().bindings.isEmpty())
        failure { ProjectBinding.create(profile, "a", "", EPOCH) }
        failure { ProjectBinding.create(profile, "a", "account", EPOCH, catalogContractVersion = 1) }
        // A checksummed, otherwise valid envelope with trailing bytes is still corruption.
        val old = v2.bytes!!
        val payload = old.copyOfRange(0, old.size - 32) + byteArrayOf(0)
        v2.bytes = payload + MessageDigest.getInstance("SHA-256").digest(payload)
        assertEquals(SecureStoreException.Reason.CORRUPT, failure { store.snapshot() }.reason)
    }

    private fun failure(block: () -> Unit): SecureStoreException {
        try { block() } catch (e: SecureStoreException) { return e }
        throw AssertionError("Expected safe metadata failure")
    }
    private companion object { const val EPOCH = "0123456789abcdef0123456789abcdef" }
    @Test fun findOrAddBindingReusesExactIdentityWithoutWriteAndRejectsRetargetedId() {
        val disk=Memory();val store=ServerProfileStore(disk)
        val profile=ServerProfile.create("Profile","https://example.com/Prefix","key","profile",4)
        store.upsert(profile)
        val first=store.findOrAddBinding(ProjectBinding.create(profile,"id","account",EPOCH,"original"))
        val bytes=disk.bytes!!.toList();val writes=disk.writes
        disk.failWrite=true
        val reused=ServerProfileStore(disk).findOrAddBinding(ProjectBinding.create(profile,"id","account",EPOCH,"new-random-id"))
        assertEquals(first.bindingId,reused.bindingId);assertEquals(writes,disk.writes);assertEquals(bytes,disk.bytes!!.toList())
        failure {store.findOrAddBinding(ProjectBinding.create(profile,"different","account",EPOCH,"original"))}
    }

    @Test fun bindingReuseIncludesProfileUrlAccountEpochCredentialRevisionAndProvenance() {
        val disk=Memory();val store=ServerProfileStore(disk)
        var profile=ServerProfile.create("Profile","https://example.com/Prefix","key","profile",1)
        store.upsert(profile)
        val ids=mutableSetOf<String>()
        fun add(p:ServerProfile,project:String="id",account:String="account",epoch:String=EPOCH) {
            ids+=store.findOrAddBinding(ProjectBinding.create(p,project,account,epoch)).bindingId
        }
        add(profile);add(profile,project="other-id");add(profile,account="other-account");add(profile,epoch="b".repeat(32))
        val second=ServerProfile.create("Same label",profile.canonicalUrl,"key","other-profile",1);store.upsert(second);add(second)
        val old=profile;profile=ServerProfile.create("Renamed",profile.canonicalUrl,"key","profile",2);store.upsert(profile);add(profile)
        failure {store.findOrAddBinding(ProjectBinding.create(old,"id","account",EPOCH))}
        profile=ServerProfile.create("Profile",profile.canonicalUrl,"new-key","profile",3);store.upsert(profile);add(profile)
        profile=ServerProfile.create("Profile","https://example.com/prefix","new-key","profile",3);store.upsert(profile);add(profile)
        assertEquals(8,ids.size);assertEquals(8,store.snapshot().bindings.size)
        val legacy=ProfileStore(Memory());val source=legacy.create("Legacy","https://legacy.example","game","legacy-key")
        val legacyStore=ServerProfileStore(Memory());val migrated=legacyStore.migrateLegacy(legacy);val imported=migrated.profiles.single()
        val noProvenance=legacyStore.findOrAddBinding(ProjectBinding.create(imported,"id","account",EPOCH))
        val proven=legacyStore.findOrAddBinding(ProjectBinding.create(imported,"id","account",EPOCH,legacyImportRecordId=source.id))
        assertNotEquals(noProvenance.bindingId,proven.bindingId)
        assertEquals(proven.bindingId,legacyStore.findOrAddBinding(ProjectBinding.create(imported,"id","account",EPOCH,
            legacyImportRecordId=source.id)).bindingId)
    }

    @Test fun latestAuthenticatedAccountAssociationPersistsAcrossStoreReconstruction() {
        val disk=Memory();val store=ServerProfileStore(disk)
        for(account in listOf("account-a","account-b")) {
            val v=ServerVerification.create("https://example.com","key",1,1,account,2,123,account,"#123456",2)
            store.upsert(ServerProfile.create("Profile","https://example.com","key","profile",1,v))
        }
        assertEquals("account-b",ServerProfileStore(disk).snapshot().profiles.single().verification!!.accountId)
    }


}
