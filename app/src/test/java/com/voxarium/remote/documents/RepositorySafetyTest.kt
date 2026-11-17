package com.voxarium.remote.documents

import com.voxarium.remote.data.documents.*
import com.voxarium.remote.data.transport.*
import com.voxarium.remote.data.security.SavedProfile
import org.junit.Assert.*
import org.junit.Test

/** VS-74 safety regressions. Pure JVM repository tests; no Android storage or network. */
class RepositorySafetyTest {
    private fun partition(project: String = "project-a", account: String = "alice", epoch: String = "a".repeat(32),
                          url: String = endpoint.serverUrl) = DocumentPartition(url,project,account,epoch)
    private fun v2Hello(p: DocumentPartition, seq: Long = 0, label: String = "Shared label") =
        ServerFrame.Hello(2,RemoteUser(p.accountId,"Alice","#123456"),RemoteSpace(p.projectId,label,p.storageEpoch),seq)

    @Test fun exactV2PartitionSurvivesRenameAndReconnectWithoutChangingKeys() {
        val storage=MemoryStorage();val repository=DocumentsRepository(storage);val p=partition()
        val owner=repository.selectPartition(p)
        assertEquals(ClientFrame.Sync(0),deliver(repository,owner,v2Hello(p,3)).single().frame)
        deliver(repository,owner,change("a.md","base",3));deliver(repository,owner,ServerFrame.Synced(3))
        val oldScope=repository.commandScope(owner)
        repository.saveDraft(oldScope,"a.md","local")
        val keys=storage.journals.keys.toSet()
        repository.onEvent(owner,ConnectionEvent.State(generation,ConnectionPhase.STOPPED))
        val effects=repository.onEvent(owner,ConnectionEvent.Frame(generation+1,v2Hello(p,3,"Renamed")))
        assertEquals(ClientFrame.Sync(3),effects.single().frame)
        assertEquals(keys,storage.journals.keys)
        assertEquals(p.namespace,repository.view().namespace)
        assertFalse(repository.view().synced)
        assertEquals("local",repository.view().drafts.getValue("a.md").content)
    }
    @Test fun v2HelloRejectsWrongAccountIdEpochAndLegacyWithoutWriting() {
        val target=partition()
        val wrong=listOf(v2Hello(partition(account="bob")),v2Hello(partition(project="project-b")),
            v2Hello(partition(epoch="b".repeat(32))),hello(),v2Hello(target).copy(seq=-1))
        for(frame in wrong) {
            val storage=MemoryStorage();val repository=DocumentsRepository(storage);val owner=repository.selectPartition(target)
            fails(DocumentFailure.INVALID_FRAME) { deliver(repository,owner,frame) }
            assertEquals(0,storage.cacheWrites);assertEquals(0,storage.draftWrites)
            assertFalse(repository.view().synced)
        }
    }
    @Test fun selectionNeverSharesDraftsAcrossIdAccountEpochOrProxyPrefixAndFencesLateCallbacks() {
        val storage=MemoryStorage();val repository=DocumentsRepository(storage);val original=partition()
        val owner=repository.selectPartition(original)
        deliver(repository,owner,v2Hello(original));deliver(repository,owner,ServerFrame.Synced(0))
        val oldScope=repository.commandScope(owner)
        repository.queueCreate(oldScope,"a.md","immutable pending")
        val oldEffect=repository.nextWrite(oldScope,"a.md")!!
        repository.saveDraft(oldScope,"a.md","newer draft")
        val saved=storage.journals.getValue(original.journalKey()).clone()
        for(target in listOf(partition(project="project-b"),partition(account="bob"),partition(epoch="b".repeat(32)),
            partition(url="https://remote.example/other-prefix"))) {
            val fresh=repository.selectPartition(target)
            assertTrue(repository.onEvent(owner,ConnectionEvent.Frame(999,v2Hello(original))).isEmpty())
            assertFalse(repository.isCurrent(oldEffect))
            fails(DocumentFailure.STALE_ROOM) { repository.saveDraft(oldScope,"a.md","misdirected") }
            deliver(repository,fresh,v2Hello(target));deliver(repository,fresh,ServerFrame.Synced(0))
            assertTrue(repository.view().drafts.isEmpty());assertTrue(repository.view().pending.isEmpty())
            assertArrayEquals(saved,storage.journals.getValue(original.journalKey()))
        }
        val restored=repository.selectPartition(original)
        assertEquals("newer draft",repository.view().drafts.getValue("a.md").content)
        assertEquals("immutable pending",repository.view().pending.getValue("a.md").content)
        assertFalse(repository.view().synced)
        assertEquals(original.namespace,repository.commandScope(restored).namespace)
    }
    @Test fun sequenceRollbackPreservesBothVariantsAndCannotConfirmPendingFromOldCachedHash() {
        val storage=MemoryStorage();var repository=DocumentsRepository(storage);val p=partition()
        var owner=repository.selectPartition(p)
        deliver(repository,owner,v2Hello(p,5));deliver(repository,owner,ServerFrame.Synced(5))
        val scope=repository.commandScope(owner)
        repository.queueCreate(scope,"a.md","pending")
        val pending=repository.nextWrite(scope,"a.md")!!.frame as ClientFrame.Put
        repository.saveDraft(scope,"a.md","newer")
        deliver(repository,owner,change("a.md","pending",5))
        repository=DocumentsRepository(storage);owner=repository.selectPartition(p)
        assertEquals(ClientFrame.Sync(0),deliver(repository,owner,v2Hello(p,0)).single().frame)
        assertTrue(repository.view().documents.isEmpty());assertFalse(repository.view().synced)
        assertEquals(setOf("pending","newer"),repository.view().recoveries.filter { it.reason == RecoveryReason.SEQUENCE_ROLLBACK }.map { it.content }.toSet())
        deliver(repository,owner,ServerFrame.Ack(FrameId.Integer(pending.id),"a.md",5,documentHash("pending"),false))
        assertEquals(1,repository.view().pending.size)
        assertNull(repository.nextWrite(repository.commandScope(owner),"a.md"))
        repository=DocumentsRepository(storage);owner=repository.selectPartition(p)
        assertEquals(ClientFrame.Sync(0),deliver(repository,owner,v2Hello(p)).single().frame)
        deliver(repository,owner,ServerFrame.Synced(0))
        assertTrue(repository.view().pending.isEmpty());assertTrue(repository.view().documents.isEmpty())
        assertTrue(repository.view().recoveries.any { it.content == "pending" })
        assertTrue(repository.view().recoveries.any { it.content == "newer" })
    }
    @Test fun v2SyncCannotFinishBelowAdvertisedSequenceOrResolvePendingEarly() {
        val storage=MemoryStorage();val repository=DocumentsRepository(storage);val p=partition();val owner=repository.selectPartition(p)
        deliver(repository,owner,v2Hello(p,5))
        fails(DocumentFailure.INVALID_FRAME) { deliver(repository,owner,ServerFrame.Synced(4)) }
        assertFalse(repository.view().synced)
    }
    @Test fun stagedOrCorruptMigrationCannotBecomeWritableThroughFreshSelection() {
        val storage=MemoryStorage();val p=partition()
        val metadata="""{"v":2,"canonicalizer":1,"server":"${p.canonicalServerUrl}","project":"${p.projectId}","account":"${p.accountId}","epoch":"${p.storageEpoch}","profiles":[]}""".toByteArray()
        storage.journals["partition-metadata.v2:"+p.namespace.storageKey()]=metadata
        val repository=DocumentsRepository(storage)
        fails(DocumentFailure.NOT_SYNCED) { repository.selectPartition(p) }
        assertEquals(0,storage.draftWrites);assertEquals(0,storage.cacheWrites)
        storage.journals["partition-metadata.v2:"+p.namespace.storageKey()]="malformed".toByteArray()
        fails(DocumentFailure.CORRUPT_JOURNAL) { repository.selectPartition(p) }
        assertEquals(0,storage.draftWrites);assertEquals(0,storage.cacheWrites)
    }
    @Test fun identicalOwnerAndConnectionCountersCannotTransferEffectsBetweenPartitions() {
        val left=DocumentsRepository(MemoryStorage());val right=DocumentsRepository(MemoryStorage())
        val a=partition();val b=partition(epoch="b".repeat(32))
        val leftOwner=left.selectPartition(a);val rightOwner=right.selectPartition(b)
        val leftSync=deliver(left,leftOwner,v2Hello(a)).single()
        val rightSync=deliver(right,rightOwner,v2Hello(b)).single()
        assertTrue(left.isCurrent(leftSync));assertTrue(right.isCurrent(rightSync))
        assertFalse(right.isCurrent(leftSync));assertFalse(left.isCurrent(rightSync))
        deliver(left,leftOwner,ServerFrame.Synced(0));deliver(right,rightOwner,ServerFrame.Synced(0))
        val ls=left.commandScope(leftOwner);val rs=right.commandScope(rightOwner)
        left.queueCreate(ls,"a.md","same");right.queueCreate(rs,"a.md","same")
        val leftPut=left.nextWrite(ls,"a.md")!!;val rightPut=right.nextWrite(rs,"a.md")!!
        assertEquals(leftPut.frame,rightPut.frame)
        assertTrue(left.isCurrent(leftPut));assertTrue(right.isCurrent(rightPut))
        assertFalse(right.isCurrent(leftPut));assertFalse(left.isCurrent(rightPut))
    }
    @Test fun rollbackStorageFailureNeverReturnsSyncOrDropsPending() {
        for(failJournal in listOf(true,false)) {
            val storage=MemoryStorage();val p=partition();var repository=DocumentsRepository(storage);var owner=repository.selectPartition(p)
            deliver(repository,owner,v2Hello(p,9));deliver(repository,owner,ServerFrame.Synced(9))
            val scope=repository.commandScope(owner);repository.queueCreate(scope,"a.md","pending");repository.nextWrite(scope,"a.md")
            repository=DocumentsRepository(storage);owner=repository.selectPartition(p)
            if(failJournal) storage.failDraftAt=storage.draftWrites+1 else storage.failCacheAt=storage.cacheWrites+1
            injectedFailure { deliver(repository,owner,v2Hello(p,0)) }
            assertFalse(repository.view().synced)
            assertEquals("pending",repository.view().pending.getValue("a.md").content)
            assertFalse(repository.isCurrent(DocumentSend(generation,ClientFrame.Sync(0),owner)))
        }
    }
    private val endpoint = RemoteEndpoint.create("https://remote.example", "game")
    private fun immutableBytes(map: Map<String,ByteArray>) = map.mapValues { it.value.clone() }
    private fun assertBytes(expected: Map<String,ByteArray>, actual: Map<String,ByteArray>) {
        assertEquals(expected.keys,actual.keys)
        expected.forEach { (key,bytes) -> assertArrayEquals(bytes,actual.getValue(key)) }
    }
    private fun assertRejectedRouteIsInert(repository: DocumentsRepository, storage: MemoryStorage, p: DocumentPartition, owner: Long = 1) {
        val journals=immutableBytes(storage.journals);val caches=immutableBytes(storage.caches)
        val writes=storage.order.toList()
        for(gen in listOf(1L,7L,99L)) {
            assertTrue(repository.onEvent(owner,ConnectionEvent.State(gen,ConnectionPhase.READY)).isEmpty())
            for(frame in listOf(v2Hello(p,8),change("a.md","must not be adopted",8),ServerFrame.Synced(8)))
                assertTrue(repository.onEvent(owner,ConnectionEvent.Frame(gen,frame)).isEmpty())
        }
        assertFalse(repository.view().synced);assertNull(repository.view().namespace)
        assertTrue(repository.view().drafts.isEmpty());assertTrue(repository.view().pending.isEmpty())
        fails(DocumentFailure.CORRUPT_JOURNAL) { repository.commandScope(owner) }
        assertBytes(journals,storage.journals);assertBytes(caches,storage.caches);assertEquals(writes,storage.order)
    }

