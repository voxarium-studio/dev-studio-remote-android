package com.voxarium.remote.documents

import com.voxarium.remote.data.documents.*
import com.voxarium.remote.data.room.*
import com.voxarium.remote.data.transport.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import android.system.Os
import android.system.OsConstants
import android.system.ErrnoException
import android.system.StructStat
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.AccessDeniedException
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** Offline commit fault model, not Android OS-kill/power-loss or a transport acceptance gate. */
class IdentityMigrationTest {
    private val server = "https://remote.example/proxy"
    private val account = "account-a"
    private val id = "0123456789ab4cde8123456789abcdef"
    private val otherId = "fedcba98765443218123fedcba987654"
    private val source = LegacyDocumentSource(server, account, listOf(LegacyDocumentProfile("profile", "credential", "game")))
    private val target get() = DocumentPartition(server, id, account, id)
    private val text = "---\r\ntitle: Exact\r\n---\r\n\r\n# 😀 Снимок e\u0301\r\n"
    private val newer = text + "newer draft\r\n"
    private val namespace get() = DocumentNamespace(server, account, id)
    private val journalKey get() = DocumentNamespace(server, account, "@journal").storageKey()
    private fun tagged(vararg values: String) = values.joinToString("") { "${it.length}:$it" }
    private class Crash : Error()
    private class Memory : DocumentStorage {
        val drafts = linkedMapOf<String, ByteArray>()
        val caches = linkedMapOf<String, ByteArray>()
        val writes = mutableListOf<Pair<String, Boolean>>()
        var crashAfter: Int? = null
        var diskFullAt: Int? = null
        var dropWriteAt: Int? = null
        var corruptReadKey: String? = null
        var failingReadKey: String? = null
        override fun readDrafts(namespace: String) = read(drafts, namespace)
        override fun readCache(namespace: String) = read(caches, namespace)
        private fun read(m: Map<String, ByteArray>, key: String): ByteArray? {
            if (failingReadKey == key) throw DocumentStorageException(DocumentStorageException.Reason.STORAGE)
            val bytes = m[key]?.clone() ?: return null
            return if (corruptReadKey == key) bytes + 0 else bytes
        }
        override fun writeDrafts(namespace: String, bytes: ByteArray) = write(drafts, namespace, bytes, false)
        override fun writeCache(namespace: String, bytes: ByteArray) = write(caches, namespace, bytes, true)
        private fun write(m: MutableMap<String, ByteArray>, key: String, bytes: ByteArray, cache: Boolean) {
            if (writes.size == diskFullAt) throw DocumentStorageException(DocumentStorageException.Reason.STORAGE)
            val count = writes.size
            if (count != dropWriteAt) m[key] = bytes.clone()
            writes.add(key to cache)
            if (writes.size == crashAfter) throw Crash()
        }
        fun copy() = Memory().also { c -> drafts.forEach { (k,v) -> c.drafts[k] = v.clone() }; caches.forEach { (k,v) -> c.caches[k] = v.clone() } }
    }
    private fun fixture(): Memory = Memory().apply {
        val base = DocumentVersion(8, documentHash("base"), false, "base")
        val draft = DocumentDraft("note.md", newer, 14, base, DraftKind.ROOM)
        val pending = PendingDocumentWrite(31, "note.md", text, 13, 8, "old-sid", 21)
        val recovery = DocumentRecovery(30, id, "note.md", "conflict\r\n", 12, base, RecoveryReason.CONFLICT, "reserved.md")
        val journal = Journal(80, 100, mapOf(id to SpaceJournal(mapOf("note.md" to draft), mapOf("note.md" to pending), listOf(recovery)),
            otherId to SpaceJournal(mapOf("other.md" to DocumentDraft("other.md", "other account-space", 15, null, DraftKind.LOCAL)))),
            mapOf("game" to id, "other" to otherId))
        drafts[journalKey] = DocumentStateCodec.journal(journal)
        val mirror = Mirror(true, 9, mapOf("note.md" to base, "gone.md" to DocumentVersion(9, null, true, null)))
        caches[namespace.storageKey()] = DocumentStateCodec.mirror(mirror)
        drafts[tagged("profile-binding.v1", server, "game", "profile", "credential")] = DocumentStateCodec.identity(namespace)
        RoomRecoveryStore(this).persist(namespace, "note.md", EditorContext(19, 21, "old-sid"), null, listOf("AAE=", "//8=", ""))
        RoomRecoveryStore(this).persist(DocumentNamespace(server, account, otherId), "other.md", EditorContext(22, 23, "old-other"), RoomSnapshot("last-valid\r\n", 25), listOf("AA=="))
        writes.clear()
    }
    private fun failure(reason: IdentityMigrationFailure, block: () -> Unit) {
        try { block(); fail("expected migration failure") }
        catch (e: IdentityMigrationException) { assertEquals(reason, e.reason); assertNull(e.cause); assertEquals("Identity migration: ${reason.name}", e.message) }
    }
    private fun unchanged(a: Memory, b: Memory) {
        assertEquals(a.drafts.keys, b.drafts.keys); assertEquals(a.caches.keys, b.caches.keys)
        a.drafts.forEach { (k,v) -> assertArrayEquals(v, b.drafts[k]) }
        a.caches.forEach { (k,v) -> assertArrayEquals(v, b.caches[k]) }
    }
    private fun verifyImported(legacy: Memory, dest: Memory) {
        val migrated = DocumentStateCodec.journal(dest.drafts.getValue(target.journalKey()))
        assertEquals(setOf(id), migrated.spaces.keys)
        assertEquals(80, migrated.nextId); assertEquals(100, migrated.nextGeneration)
        val state = migrated.spaces.getValue(id)
        assertEquals(text, state.pending.getValue("note.md").content)
        assertEquals(13, state.pending.getValue("note.md").generation)
        assertNull(state.pending.getValue("note.md").live); assertEquals(0, state.pending.getValue("note.md").connectionGeneration)
        assertEquals(newer, state.drafts.getValue("note.md").content); assertEquals(14, state.drafts.getValue("note.md").generation)
        assertEquals("reserved.md", state.recoveries.single().copyPath)
        assertArrayEquals(legacy.caches[namespace.storageKey()], dest.caches[target.namespace.storageKey()])
        val migration = IdentityMigration(legacy, dest)
        assertArrayEquals(legacy.drafts[journalKey], migration.archivedAccountJournal(source))
        assertArrayEquals(legacy.caches[namespace.storageKey()], migration.archivedMirror(source, id))
        val originalRoom = RoomRecoveryStore(legacy).archive(namespace)
        assertArrayEquals(originalRoom, migration.archivedRoom(source, id))
        val archived = Memory(); archived.drafts[RoomRecoveryStore(archived).key(namespace)] = migration.archivedRoom(source, id)!!
        val raw = RoomRecoveryStore(archived).list(namespace).single()
        assertNull(raw.snapshot); assertEquals(namespace, raw.namespace); assertEquals(EditorContext(19, 21, "old-sid"), raw.context)
        assertEquals(listOf("AAE=", "//8=", ""), raw.raw)
    }

