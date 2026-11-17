package com.voxarium.remote.transport

import com.voxarium.remote.data.security.*
import com.voxarium.remote.data.transport.ProjectSummary
import org.junit.Assert.*
import org.junit.Test

class CreateIntentStoreTest {
    private class Disk : BlobStorage {
        var bytes: ByteArray? = null
        var failWrite = false; var dropWrite = false; var failRead = false
        override fun read(): ByteArray? { check(!failRead); return bytes?.clone() }
        override fun write(bytes: ByteArray) { check(!failWrite); if (!dropWrite) this.bytes = bytes.clone() }
    }
    private val profile = ServerProfile.create("Profile", "https://remote.example/Proxy", "credential", "profile")
    private val project = ProjectSummary("original-id", "Same name", "a".repeat(32), 1, "now")
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected fail closed") } catch (_: SecureStoreException) { }
    }
    @Test fun unknownIsDurableBeforeCallerCanDispatchAndBlocksReplayAfterRestart() {
        val disk = Disk(); val store = CreateIntentStore(disk)
        val intent = store.prepare(profile, "account", project.id, project.displayName)
        assertEquals(CreateOutcome.UNKNOWN, intent.outcome)
        assertEquals(intent, CreateIntentStore(disk).list().single())
        rejected { CreateIntentStore(disk).prepare(profile, "account", project.id, project.displayName) }
    }
    @Test fun onlyDefinitiveAbsencePermitsAnotherExplicitAttempt() {
        val disk = Disk(); val store = CreateIntentStore(disk)
        val first = store.prepare(profile, "account", project.id, project.displayName)
        store.resolve(first, CreateOutcome.NOT_CREATED)
        val next = store.prepare(profile, "account", project.id, "New label")
        assertNotEquals(first.attemptId, next.attemptId)
        rejected { store.resolve(first, CreateOutcome.CREATED, project) }
        assertEquals(next, store.list().single())
    }
    @Test fun createdAndConflictAreNotReplayPermissionAndDoNotBecomeSelected() {
        for (outcome in listOf(CreateOutcome.CREATED, CreateOutcome.CONFLICT)) {
            val disk=Disk();val store=CreateIntentStore(disk)
            val intent=store.prepare(profile,"account",project.id,project.displayName)
            store.resolve(intent,outcome,if(outcome==CreateOutcome.CREATED) project else null)
            assertEquals(outcome,CreateIntentStore(disk).list().single().outcome)
            rejected { store.prepare(profile,"account",project.id,project.displayName) }
        }
    }
    @Test fun exactAccountUrlAndIdNotLabelOrCredentialDefineUniqueness() {
        val store=CreateIntentStore(Disk())
        store.prepare(profile,"a",project.id,"same")
        store.prepare(profile,"b",project.id,"same")
        store.prepare(profile,"a","other-id","same")
        val other=ServerProfile.create("Profile","https://remote.example/proxy","credential","other")
        store.prepare(other,"a",project.id,"same")
        assertEquals(4,store.list().size)
        val duplicate=ServerProfile.create("Different","https://remote.example/Proxy","different","new-profile",2)
        rejected { store.prepare(duplicate,"a",project.id,"different name") }
    }
    @Test fun diskFullDroppedWriteAndReadFailureNeverReturnSendableIntent() {
        for(mode in 0..2) {
            val disk=Disk().apply { failWrite=mode==0;dropWrite=mode==1;failRead=mode==2 }
            rejected { CreateIntentStore(disk).prepare(profile,"account",project.id,project.displayName) }
        }
    }
    @Test fun unknownOrCorruptEnvelopeIsNotAnEmptyStore() {
        val disk=Disk();CreateIntentStore(disk).prepare(profile,"account",project.id,project.displayName)
        val bytes=disk.bytes!!.clone()
        for(index in listOf(0,3,bytes.lastIndex)) {
            disk.bytes=bytes.clone().also { it[index]=(it[index].toInt() xor 1).toByte() }
            rejected { CreateIntentStore(disk).list() }
        }
        disk.bytes=byteArrayOf(1,2,3);rejected { CreateIntentStore(disk).list() }
    }
    @Test fun wrongProjectCannotResolveOriginalIdAndModelDiagnosticsAreRedacted() {
        val store=CreateIntentStore(Disk());val first=store.prepare(profile,"account",project.id,"private label")
        rejected { store.resolve(first,CreateOutcome.CREATED,project.copy(id="wrong")) }
        assertEquals(CreateOutcome.UNKNOWN,store.list().single().outcome)
        assertFalse(first.toString().contains("private"));assertFalse(first.toString().contains(profile.canonicalUrl))
    }
    @Test fun durableResolutionFailureLeavesUnknownAndNeverLosesOriginalId() {
        val disk=Disk();val store=CreateIntentStore(disk);val first=store.prepare(profile,"account",project.id,project.displayName)
        disk.failWrite=true;rejected { store.resolve(first,CreateOutcome.CREATED,project) }
        disk.failWrite=false
        assertEquals(first,CreateIntentStore(disk).list().single())
    }
}