    /** Review F1: exercise the real PREPARED/copy crash boundaries, not invented metadata. */
    @Test fun activationMustGatePartiallyCopiedJournalBeforeMetadataAndPermitByteExactResume() {
        for(crashAt in listOf("journal","metadata","mirror")) {
            val sourceStorage=MemoryStorage();val destination=MemoryStorage();val id="a".repeat(32)
            val p=partition(project=id,epoch=id)
            val profile=SavedProfile.create("Legacy",p.canonicalServerUrl,"legacy","credential","profile")
            val old=DocumentsRepository(sourceStorage);val owner=old.selectProfile(profile)
            deliver(old,owner,ServerFrame.Hello(1,RemoteUser("alice","Alice","#123456"),RemoteSpace(id,"legacy"),0))
            deliver(old,owner,ServerFrame.Synced(0));val scope=old.commandScope(owner)
            old.queueCreate(scope,"pending.md","source pending");old.nextWrite(scope,"pending.md")
            old.saveDraft(scope,"pending.md","newer source draft")
            val originalJournals=immutableBytes(sourceStorage.journals);val originalCaches=immutableBytes(sourceStorage.caches)
            val source=LegacyDocumentSource(p.canonicalServerUrl,p.accountId,listOf(LegacyDocumentProfile("profile","credential","legacy")))
            val metadataKey="partition-metadata.v2:"+p.namespace.storageKey()
            when(crashAt) {
                "journal" -> destination.failDraftNamespace=p.journalKey()
                "metadata" -> destination.failDraftNamespace=metadataKey
                else -> destination.failCacheNamespace=p.namespace.storageKey()
            }
            // Mirror commit needs a real source mirror, supplied by the completed v1 sync.
            val error=assertThrows(IdentityMigrationException::class.java) { IdentityMigration(sourceStorage,destination).resume(source) }
            assertEquals(IdentityMigrationFailure.STORAGE,error.reason)
            if(crashAt == "metadata") {
                assertNotNull(destination.journals[p.journalKey()]);assertNull(destination.journals[metadataKey])
            }
            val stagedJournals=immutableBytes(destination.journals);val stagedCaches=immutableBytes(destination.caches)
            repeat(2) {
                val reopened=DocumentsRepository(destination)
                fails(DocumentFailure.NOT_SYNCED) { reopened.selectPartition(p) }
                assertRejectedRouteIsInert(reopened,destination,p)
                assertBytes(stagedJournals,destination.journals);assertBytes(stagedCaches,destination.caches)
            }
            destination.failDraftNamespace=null;destination.failCacheNamespace=null
            val migration=IdentityMigration(sourceStorage,destination)
            val prepared=migration.resume(source)
            assertEquals(IdentityMigrationPhase.VERIFIED,prepared.phase)
            stagedJournals[p.journalKey()]?.let { assertArrayEquals(it,destination.journals[p.journalKey()]) }
            val rejected=DocumentsRepository(destination)
            fails(DocumentFailure.NOT_SYNCED) { rejected.selectPartition(p) }
            assertRejectedRouteIsInert(rejected,destination,p)
            migration.activate(source,prepared.sourceFingerprint,p)
            val activated=DocumentsRepository(destination);val activeOwner=activated.selectPartition(p)
            assertEquals("source pending",activated.view().pending.getValue("pending.md").content)
            assertEquals("newer source draft",activated.view().drafts.getValue("pending.md").content)
            assertEquals(ClientFrame.Sync(0),deliver(activated,activeOwner,v2Hello(p)).single().frame)
            assertBytes(originalJournals,sourceStorage.journals);assertBytes(originalCaches,sourceStorage.caches)
            assertEquals(IdentityMigrationPhase.ACTIVATED,migration.resume(source).phase)
        }
    }
    /** Review F3: caught load failure cannot be revived by any queued or fresh callback. */
    @Test fun rejectedSelectionCannotProduceSyncOrMirrorWritesAfterFailureIsCaught() {
        val p=partition()
        val metadataKey="partition-metadata.v2:"+p.namespace.storageKey()
        val metadata="""{"v":2,"canonicalizer":1,"server":"${p.canonicalServerUrl}","project":"${p.projectId}","account":"${p.accountId}","epoch":"${p.storageEpoch}","profiles":[]}""".toByteArray()
        for(mode in listOf("staged","corrupt metadata","journal IO","corrupt journal","migration IO","corrupt migration")) {
            val storage=MemoryStorage();val repo=DocumentsRepository(storage)
            val recordKey=listOf("identity-migration.v2",p.canonicalServerUrl,p.accountId).joinToString("") { "${it.length}:$it" }
            val reason=when(mode) {
                "staged" -> { storage.journals[metadataKey]=metadata;DocumentFailure.NOT_SYNCED }
                "corrupt metadata" -> { storage.journals[metadataKey]="bad".toByteArray();DocumentFailure.CORRUPT_JOURNAL }
                "corrupt journal" -> { storage.journals[p.journalKey()]="bad".toByteArray();DocumentFailure.CORRUPT_JOURNAL }
                "corrupt migration" -> { storage.journals[recordKey]="bad".toByteArray();DocumentFailure.CORRUPT_JOURNAL }
                else -> { storage.failDraftRead=if(mode == "journal IO") p.journalKey() else recordKey;DocumentFailure.STORAGE }
            }
            fails(reason) { repo.selectPartition(p) }
            assertRejectedRouteIsInert(repo,storage,p)
            // Failure is recoverable only through a new explicit, successfully loaded owner.
            storage.failDraftRead=null;storage.journals.clear()
            val fresh=repo.selectPartition(p)
            assertTrue(repo.onEvent(1,ConnectionEvent.Frame(100,v2Hello(p))).isEmpty())
            assertEquals(ClientFrame.Sync(0),deliver(repo,fresh,v2Hello(p)).single().frame)
            deliver(repo,fresh,ServerFrame.Synced(0));assertTrue(repo.view().synced)
        }
    }