    @Test fun exactCopiesRemainOfflineUntilSeparateExplicitAtomicActivation() {
        val legacy = fixture(); val before = legacy.copy(); val dest = Memory()
        val migration = IdentityMigration(legacy, dest)
        val prepared = migration.resume(source)
        assertEquals(IdentityMigrationPhase.VERIFIED, prepared.phase); assertTrue(prepared.activated.isEmpty())
        assertFalse(dest.drafts.containsKey(IdentityMigration.ACTIVATION_KEY))
        assertEquals(mapOf("drafts" to 1, "pending" to 1, "recoveries" to 1, "files" to 2, "rooms" to 1), prepared.candidates.first { it.namespace.spaceId == id }.counts)
        // Every recovery/journal/archive commit precedes every replaceable cache commit.
        val firstCache = dest.writes.indexOfFirst { it.second }
        assertTrue(firstCache > 0)
        assertTrue(dest.writes.take(firstCache).any { it.first == target.journalKey() })
        verifyImported(legacy, dest)
        val active = migration.activate(source, prepared.sourceFingerprint, target)
        assertEquals(IdentityMigrationPhase.ACTIVATED, active.phase); assertEquals(setOf(id), active.activated)
        assertTrue(IdentityMigration.isActivated(dest, target))
        assertFalse(IdentityMigration.isActivated(dest, target.copy(storageEpoch = "f".repeat(32))))
        unchanged(before, legacy)
    }

    @Test fun crashAfterEveryCopyAndPhaseCommitResumesWithoutDuplicateOrLostGenerations() {
        val baselineSource = fixture(); val baselineDest = Memory()
        IdentityMigration(baselineSource, baselineDest).resume(source)
        for (commit in 1..baselineDest.writes.size) {
            val legacy = fixture(); val before = legacy.copy(); val dest = Memory().apply { crashAfter = commit }
            try { IdentityMigration(legacy, dest).resume(source); fail("expected crash at $commit") } catch (_: Crash) { }
            assertFalse(IdentityMigration.isActivated(dest, target))
            dest.crashAfter = null
            val resumed = IdentityMigration(legacy, dest).resume(source)
            assertEquals(IdentityMigrationPhase.VERIFIED, resumed.phase)
            verifyImported(legacy, dest); unchanged(before, legacy)
        }
    }

    @Test fun crashAfterActivationCommitIsAlreadyActivatedAndNeverImportsStaleSourceAgain() {
        val legacy = fixture(); val dest = Memory(); val migration = IdentityMigration(legacy, dest)
        val s = migration.resume(source)
        dest.crashAfter = dest.writes.size + 1
        try { migration.activate(source, s.sourceFingerprint, target); fail() } catch (_: Crash) { }
        dest.crashAfter = null
        val journal = DocumentStateCodec.journal(dest.drafts.getValue(target.journalKey()))
        val changed = journal.copy(nextGeneration = 101, spaces = mapOf(id to journal.spaces.getValue(id).copy(drafts = mapOf("new.md" to DocumentDraft("new.md", "new v2 edit", 100, null, DraftKind.LOCAL)))))
        dest.drafts[target.journalKey()] = DocumentStateCodec.journal(changed)
        legacy.drafts[journalKey] = "stale or changed source".toByteArray()
        val before = dest.copy(); val resumed = IdentityMigration(legacy, dest).resume(source)
        assertEquals(IdentityMigrationPhase.ACTIVATED, resumed.phase)
        migration.activate(source, s.sourceFingerprint, target) // repeated explicit action is a no-op
        unchanged(before, dest)
    }