    private fun confirmedAheadOfCursor(storage: MemoryStorage, p: DocumentPartition, fromAck: Boolean): Pair<DocumentsRepository,Long> {
        val repo=DocumentsRepository(storage);val owner=repo.selectPartition(p)
        deliver(repo,owner,v2Hello(p,5));deliver(repo,owner,change("a.md","base",5));deliver(repo,owner,ServerFrame.Synced(5))
        if(fromAck) {
            val scope=repo.commandScope(owner);repo.queueCreate(scope,"confirmed.md","confirmed at ten")
            val put=repo.nextWrite(scope,"confirmed.md")!!.frame as ClientFrame.Put
            deliver(repo,owner,ServerFrame.Ack(FrameId.Integer(put.id),"confirmed.md",10,documentHash(put.content),false))
        } else deliver(repo,owner,change("confirmed.md","confirmed at ten",10))
        assertEquals(5L,repo.view().cursor);assertEquals(10L,repo.view().documents.getValue("confirmed.md").rev)
        return repo to owner
    }
    /** Review F2: both ACK and unsolicited Change can be durable ahead of the sync cursor. */
    @Test fun rollbackAlsoDetectsCachedAckOrChangeBeyondCursorAndCompletesFullReconciliation() {
        for(fromAck in listOf(true,false)) {
            val storage=MemoryStorage();val p=partition();confirmedAheadOfCursor(storage,p,fromAck)
            repeat(2) { // restart before receiving the full snapshot completion
                val repo=DocumentsRepository(storage);val owner=repo.selectPartition(p)
                assertEquals(ClientFrame.Sync(0),deliver(repo,owner,v2Hello(p,8)).single().frame)
                assertTrue(repo.view().documents.isEmpty())
                deliver(repo,owner,change("a.md","restored revision eight",8))
                assertFalse(repo.view().synced)
            }
            val repo=DocumentsRepository(storage);val owner=repo.selectPartition(p)
            assertEquals(ClientFrame.Sync(0),deliver(repo,owner,v2Hello(p,8)).single().frame)
            deliver(repo,owner,change("a.md","restored revision eight",8));deliver(repo,owner,ServerFrame.Synced(8))
            assertTrue(repo.view().synced);assertEquals(8L,repo.view().cursor)
            assertEquals(setOf("a.md"),repo.view().documents.keys)
            assertEquals(1,repo.view().recoveries.count { it.reason == RecoveryReason.SEQUENCE_ROLLBACK && it.content == "confirmed at ten" })
            repeat(2) {
                val reopened=DocumentsRepository(storage);val selected=reopened.selectPartition(p)
                assertEquals(ClientFrame.Sync(8),deliver(reopened,selected,v2Hello(p,8)).single().frame)
                deliver(reopened,selected,ServerFrame.Synced(8));assertTrue(reopened.view().synced)
                assertNull(reopened.view().documents["confirmed.md"])
            }
        }
    }
    @Test fun rollbackBeyondCursorPreservesPendingBeforeResetAndRepeatedResetFailureIsIdempotent() {
        for(fromAck in listOf(true,false)) for(failRecovery in listOf(true,false)) {
            val storage=MemoryStorage();val p=partition();val (old,owner)=confirmedAheadOfCursor(storage,p,fromAck)
            val scope=old.commandScope(owner)
            old.queueCreate(scope,"pending.md","immutable pending");old.nextWrite(scope,"pending.md")
            old.saveDraft(scope,"pending.md","newer pending")
            val initial=immutableBytes(storage.journals)
            repeat(2) {
                val repo=DocumentsRepository(storage);val selected=repo.selectPartition(p);storage.order.clear()
                if(failRecovery) storage.failDraftAt=storage.draftWrites+1 else storage.failCacheAt=storage.cacheWrites+1
                injectedFailure { deliver(repo,selected,v2Hello(p,8)) }
                assertEquals(if(failRecovery) listOf("journal") else listOf("journal","cache"),storage.order)
                if(failRecovery) assertBytes(initial,storage.journals)
                assertEquals("immutable pending",repo.view().pending.getValue("pending.md").content)
                assertEquals("newer pending",repo.view().drafts.getValue("pending.md").content)
                assertFalse(repo.view().synced)
                assertTrue(deliver(repo,selected,v2Hello(p,8)).isEmpty())
            }
            storage.failCacheAt=-1;storage.failDraftAt=-1
            val reopened=DocumentsRepository(storage);val selected=reopened.selectPartition(p)
            assertEquals(ClientFrame.Sync(0),deliver(reopened,selected,v2Hello(p,8)).single().frame)
            val recoveries=reopened.view().recoveries.filter { it.reason == RecoveryReason.SEQUENCE_ROLLBACK }
            assertEquals(setOf("immutable pending","newer pending","confirmed at ten"),recoveries.map { it.content }.toSet())
            assertEquals(3,recoveries.size)
            // Late ACK cannot resolve this pending before a full, valid snapshot finishes.
            val pending=reopened.view().pending.getValue("pending.md")
            deliver(reopened,selected,ServerFrame.Ack(FrameId.Integer(pending.id),pending.path,10,pending.hash,false))
            assertEquals(1,reopened.view().pending.size)
            deliver(reopened,selected,change("a.md","restored",8));deliver(reopened,selected,ServerFrame.Synced(8))
            assertTrue(reopened.view().synced);assertTrue(reopened.view().pending.isEmpty())
        }
    }
    @Test fun rollbackDoesNotWeakenRevisionAboveSyncedValidation() {
        val storage=MemoryStorage();val p=partition();confirmedAheadOfCursor(storage,p,true)
        val repo=DocumentsRepository(storage);val owner=repo.selectPartition(p)
        assertEquals(ClientFrame.Sync(0),deliver(repo,owner,v2Hello(p,8)).single().frame)
        deliver(repo,owner,change("a.md","invalid future change",10))
        fails(DocumentFailure.INVALID_FRAME) { deliver(repo,owner,ServerFrame.Synced(8)) }
        assertFalse(repo.view().synced)
    }
    @Test fun realRemoteV2ContractFixtureDecodesAndReconcilesWithoutTemporaryFileDependencies() {
        // Captured by the independent review from unchanged Remote Hub/Store, seq=8.
        val lines=listOf(
            """{"t":"hello","protocol":2,"user":{"id":"3a5f9d805ce3441a97633310caba7d8d","display_name":"Review user","color":"#123456"},"space":{"id":"review.project","display_name":"Same label","storage_epoch":"038414abcf1d4a39bb3a303ae2a1b6b8"},"seq":8}""",
            """{"t":"presence","peers":[{"user":{"id":"3a5f9d805ce3441a97633310caba7d8d","display_name":"Review user","color":"#123456"},"connections":1,"docs":[]}]}""",
            """{"t":"change","path":"a.md","rev":8,"hash":"8089ed1e7d329e03b7c041f134ecc166e70632ff9812292bd14b395ba40693a2","deleted":false,"content":"revision 8","by":null}""",
            """{"t":"synced","seq":8}""",
        )
        val frames=lines.map(FrameCodec::decodeV2);val h=frames.first() as ServerFrame.Hello
        val me=FrameCodec.decodeMeV2("""{"protocol":2,"user":{"id":"3a5f9d805ce3441a97633310caba7d8d","display_name":"Review user","color":"#123456"}}""")
        assertEquals(me.user,h.user);assertEquals(8L,h.seq)
        val repo=DocumentsRepository(MemoryStorage());val owner=repo.selectPartition(partition(h.space.id,h.user.id,h.space.storageEpoch!!))
        assertEquals(ClientFrame.Sync(0),deliver(repo,owner,h).single().frame)
        frames.drop(1).forEach { deliver(repo,owner,it) }
        assertTrue(repo.view().synced);assertEquals(8L,repo.view().cursor)
        assertEquals("revision 8",repo.view().documents.getValue("a.md").content)
    }
    private val alice = DocumentNamespace(endpoint.serverUrl, "alice", "space-id")
    private val bob = DocumentNamespace(endpoint.serverUrl, "bob", "space-id")
    private val generation = 7L