    @Test fun diskFullAtEveryCopyCommitLeavesSourceAndActivationUntouchedThenResumes() {
        val baseline = Memory(); IdentityMigration(fixture(), baseline).resume(source)
        for (operation in baseline.writes.indices) {
            val legacy = fixture(); val before = legacy.copy(); val dest = Memory().apply { diskFullAt = operation }
            failure(IdentityMigrationFailure.STORAGE) { IdentityMigration(legacy, dest).resume(source) }
            assertFalse(dest.drafts.containsKey(IdentityMigration.ACTIVATION_KEY)); unchanged(before, legacy)
            dest.diskFullAt = null; IdentityMigration(legacy, dest).resume(source); verifyImported(legacy, dest)
        }
    }

    @Test fun droppedWritesAndBadReadbackAtEveryCopyCommitCannotActivate() {
        val baseline = Memory(); IdentityMigration(fixture(), baseline).resume(source)
        for (operation in baseline.writes.indices) {
            val legacy = fixture(); val dest = Memory().apply { dropWriteAt = operation }
            failure(IdentityMigrationFailure.STORAGE) { IdentityMigration(legacy, dest).resume(source) }
            assertFalse(dest.drafts.containsKey(IdentityMigration.ACTIVATION_KEY))
        }
        val legacy = fixture(); val dest = Memory(); val m = IdentityMigration(legacy, dest); val s = m.resume(source)
        dest.corruptReadKey = target.journalKey()
        failure(IdentityMigrationFailure.CORRUPT) { m.activate(source, s.sourceFingerprint, target) }
        assertFalse(dest.drafts.containsKey(IdentityMigration.ACTIVATION_KEY))
        dest.corruptReadKey = null; dest.failingReadKey = target.namespace.storageKey()
        failure(IdentityMigrationFailure.STORAGE) { m.activate(source, s.sourceFingerprint, target) }
        assertFalse(dest.drafts.containsKey(IdentityMigration.ACTIVATION_KEY))
    }

    @Test fun corruptUnknownOrDuplicateLegacyJournalFailsBeforeAnyDestinationCommit() {
        val good = fixture().drafts.getValue(journalKey).toString(Charsets.UTF_8)
        val variants = listOf("", "{}", good.replaceFirst("\"v\":1", "\"v\":9"), good.replaceFirst("{", "{\"v\":1,"),
            good + " trailing", good.replaceFirst("\"nextId\":80", "\"nextId\":\"80\""))
        variants.forEach { bytes ->
            val legacy = fixture(); legacy.drafts[journalKey] = bytes.toByteArray(); val dest = Memory()
            failure(IdentityMigrationFailure.CORRUPT) { IdentityMigration(legacy, dest).resume(source) }
            assertTrue(dest.writes.isEmpty())
        }
        val legacy = fixture(); val roomKey = RoomRecoveryStore(legacy).key(namespace)
        legacy.drafts[roomKey] = "{}".toByteArray(); val dest = Memory()
        failure(IdentityMigrationFailure.CORRUPT) { IdentityMigration(legacy, dest).resume(source) }; assertTrue(dest.writes.isEmpty())
    }

    @Test fun ambiguousNamesAndUnresolvedRawOnlyNamespaceAreArchiveOnly() {
        val legacy = fixture(); val j = DocumentStateCodec.journal(legacy.drafts.getValue(journalKey))
        legacy.drafts[journalKey] = DocumentStateCodec.journal(j.copy(lastSpace = j.lastSpace + ("alias" to id)))
        val rawOnly = "raw-only-space"
        RoomRecoveryStore(legacy).persist(DocumentNamespace(server, account, rawOnly), "raw.md", EditorContext(1, 1, "sid"), null, listOf("/w=="))
        val inventory = source.copy(recoverySpaceIds = setOf(rawOnly))
        val dest = Memory(); val m = IdentityMigration(legacy, dest); val s = m.resume(inventory)
        assertTrue(s.candidates.first { it.namespace.spaceId == id }.archiveOnly)
        assertTrue(s.candidates.first { it.namespace.spaceId == rawOnly }.archiveOnly)
        assertFalse(dest.drafts.containsKey(target.journalKey()))
        failure(IdentityMigrationFailure.UNPROVEN_MAPPING) { m.activate(inventory, s.sourceFingerprint, target) }
        assertNotNull(m.archivedRoom(inventory, rawOnly)); assertArrayEquals(legacy.drafts[journalKey], m.archivedAccountJournal(inventory))
    }

    @Test fun tupleMismatchAndChangedSourceOrInventoryCannotActivateOrReassignQueue() {
        val legacy = fixture(); val dest = Memory(); val m = IdentityMigration(legacy, dest); val s = m.resume(source)
        listOf(target.copy(canonicalServerUrl = "https://remote.example/other"), target.copy(projectId = "different"),
            target.copy(accountId = "account-b"), target.copy(storageEpoch = "f".repeat(32))).forEach { p ->
            failure(IdentityMigrationFailure.UNPROVEN_MAPPING) { m.activate(source, s.sourceFingerprint, p) }
        }
        failure(IdentityMigrationFailure.UNPROVEN_MAPPING) { m.activate(source, "0".repeat(64), target) }
        val j = DocumentStateCodec.journal(legacy.drafts.getValue(journalKey))
        legacy.drafts[journalKey] = DocumentStateCodec.journal(j.copy(nextGeneration = 101))
        failure(IdentityMigrationFailure.SOURCE_CHANGED) { m.activate(source, s.sourceFingerprint, target) }
        failure(IdentityMigrationFailure.SOURCE_CHANGED) { m.resume(source) }
        assertFalse(dest.drafts.containsKey(IdentityMigration.ACTIVATION_KEY))
    }

    @Test fun occupiedDestinationAndCorruptedStagingAreNeverOverwritten() {
        val legacy = fixture(); val dest = Memory(); dest.drafts[target.journalKey()] = "existing".toByteArray()
        failure(IdentityMigrationFailure.DESTINATION_OCCUPIED) { IdentityMigration(legacy, dest).resume(source) }
        assertEquals("existing", dest.drafts.getValue(target.journalKey()).toString(Charsets.UTF_8))
        val fresh = Memory(); val m = IdentityMigration(legacy, fresh); m.resume(source)
        fresh.drafts[target.journalKey()] = "damaged".toByteArray()
        failure(IdentityMigrationFailure.CORRUPT) { m.resume(source) }
        assertEquals("damaged", fresh.drafts.getValue(target.journalKey()).toString(Charsets.UTF_8))
    }

    @Test fun stagedRepositoryCannotReadUntilActivationAndActiveReaderHasZeroTransportEffects() {
        val legacy = fixture(); val dest = Memory(); val m = IdentityMigration(legacy, dest); val s = m.resume(source)
        val repo = DocumentsRepository(dest)
        try { repo.selectPartitionOffline(target); fail() } catch (e: DocumentRepositoryException) { assertEquals(DocumentFailure.NOT_SYNCED, e.reason) }
        m.activate(source, s.sourceFingerprint, target)
        val owner = repo.selectPartitionOffline(target); val v = repo.view()
        assertEquals(target.namespace, v.namespace); assertFalse(v.synced)
        assertEquals(newer, v.drafts.getValue("note.md").content); assertEquals(text, v.pending.getValue("note.md").content)
        val hello = ServerFrame.Hello(FrameCodec.PROTOCOL_VERSION.toLong(), RemoteUser(account, "user", "#123456"), RemoteSpace(id, "game"), 9)
        assertTrue(repo.onEvent(owner, ConnectionEvent.Frame(900, hello)).isEmpty())
        assertTrue(repo.onEvent(owner, ConnectionEvent.Frame(900, ServerFrame.Ack(FrameId.Integer(31), "note.md", 10, documentHash(text), false))).isEmpty())
        assertEquals(newer, repo.view().drafts.getValue("note.md").content)
        assertEquals(text, repo.view().pending.getValue("note.md").content)
        assertEquals(2, repo.view().documents.size)
    }

    @Test fun everyTupleComponentAndVersionHasAnUnambiguousPrivateStorageKey() {
        val partitions = listOf(target, target.copy(canonicalServerUrl = "https://remote.example/Proxy"),
            target.copy(canonicalServerUrl = "https://remote.example/other"), target.copy(projectId = otherId),
            target.copy(accountId = "account-b"), target.copy(storageEpoch = "f".repeat(32)))
        val keys = partitions.map { it.namespace.storageKey() }
        assertEquals(partitions.size, keys.toSet().size)
        assertFalse(namespace.storageKey() in keys)
        assertEquals(partitions.size, partitions.map { it.journalKey() }.toSet().size)
        assertTrue(partitions.all { it.toString() == "DocumentPartition(redacted)" })
    }