    private class MemoryStorage : DocumentStorage {
        val caches = mutableMapOf<String, ByteArray>()
        val journals = mutableMapOf<String, ByteArray>()
        var cacheWrites = 0
        var draftWrites = 0
        var failCacheAt = -1
        var failDraftAt = -1
        var failDraftNamespace: String? = null
        var failCacheNamespace: String? = null
        var failDraftRead: String? = null
        val order = mutableListOf<String>()

        override fun readCache(namespace: String) = caches[namespace]?.clone()
        override fun readDrafts(namespace: String): ByteArray? {
            if(namespace == failDraftRead) throw DocumentStorageException(DocumentStorageException.Reason.STORAGE)
            return journals[namespace]?.clone()
        }
        override fun writeCache(namespace: String, bytes: ByteArray) {
            order += "cache"
            cacheWrites++
            if(namespace == failCacheNamespace) throw DocumentStorageException(DocumentStorageException.Reason.STORAGE)
            check(cacheWrites != failCacheAt) { "injected cache failure" }
            caches[namespace] = bytes.clone()
        }
        override fun writeDrafts(namespace: String, bytes: ByteArray) {
            order += "journal"
            draftWrites++
            if(namespace == failDraftNamespace) throw DocumentStorageException(DocumentStorageException.Reason.STORAGE)
            check(draftWrites != failDraftAt) { "injected journal failure" }
            journals[namespace] = bytes.clone()
        }
    }