    @Test fun separateAccountPartitionAndActivationNeverAcquireEachOthersQueue() {
        val dest = Memory()
        for (user in listOf("account-a", "account-b")) {
            val legacy = Memory()
            val source = LegacyDocumentSource(server, user)
            val p = DocumentPartition(server, id, user, id)
            val old = DocumentNamespace(server, user, "@journal").storageKey()
            val journal = Journal(2, 3, mapOf(id to SpaceJournal(
                drafts = mapOf("note.md" to DocumentDraft("note.md", "draft-$user", 2, null, DraftKind.LOCAL)),
                pending = mapOf("note.md" to PendingDocumentWrite(1, "note.md", "pending-$user", 1, 0, null, 7)))), mapOf("game" to id))
            legacy.drafts[old] = DocumentStateCodec.journal(journal)
            val m = IdentityMigration(legacy, dest); val s = m.resume(source); m.activate(source, s.sourceFingerprint, p)
            val repo = DocumentsRepository(dest); repo.selectPartitionOffline(p)
            assertEquals("draft-$user", repo.view().drafts.getValue("note.md").content)
            assertEquals("pending-$user", repo.view().pending.getValue("note.md").content)
        }
        val first = DocumentsRepository(dest); first.selectPartitionOffline(target)
        assertEquals("pending-account-a", first.view().pending.getValue("note.md").content)
    }

    @Test fun secondPartitionActivationDoesNotRevalidateOrOverwriteNewEditsInFirst() {
        val legacy = fixture(); val dest = Memory(); val m = IdentityMigration(legacy, dest); val s = m.resume(source)
        m.activate(source, s.sourceFingerprint, target)
        dest.drafts[target.journalKey()] = DocumentStateCodec.journal(Journal(nextGeneration = 2, spaces = mapOf(id to SpaceJournal(
            drafts = mapOf("new.md" to DocumentDraft("new.md", "new v2 edit", 1, null, DraftKind.LOCAL))))))
        val before = dest.drafts.getValue(target.journalKey()).clone()
        val second = DocumentPartition(server, otherId, account, otherId)
        val activated = m.activate(source, s.sourceFingerprint, second)
        assertEquals(setOf(id, otherId), activated.activated)
        assertArrayEquals(before, dest.drafts.getValue(target.journalKey()))
        assertTrue(IdentityMigration.isActivated(dest, second))
    }

    @Test fun diskFullAtFinalActivationCommitDoesNotPublishOrModifyAnySource() {
        val legacy = fixture(); val before = legacy.copy(); val dest = Memory(); val m = IdentityMigration(legacy, dest); val s = m.resume(source)
        dest.diskFullAt = dest.writes.size
        failure(IdentityMigrationFailure.STORAGE) { m.activate(source, s.sourceFingerprint, target) }
        assertFalse(dest.drafts.containsKey(IdentityMigration.ACTIVATION_KEY)); unchanged(before, legacy)
        dest.diskFullAt = null
        assertEquals(IdentityMigrationPhase.ACTIVATED, m.activate(source, s.sourceFingerprint, target).phase)
    }

    @Test fun absentSourceMirrorReservesTargetAgainstExistingAndLaterForeignCache() {
        for (late in listOf(false, true)) {
            val legacy = fixture().apply { caches.remove(namespace.storageKey()) }
            val dest = Memory(); val m = IdentityMigration(legacy, dest)
            val status = if (late) m.resume(source) else null
            val foreign = DocumentStateCodec.mirror(Mirror(true, 99, mapOf("foreign.md" to DocumentVersion(99, documentHash("FOREIGN"), false, "FOREIGN"))))
            dest.caches[target.namespace.storageKey()] = foreign
            failure(IdentityMigrationFailure.DESTINATION_OCCUPIED) {
                if (late) m.activate(source, status!!.sourceFingerprint, target) else m.resume(source)
            }
            assertFalse(dest.drafts.containsKey(IdentityMigration.ACTIVATION_KEY))
            assertArrayEquals(foreign, dest.caches[target.namespace.storageKey()])
            if (!late) assertFalse(dest.drafts.keys.any { it.startsWith("21:identity-migration.v2") })
        }
    }