    private fun journalKey(namespace: DocumentNamespace) =
        DocumentNamespace(namespace.server, namespace.accountId, "@journal").storageKey()

    // Internal constructor deliberately exercises write gates even AFTER a failed journal load.
    private fun scope(repository: DocumentsRepository, owner: Long) =
        DocumentScope(owner, repository.view().namespace ?: alice)

    private fun hello(namespace: DocumentNamespace = alice, seq: Long = 0) = ServerFrame.Hello(
        1, RemoteUser(namespace.accountId, "user", "#ffffff"),
        RemoteSpace(namespace.spaceId, endpoint.space), seq,
    )

    private fun change(path: String, text: String, rev: Long) =
        ServerFrame.Change(path, rev, documentHash(text), false, text, null)

    private fun deliver(repository: DocumentsRepository, owner: Long, frame: ServerFrame) =
        repository.onEvent(owner, ConnectionEvent.Frame(generation, frame))

    private fun online(
        repository: DocumentsRepository,
        namespace: DocumentNamespace = alice,
        path: String? = null,
        text: String = "base",
        seq: Long = if (path == null) 0 else 1,
    ): Long {
        val owner = repository.select(endpoint)
        val effect = deliver(repository, owner, hello(namespace, seq)).single()
        assertEquals(ClientFrame.Sync(0), effect.frame)
        if (path != null) deliver(repository, owner, change(path, text, seq))
        deliver(repository, owner, ServerFrame.Synced(seq))
        return owner
    }

    private fun room(repository: DocumentsRepository, owner: Long, path: String): RoomWriteToken {
        val rev = repository.view().documents.getValue(path).rev
        deliver(repository, owner, ServerFrame.Joined("sid", path, "epoch", true, "AA==", true, false, rev))
        return repository.confirmRoomReady(owner, generation, "sid", "epoch")
    }

    private fun fails(reason: DocumentFailure, operation: () -> Unit) {
        try {
            operation()
            fail("Expected $reason")
        } catch (error: DocumentRepositoryException) {
            assertEquals(reason, error.reason)
        }
    }