    @Test fun slugDashedUppercaseAndMalformedLegacyIdsStayArchiveOnly() {
        for (oldId in listOf("game", "01234567-89ab-4cde-8123-456789abcdef", "A".repeat(32), "g".repeat(32), "a".repeat(31))) {
            val legacy = Memory()
            legacy.drafts[journalKey] = DocumentStateCodec.journal(Journal(spaces = mapOf(oldId to SpaceJournal()), lastSpace = mapOf("game" to oldId)))
            val dest = Memory(); val migration = IdentityMigration(legacy, dest); val s = migration.resume(LegacyDocumentSource(server, account))
            assertTrue(s.candidates.single().archiveOnly)
            assertTrue(dest.caches.isEmpty())
            assertNotNull(migration.archivedAccountJournal(LegacyDocumentSource(server, account)))
        }
        for (epoch in listOf("game", "A".repeat(32), "a".repeat(31), "01234567-89ab-4cde-8123-456789abcdef")) {
            try { target.copy(storageEpoch = epoch); fail() } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun partitionUsesTheSameExactUrlAndAccountRulesAsA1() {
        for (url in listOf("https://REMOTE.example/proxy", "https://remote.example:443/proxy", "https://remote.example/proxy/", "https://user@remote.example/proxy", "https://remote.example/proxy?x=1", "https://")) {
            try { target.copy(canonicalServerUrl = url); fail(url) } catch (_: IllegalArgumentException) { }
        }
        for (user in listOf("", " ", "a".repeat(257), "bad\u0000user", "\uD800")) {
            try { target.copy(accountId = user); fail() } catch (_: IllegalArgumentException) { }
        }
        val longUrl = "https://remote.example/" + "a".repeat(5000)
        assertEquals(longUrl, target.copy(canonicalServerUrl = longUrl).canonicalServerUrl)
    }
}


/** Actual Android AtomicFile/envelopes and namespace discovery, including API23 backup rules.
 * Only host errno/POSIX rename primitives are adapted, just as in DocumentStorageTest. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35], shadows = [AtomicIdentityMigrationTest.InventoryOs::class, DocumentStorageTest.PosixAtomicRename::class])
class AtomicIdentityMigrationTest {
    @get:Rule val temporary = TemporaryFolder(File(
        System.getenv("VOXARIUM_DOCUMENTS_TEST_TEMP") ?: System.getProperty("java.io.tmpdir"),
        "identity-migration-tests").apply { check(isDirectory || mkdirs()) })
    private val url = "https://remote.example/proxy"
    private val account = "account-a"
    private val id = "0123456789ab4cde8123456789abcdef"
    private val orphan = "fedcba98765443218123fedcba987654"
    private val source get() = LegacyDocumentSource(url, account)
    private val target get() = DocumentPartition(url, id, account, id)
    private val namespace get() = DocumentNamespace(url, account, id)
    private val journalKey get() = DocumentNamespace(url, account, "@journal").storageKey()
    private val sourceRoot get() = File(temporary.root, "documents.v1")
    private val targetRoot get() = File(temporary.root, "documents.v2")
    private fun stores(withMirror: Boolean = false): Pair<AtomicDocumentStorage, AtomicDocumentStorage> {
        val legacy = AtomicDocumentStorage(sourceRoot)
        legacy.writeDrafts(journalKey, DocumentStateCodec.journal(Journal(spaces = mapOf(id to SpaceJournal()), lastSpace = mapOf("game" to id))))
        if (withMirror) legacy.writeCache(namespace.storageKey(), mirror("source"))
        return legacy to AtomicDocumentStorage(targetRoot)
    }
    private fun mirror(text: String) = DocumentStateCodec.mirror(Mirror(true, 9,
        mapOf("note.md" to DocumentVersion(9, documentHash(text), false, text))))
    private fun file(root: File, key: String, kind: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
        for (c in key) { digest.update((c.code ushr 8).toByte()); digest.update(c.code.toByte()) }
        return File(File(root, digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }), kind)
    }
    private fun failure(reason: IdentityMigrationFailure, block: () -> Unit) {
        try { block(); fail("expected migration failure") }
        catch (e: IdentityMigrationException) { assertEquals(reason, e.reason); assertNull(e.cause) }
    }
    private fun storageFailure(reason: DocumentStorageException.Reason, block: () -> Unit) {
        try { block(); fail("expected storage failure") }
        catch (e: DocumentStorageException) { assertEquals(reason, e.reason); assertNull(e.cause) }
    }
    private fun noActivation() = assertNull(AtomicDocumentStorage(targetRoot).readDraftsForMigration(IdentityMigration.ACTIVATION_KEY))
    private fun bytesUnder(root: File): Map<String, List<Byte>> = if (!root.exists()) emptyMap() else
        root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).invariantSeparatorsPath to it.readBytes().toList() }

    @Test fun sourceCorruptPhysicalCacheNeverLooksLikeMissingToMigrationButRuntimeStillRefetches() {
        val (legacy, dest) = stores(withMirror = true)
        val cache = file(sourceRoot, namespace.storageKey(), "cache.bin")
        val envelope = cache.readBytes()
        val damaged = listOf(byteArrayOf(), envelope.copyOf(4), envelope.copyOf(envelope.size - 1),
            envelope + 0, envelope.clone().apply { this[0] = 0 },
            envelope.clone().apply { this[12] = (this[12].toInt() xor 1).toByte() })
        for (bytes in damaged) {
            cache.writeBytes(bytes); val before = bytesUnder(sourceRoot)
            assertNull(legacy.readCache(namespace.storageKey())) // unchanged normal-runtime policy
            storageFailure(DocumentStorageException.Reason.CORRUPT) { legacy.readCacheForMigration(namespace.storageKey()) }
            failure(IdentityMigrationFailure.CORRUPT) { IdentityMigration(legacy, dest).resume(source) }
            assertEquals(before, bytesUnder(sourceRoot)); assertFalse(targetRoot.exists()); noActivation()
        }
    }

    @Test fun unexpectedTargetCacheWithoutSourceMirrorFailsBeforePrepareForValidAndCorruptEnvelope() {
        val (legacy, dest) = stores()
        dest.writeCache(target.namespace.storageKey(), mirror("FOREIGN"))
        val cache = file(targetRoot, target.namespace.storageKey(), "cache.bin")
        val envelope = cache.readBytes()
        for ((bytes, reason) in listOf(envelope to IdentityMigrationFailure.DESTINATION_OCCUPIED,
            envelope.copyOf(7) to IdentityMigrationFailure.CORRUPT)) {
            cache.writeBytes(bytes); val before = bytesUnder(targetRoot)
            failure(reason) { IdentityMigration(legacy, dest).resume(source) }
            assertEquals(before, bytesUnder(targetRoot)); noActivation()
        }
    }

    @Test fun newlyOccupiedAbsentTargetMirrorAfterVerificationBlocksAtomicActivation() {
        val (legacy, dest) = stores(); val migration = IdentityMigration(legacy, dest); val status = migration.resume(source)
        dest.writeCache(target.namespace.storageKey(), mirror("FOREIGN"))
        val cache = file(targetRoot, target.namespace.storageKey(), "cache.bin")
        val envelope = cache.readBytes()
        failure(IdentityMigrationFailure.DESTINATION_OCCUPIED) { migration.activate(source, status.sourceFingerprint, target) }
        noActivation(); assertArrayEquals(envelope, cache.readBytes())
        cache.writeBytes(envelope.copyOf(5))
        failure(IdentityMigrationFailure.CORRUPT) { migration.activate(source, status.sourceFingerprint, target) }
        noActivation(); assertArrayEquals(envelope.copyOf(5), cache.readBytes())
    }

    @Test fun corruptCopiedTargetPhysicalEnvelopeBlocksReadbackAndPublication() {
        val (legacy, dest) = stores(withMirror = true); val migration = IdentityMigration(legacy, dest); val status = migration.resume(source)
        val cache = file(targetRoot, target.namespace.storageKey(), "cache.bin")
        val corrupt = cache.readBytes().apply { this[lastIndex] = (last().toInt() xor 1).toByte() }
        cache.writeBytes(corrupt)
        failure(IdentityMigrationFailure.CORRUPT) { migration.resume(source) }
        failure(IdentityMigrationFailure.CORRUPT) { migration.activate(source, status.sourceFingerprint, target) }
        noActivation(); assertArrayEquals(corrupt, cache.readBytes())
    }

    @Test fun rawOnlyOrphanIsDiscoveredValidatedAndArchivedAutomaticallyWithoutAnyHints() {
        val (legacy, dest) = stores()
        val n = DocumentNamespace(url, account, orphan)
        RoomRecoveryStore(legacy).persist(n, "orphan.md", EditorContext(10, 11, "old-sid"), null, listOf("", "AAE=", "//8="))
        val rawFile = file(sourceRoot, RoomRecoveryStore(legacy).key(n), "drafts.bin")
        val rawEnvelope = rawFile.readBytes(); val before = bytesUnder(sourceRoot)
        assertEquals(setOf(n), legacy.migrationRoomNamespaces())
        val migration = IdentityMigration(legacy, dest); val status = migration.resume(source)
        val candidate = status.candidates.single { it.namespace == n }
        assertTrue(candidate.archiveOnly); assertEquals(1, candidate.counts["rooms"])
        assertArrayEquals(RoomRecoveryStore(legacy).archive(n), migration.archivedRoom(source, orphan))
        assertEquals(before, bytesUnder(sourceRoot)); assertArrayEquals(rawEnvelope, rawFile.readBytes())
        migration.activate(source, status.sourceFingerprint, target)
        assertArrayEquals(RoomRecoveryStore(legacy).archive(n), migration.archivedRoom(source, orphan))
    }

    @Test fun rawOnlyOrphanInOtherwiseEmptyInstallationIsNotLost() {
        val legacy = AtomicDocumentStorage(sourceRoot); val dest = AtomicDocumentStorage(targetRoot)
        val n = DocumentNamespace(url, account, orphan)
        RoomRecoveryStore(legacy).persist(n, "orphan.md", EditorContext(1, 2, "sid"), null, listOf("/w=="))
        val migration = IdentityMigration(legacy, dest); val status = migration.resume(source)
        assertEquals(listOf(n), status.candidates.map { it.namespace }); assertTrue(status.candidates.single().archiveOnly)
        assertArrayEquals(RoomRecoveryStore(legacy).archive(n), migration.archivedRoom(source, orphan)); noActivation()
    }

    @Test fun newlyDiscoveredOrCorruptOrphanCannotBeOmittedAtActivation() {
        val (legacy, dest) = stores(); val migration = IdentityMigration(legacy, dest); val status = migration.resume(source)
        val n = DocumentNamespace(url, account, orphan)
        RoomRecoveryStore(legacy).persist(n, "orphan.md", EditorContext(1, 2, "sid"), null, listOf("/w=="))
        failure(IdentityMigrationFailure.SOURCE_CHANGED) { migration.activate(source, status.sourceFingerprint, target) }
        noActivation()
        val rawFile = file(sourceRoot, RoomRecoveryStore(legacy).key(n), "drafts.bin")
        rawFile.writeBytes(rawFile.readBytes().copyOf(6))
        failure(IdentityMigrationFailure.CORRUPT) { migration.activate(source, status.sourceFingerprint, target) }
        noActivation()
    }

    @Test fun absentLiveV2RoomSlotCannotAdoptForeignRecoveryBeforePrepareOrActivation() {
        val (legacy, dest) = stores()
        RoomRecoveryStore(dest).persist(target.namespace, "foreign.md", EditorContext(1, 2, "sid"), null, listOf("AA=="))
        failure(IdentityMigrationFailure.DESTINATION_OCCUPIED) { IdentityMigration(legacy, dest).resume(source) }
        noActivation()
        val secondRoot = File(temporary.root, "second-destination")
        val second = AtomicDocumentStorage(secondRoot)
        val migration = IdentityMigration(legacy, second); val status = migration.resume(source)
        RoomRecoveryStore(second).persist(target.namespace, "foreign.md", EditorContext(1, 2, "sid"), null, listOf("AA=="))
        failure(IdentityMigrationFailure.DESTINATION_OCCUPIED) { migration.activate(source, status.sourceFingerprint, target) }
        assertNull(second.readDraftsForMigration(IdentityMigration.ACTIVATION_KEY))
    }

    @Test fun copiedRoomPayloadInWrongHashedDirectoryIsNotAnInventoryObservation() {
        val (legacy, dest) = stores()
        val n = DocumentNamespace(url, account, orphan)
        RoomRecoveryStore(legacy).persist(n, "orphan.md", EditorContext(1, 2, "sid"), null, listOf("AA=="))
        val original = file(sourceRoot, RoomRecoveryStore(legacy).key(n), "drafts.bin")
        val wrong = file(sourceRoot, "unrelated-opaque-key", "drafts.bin"); check(wrong.parentFile!!.mkdirs())
        wrong.writeBytes(original.readBytes()); val before = bytesUnder(sourceRoot)
        failure(IdentityMigrationFailure.CORRUPT) { IdentityMigration(legacy, dest).resume(source) }
        assertEquals(before, bytesUnder(sourceRoot)); noActivation()
    }

    @Test fun migrationReadsAndDiscoveryDoNotRepairRenameOrDeleteLegacyBackupOrNewFiles() {
        val (legacy, dest) = stores(withMirror = true)
        val n = DocumentNamespace(url, account, orphan)
        RoomRecoveryStore(legacy).persist(n, "orphan.md", EditorContext(1, 2, "sid"), null, listOf("AA=="))
        val raw = file(sourceRoot, RoomRecoveryStore(legacy).key(n), "drafts.bin")
        val original = raw.readBytes(); File(raw.path + ".bak").writeBytes(original); raw.writeBytes(byteArrayOf(1, 2))
        File(raw.path + ".new").writeBytes(byteArrayOf(3, 4))
        val cache = file(sourceRoot, namespace.storageKey(), "cache.bin")
        File(cache.path + ".bak").writeBytes(cache.readBytes()); cache.writeBytes(byteArrayOf(5))
        val before = bytesUnder(sourceRoot)
        val migration = IdentityMigration(legacy, dest); val status = migration.resume(source)
        assertNotNull(migration.archivedRoom(source, orphan)); assertEquals(before, bytesUnder(sourceRoot))
        migration.activate(source, status.sourceFingerprint, target)
        assertEquals(before, bytesUnder(sourceRoot))
    }

    @Test fun unknownOrExcessiveInventoryFailsClosedWithoutDestinationWrites() {
        val (legacy, dest) = stores()
        val unknown = File(sourceRoot, "unknown-entry"); check(unknown.mkdir())
        failure(IdentityMigrationFailure.CORRUPT) { IdentityMigration(legacy, dest).resume(source) }; assertFalse(targetRoot.exists())
        check(unknown.delete())
        for (i in 0..AtomicDocumentStorage.MAX_INVENTORY_DIRECTORIES) check(File(sourceRoot, "%064x".format(i)).mkdir())
        failure(IdentityMigrationFailure.STORAGE) { IdentityMigration(legacy, dest).resume(source) }; assertFalse(targetRoot.exists())
    }

    @Implements(Os::class)
    class InventoryOs {
        companion object {
            @Implementation @JvmStatic fun stat(path: String): StructStat = DocumentStorageTest.ErrnoAwareOs.stat(path)
            @Implementation @JvmStatic fun lstat(path: String): StructStat {
                try {
                    val a = Files.readAttributes(File(path).toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    val mode = when { a.isSymbolicLink -> OsConstants.S_IFLNK; a.isRegularFile -> OsConstants.S_IFREG; a.isDirectory -> OsConstants.S_IFDIR; else -> 0 }
                    return StructStat(0, 0, mode, 1, 0, 0, 0, a.size(), 0, 0, 0, 0, 0)
                } catch (_: NoSuchFileException) { throw ErrnoException("lstat", OsConstants.ENOENT) }
                catch (_: NotDirectoryException) { throw ErrnoException("lstat", OsConstants.ENOTDIR) }
                catch (_: AccessDeniedException) { throw ErrnoException("lstat", OsConstants.EACCES) }
                catch (_: IOException) { throw ErrnoException("lstat", OsConstants.EIO) }
            }
        }
    }
}