    private fun injectedFailure(operation: () -> Unit) {
        try {
            operation()
            fail("Expected an injected storage failure")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().startsWith("injected "))
        }
    }

    @Test fun failedLogicalHelloJournalLoadCannotOverwriteItsBytes() {
        val storage = MemoryStorage()
        val corrupt = "{\"v\":1,\"spaces\":[]}".toByteArray()
        storage.journals[journalKey(alice)] = corrupt.clone()
        val repository = DocumentsRepository(storage)
        val owner = repository.select(endpoint)

        fails(DocumentFailure.CORRUPT_JOURNAL) { deliver(repository, owner, hello()) }
        fails(DocumentFailure.CORRUPT_JOURNAL) { repository.saveDraft(scope(repository, owner), "note.md", "must not replace") }
        fails(DocumentFailure.CORRUPT_JOURNAL) { repository.queueCreate(scope(repository, owner), "copy.md", "must not replace") }
        fails(DocumentFailure.CORRUPT_JOURNAL) { repository.makeConflictCopy(scope(repository, owner), "note.md") }
        fails(DocumentFailure.CORRUPT_JOURNAL) { repository.nextWrite(scope(repository, owner), "note.md") }
        assertArrayEquals(corrupt, storage.journals.getValue(journalKey(alice)))
        assertEquals(0, storage.draftWrites)
        assertFalse(repository.view().synced)
    }

    @Test fun failedLogicalOfflineJournalLoadAlsoForbidsSaving() {
        val storage = MemoryStorage()
        val corrupt = "{\"v\":999}".toByteArray()
        storage.journals[journalKey(alice)] = corrupt.clone()
        val repository = DocumentsRepository(storage)

        fails(DocumentFailure.CORRUPT_JOURNAL) { repository.select(endpoint, alice) }
        // Even knowing the internally advanced owner must not bypass the failed-load gate.
        fails(DocumentFailure.CORRUPT_JOURNAL) { repository.saveDraft(scope(repository, 1), "note.md", "replacement") }
        assertArrayEquals(corrupt, storage.journals.getValue(journalKey(alice)))
        assertEquals(0, storage.draftWrites)
    }

    @Test fun staleCommandsAndEffectsCannotCrossAProfileSwitchWithTheSameConnectionGeneration() {
        val storage = MemoryStorage()
        val repository = DocumentsRepository(storage)
        val ownerA = online(repository)
        repository.queueCreate(scope(repository, ownerA), "a.md", "alice only")
        val effect = repository.nextWrite(scope(repository, ownerA), "a.md")!!
        assertTrue(repository.isCurrent(effect))

        val ownerB = online(repository, bob)
        assertFalse(repository.isCurrent(effect))
        val writes = storage.draftWrites
        fails(DocumentFailure.STALE_ROOM) { repository.saveDraft(scope(repository, ownerA), "b.md", "old autosave") }
        fails(DocumentFailure.STALE_ROOM) { repository.queueCreate(scope(repository, ownerA), "b.md", "old create") }
        fails(DocumentFailure.STALE_ROOM) { repository.makeConflictCopy(scope(repository, ownerA), "a.md") }
        fails(DocumentFailure.STALE_ROOM) { repository.nextWrite(scope(repository, ownerA), "a.md") }
        fails(DocumentFailure.STALE_ROOM) { repository.retireRoom(scope(repository, ownerA), "sid") }
        assertTrue(deliver(repository, ownerA, change("leak.md", "alice only", 9)).isEmpty())
        assertEquals(writes, storage.draftWrites)
        assertEquals(bob, repository.view().namespace)
        assertTrue(repository.view().drafts.isEmpty())
        assertTrue(repository.view().pending.isEmpty())
        repository.saveDraft(scope(repository, ownerB), "b.md", "bob only")

        val restarted = DocumentsRepository(storage)
        restarted.select(endpoint, alice)
        assertEquals("alice only", restarted.view().drafts.getValue("a.md").content)
        assertFalse(restarted.view().drafts.containsKey("b.md"))
    }

    @Test fun localCheckpointInvalidatesPreviouslyIssuedRoomPermission() {
        val repository = DocumentsRepository(MemoryStorage())
        val owner = online(repository, path = "note.md")
        val token = room(repository, owner, "note.md")
        repository.queueRoomSnapshot(token, "room checkpoint")
        val localGeneration = repository.saveDraft(scope(repository, owner), "note.md", "local-only checkpoint")

        fails(DocumentFailure.STALE_ROOM) { repository.queueRoomSnapshot(token, "stale room snapshot") }
        assertNull(repository.nextWrite(scope(repository, owner), "note.md"))
        val local = repository.view().drafts.getValue("note.md")
        assertEquals(localGeneration, local.generation)
        assertEquals("local-only checkpoint", local.content)
        assertEquals(DraftKind.LOCAL, local.kind)
    }

    @Test fun matchingChangeCannotRemoveDraftBeforeCacheCommit() {
        val storage = MemoryStorage()
        val repository = DocumentsRepository(storage)
        val owner = online(repository, path = "note.md")
        repository.saveDraft(scope(repository, owner), "note.md", "checkpoint")
        storage.failCacheAt = storage.cacheWrites + 1

        injectedFailure { deliver(repository, owner, change("note.md", "checkpoint", 2)) }
        assertFalse(repository.view().synced)
        val restarted = DocumentsRepository(storage)
        restarted.select(endpoint, alice)
        assertEquals("checkpoint", restarted.view().drafts.getValue("note.md").content)
        assertEquals("base", restarted.view().documents.getValue("note.md").content)
    }

    @Test fun matchingChangeJournalFailureLeavesBothNewCacheAndDraftOnRestart() {
        val storage = MemoryStorage()
        val repository = DocumentsRepository(storage)
        val owner = online(repository, path = "note.md")
        repository.saveDraft(scope(repository, owner), "note.md", "checkpoint")
        storage.failDraftAt = storage.draftWrites + 1

        injectedFailure { deliver(repository, owner, change("note.md", "checkpoint", 2)) }
        val restarted = DocumentsRepository(storage)
        val restartedOwner = restarted.select(endpoint, alice)
        assertEquals("checkpoint", restarted.view().documents.getValue("note.md").content)
        assertEquals("checkpoint", restarted.view().drafts.getValue("note.md").content)
        val sync = deliver(restarted, restartedOwner, hello(seq = 2)).single()
        assertEquals(ClientFrame.Sync(1), sync.frame)
        assertTrue(deliver(restarted, restartedOwner, ServerFrame.Synced(2)).isEmpty())
        assertTrue(restarted.view().drafts.isEmpty())
    }

    @Test fun ackCacheCommitBeforeJournalFailureResolvesUnknownWithoutReplay() {
        val storage = MemoryStorage()
        val repository = DocumentsRepository(storage)
        val owner = online(repository, path = "note.md")
        val token = room(repository, owner, "note.md")
        repository.queueRoomSnapshot(token, "snapshot")
        val put = repository.nextWrite(scope(repository, owner), "note.md")!!.frame as ClientFrame.Put
        storage.failDraftAt = storage.draftWrites + 1

        injectedFailure {
            deliver(repository, owner, ServerFrame.Ack(FrameId.Integer(put.id), put.path, 2, documentHash(put.content), false))
        }
        val restarted = DocumentsRepository(storage)
        val restartedOwner = restarted.select(endpoint, alice)
        assertEquals("snapshot", restarted.view().documents.getValue("note.md").content)
        assertEquals("snapshot", restarted.view().pending.getValue("note.md").content)
        assertEquals(ClientFrame.Sync(1), deliver(restarted, restartedOwner, hello(seq = 2)).single().frame)
        assertTrue(deliver(restarted, restartedOwner, ServerFrame.Synced(2)).isEmpty())
        assertTrue(restarted.view().pending.isEmpty())
        assertTrue(restarted.view().drafts.isEmpty())
        assertNull(restarted.nextWrite(scope(restarted, restartedOwner), "note.md"))
    }

    @Test fun nackCrashAfterCopyCommitPreservesOldInflightAndNewGenerationOnRestart() {
        val storage = MemoryStorage()
        val repository = DocumentsRepository(storage)
        val owner = online(repository, path = "note.md")
        val token = room(repository, owner, "note.md")
        repository.queueRoomSnapshot(token, "old inflight")
        val put = repository.nextWrite(scope(repository, owner), "note.md")!!.frame as ClientFrame.Put
        repository.queueRoomSnapshot(token, "new generation")
        val remote = DocumentVersion(2, documentHash("remote winner"), false, "remote winner")
        // reject: record old variant, preserve new variant, then clear pending (fails).
        storage.failDraftAt = storage.draftWrites + 3
        injectedFailure { deliver(repository, owner, ServerFrame.Nack(FrameId.Integer(put.id), put.path, remote)) }

        val restarted = DocumentsRepository(storage)
        val restartedOwner = restarted.select(endpoint, alice)
        deliver(restarted, restartedOwner, hello(seq = 2))
        deliver(restarted, restartedOwner, change("note.md", "remote winner", 2))
        assertTrue(deliver(restarted, restartedOwner, ServerFrame.Synced(2)).isEmpty())
        val view = restarted.view()
        assertTrue(view.pending.isEmpty())
        assertEquals("remote winner", view.documents.getValue("note.md").content)
        assertTrue(view.recoveries.any { it.content == "old inflight" && it.remote == remote })
        assertTrue(view.recoveries.any { it.content == "new generation" && it.remote == remote })
        assertTrue(view.drafts.values.any { it.content == "new generation" })
        assertNull(restarted.nextWrite(scope(restarted, restartedOwner), "note.md"))
    }

    @Test fun longDirectoryConflictFallbackRemainsUniqueAcrossOfflineRestart() {
        val storage = MemoryStorage()
        val repository = DocumentsRepository(storage) { 0L }
        val path = "d".repeat(505) + "/a.md"
        val owner = online(repository, path = path)
        repository.saveDraft(scope(repository, owner), path, "first local variant")
        val first = repository.makeConflictCopy(scope(repository, owner), path)
        assertFalse(first.contains('/'))
        assertTrue(first.startsWith("recovery-"))
        FrameCodec.encode(ClientFrame.Put(0, first, "first local variant", 0))

        val restarted = DocumentsRepository(storage) { 0L }
        val restartedOwner = restarted.select(endpoint, alice)
        assertEquals("first local variant", restarted.view().drafts.getValue(first).content)
        assertEquals("base", restarted.view().recoveries.single().remote!!.content)
        restarted.saveDraft(scope(restarted, restartedOwner), path, "second local variant")
        val second = restarted.makeConflictCopy(scope(restarted, restartedOwner), path)
        assertFalse(first.equals(second, ignoreCase = true))
        assertFalse(second.contains('/'))
        assertEquals(setOf("first local variant", "second local variant"), restarted.view().drafts.values.map { it.content }.toSet())
        assertNull(restarted.nextWrite(scope(restarted, restartedOwner), second))
        assertTrue(restarted.view().recoveries.all { it.path == path })
    }

    @Test fun offlineNamespaceCannotBeOpenedAgainstAnotherServer() {
        val storage = MemoryStorage()
        val repository = DocumentsRepository(storage)
        val owner = online(repository)
        repository.saveDraft(scope(repository, owner), "note.md", "alice local")
        val other = RemoteEndpoint.create("https://other.example", "game")

        fails(DocumentFailure.INVALID_FRAME) { repository.select(other, alice) }
        assertEquals(alice, repository.view().namespace)
        assertEquals("alice local", repository.view().drafts.getValue("note.md").content)
    }

    @Test fun offlineRestartLoadsOnlyTheExplicitAccountNamespace() {
        val storage = MemoryStorage()
        val repository = DocumentsRepository(storage)
        val ownerA = online(repository)
        repository.saveDraft(scope(repository, ownerA), "note.md", "alice private")
        val ownerB = online(repository, bob)
        repository.saveDraft(scope(repository, ownerB), "note.md", "bob private")

        val restarted = DocumentsRepository(storage)
        var owner = restarted.select(endpoint, alice)
        assertEquals("alice private", restarted.view().drafts.getValue("note.md").content)
        assertNull(restarted.nextWrite(scope(restarted, owner), "note.md"))
        owner = restarted.select(endpoint, bob)
        assertEquals("bob private", restarted.view().drafts.getValue("note.md").content)
        assertNull(restarted.nextWrite(scope(restarted, owner), "note.md"))
        assertFalse(restarted.view().synced)
    }

    @Test fun offlineAccountHintMustNotAuthorizeDifferentAccountHello() {
        val storage = MemoryStorage()
        val repository = DocumentsRepository(storage)
        val firstOwner = online(repository)
        repository.saveDraft(scope(repository, firstOwner), "note.md", "alice private")
        val restarted = DocumentsRepository(storage)
        val offlineOwner = restarted.select(endpoint, alice)

        // Otherwise an already-queued alice autosave carrying offlineOwner is accepted for bob.
        // A new explicit selection is required to change the account, not only another hello.
        fails(DocumentFailure.INVALID_FRAME) { deliver(restarted, offlineOwner, hello(bob)) }
        fails(DocumentFailure.CORRUPT_JOURNAL) { restarted.saveDraft(scope(restarted, offlineOwner), "note.md", "queued alice autosave") }
        assertFalse(storage.journals.containsKey(journalKey(bob)))
    }

    @Test fun staleDocumentScopeCannotWriteAcrossReplacementSpaceId() {
        val storage = MemoryStorage()
        val repository = DocumentsRepository(storage)
        val owner = online(repository, path = "note.md")
        val oldScope = repository.commandScope(owner)
        repository.saveDraft(oldScope, "note.md", "old space private")
        val replacement = alice.copy(spaceId = "replacement-id")
        val effects = repository.onEvent(owner, ConnectionEvent.Frame(generation + 1, hello(replacement)))
        assertEquals(ClientFrame.Sync(0), effects.single().frame)
        repository.onEvent(owner, ConnectionEvent.Frame(generation + 1, ServerFrame.Synced(0)))
        val writes = storage.draftWrites
        fails(DocumentFailure.STALE_ROOM) { repository.saveDraft(oldScope, "note.md", "late checkpoint") }
        fails(DocumentFailure.STALE_ROOM) { repository.queueCreate(oldScope, "new.md", "late create") }
        fails(DocumentFailure.STALE_ROOM) { repository.makeConflictCopy(oldScope, "note.md") }
        fails(DocumentFailure.STALE_ROOM) { repository.nextWrite(oldScope, "note.md") }
        assertEquals(writes, storage.draftWrites)
        assertTrue(repository.view().recoveries.any { it.content == "old space private" })
        val freshScope = repository.commandScope(owner)
        repository.saveDraft(freshScope, "new.md", "new space only")
        assertEquals(replacement, repository.view().namespace)
        assertEquals("new space only", repository.view().drafts.getValue("new.md").content)
    }

    @Test fun savedProfileRestoresNamespaceAndRecoveryOfflineWithoutExternalIdentityMetadata() {
        val storage = MemoryStorage()
        val profile = SavedProfile.create("Work", endpoint.serverUrl, endpoint.space, "key-a", "profile-a")
        val first = DocumentsRepository(storage)
        val owner = first.selectProfile(profile)
        deliver(first, owner, hello(seq = 1))
        deliver(first, owner, change("note.md", "base", 1))
        deliver(first, owner, ServerFrame.Synced(1))
        first.saveDraft(first.commandScope(owner), "note.md", "persistent local")

        val restarted = DocumentsRepository(storage)
        val offlineOwner = restarted.selectProfile(profile)
        assertEquals(alice, restarted.view().namespace)
        assertEquals(1L, restarted.view().cursor)
        assertEquals("base", restarted.view().documents.getValue("note.md").content)
        assertEquals("persistent local", restarted.view().drafts.getValue("note.md").content)
        assertFalse(restarted.view().synced)
        assertNull(restarted.nextWrite(restarted.commandScope(offlineOwner), "note.md"))
        restarted.makeConflictCopy(restarted.commandScope(offlineOwner), "note.md")
        val again = DocumentsRepository(storage)
        again.selectProfile(profile)
        assertEquals("persistent local", again.view().recoveries.single().content)
    }

    @Test fun changingProfileCredentialReferenceCannotOpenThePreviousAccountsOfflineData() {
        val storage = MemoryStorage()
        val old = SavedProfile.create("Work", endpoint.serverUrl, endpoint.space, "key-a", "profile-a")
        val repository = DocumentsRepository(storage)
        val owner = repository.selectProfile(old)
        deliver(repository, owner, hello())
        deliver(repository, owner, ServerFrame.Synced(0))
        repository.saveDraft(repository.commandScope(owner), "note.md", "alice private")

        val changed = SavedProfile.create("Work", endpoint.serverUrl, endpoint.space, "key-b", "profile-a")
        val restarted = DocumentsRepository(storage)
        val newOwner = restarted.selectProfile(changed)
        assertNull(restarted.view().namespace)
        assertTrue(restarted.view().drafts.isEmpty())
        deliver(restarted, newOwner, hello(bob))
        deliver(restarted, newOwner, ServerFrame.Synced(0))
        assertEquals(bob, restarted.view().namespace)
        restarted.selectProfile(old)
        assertEquals("alice private", restarted.view().drafts.getValue("note.md").content)
    }

    @Test fun explicitlyFreshProfileSelectionCanBindANewAccountWithoutErasingTheOldAccount() {
        val storage = MemoryStorage()
        val profile = SavedProfile.create("Work", endpoint.serverUrl, endpoint.space, "key-a", "profile-a")
        val repository = DocumentsRepository(storage)
        var owner = repository.selectProfile(profile)
        deliver(repository, owner, hello())
        deliver(repository, owner, ServerFrame.Synced(0))
        repository.saveDraft(repository.commandScope(owner), "note.md", "alice preserved")

        val restarted = DocumentsRepository(storage)
        owner = restarted.selectProfile(profile)
        fails(DocumentFailure.INVALID_FRAME) { deliver(restarted, owner, hello(bob)) }
        owner = restarted.selectProfile(profile, useOfflineCache = false)
        assertNull(restarted.view().namespace)
        deliver(restarted, owner, hello(bob))
        deliver(restarted, owner, ServerFrame.Synced(0))
        assertTrue(restarted.view().drafts.isEmpty())
        restarted.select(endpoint, alice)
        assertEquals("alice preserved", restarted.view().drafts.getValue("note.md").content)
    }
}
