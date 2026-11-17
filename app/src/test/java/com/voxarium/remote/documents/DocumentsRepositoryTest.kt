package com.voxarium.remote.documents

import com.voxarium.remote.data.documents.DocumentFailure
import com.voxarium.remote.data.documents.DocumentNamespace
import com.voxarium.remote.data.documents.DocumentRepositoryException
import com.voxarium.remote.data.documents.DocumentSend
import com.voxarium.remote.data.documents.DocumentStorage
import com.voxarium.remote.data.documents.DocumentStorageException
import com.voxarium.remote.data.documents.DocumentsRepository
import com.voxarium.remote.data.documents.DraftKind
import com.voxarium.remote.data.documents.RecoveryReason
import com.voxarium.remote.data.documents.RoomWriteToken
import com.voxarium.remote.data.documents.documentHash
import com.voxarium.remote.data.transport.ClientFrame
import com.voxarium.remote.data.transport.ConnectionEvent
import com.voxarium.remote.data.transport.ConnectionPhase
import com.voxarium.remote.data.transport.DocumentVersion
import com.voxarium.remote.data.transport.FrameCodec
import com.voxarium.remote.data.transport.FrameId
import com.voxarium.remote.data.transport.RemoteEndpoint
import com.voxarium.remote.data.transport.RemoteSpace
import com.voxarium.remote.data.transport.RemoteUser
import com.voxarium.remote.data.transport.ResetReason
import com.voxarium.remote.data.transport.SendResult
import com.voxarium.remote.data.transport.ServerFrame
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

/**
 * Public-API state-machine tests: no Android runner, network, editor, disk or private state codec.
 * Reconstructing a repository over MemoryStorage is the crash/restart boundary. Every returned
 * DocumentSend is only an effect for a caller to submit; ENQUEUED is deliberately not an ACK.
 */
class DocumentsRepositoryTest {
    private companion object {
        const val PATH = "folder/note.md"
        const val SID = "room-1"
        const val EPOCH = "epoch-1"
        val ENDPOINT = RemoteEndpoint.create("https://remote.example/proxy", "game")
        val USER = RemoteUser("account-a", "alice", "#123456")
        const val SPACE = "space-a"
        const val COPY = "folder/note (conflict alice 1970-01-01 000000).md"

        fun version(content: String, rev: Long) = DocumentVersion(rev, documentHash(content), false, content)
    }

    /** Atomic replacement semantics, with independently injectable cache/journal write failures. */
    private class MemoryStorage : DocumentStorage {
        val caches = linkedMapOf<String, ByteArray>()
        val journals = linkedMapOf<String, ByteArray>()
        var cacheWrites = 0
        var journalWrites = 0
        var failCacheAfter: Int? = null
        var failJournalAfter: Int? = null

        override fun readCache(namespace: String) = caches[namespace]?.clone()
        override fun readDrafts(namespace: String) = journals[namespace]?.clone()

        override fun writeCache(namespace: String, bytes: ByteArray) {
            failCacheAfter?.let {
                if (it == 0) {
                    failCacheAfter = null
                    throw DocumentStorageException(DocumentStorageException.Reason.STORAGE)
                }
                failCacheAfter = it - 1
            }
            caches[namespace] = bytes.clone()
            cacheWrites++
        }

        override fun writeDrafts(namespace: String, bytes: ByteArray) {
            failJournalAfter?.let {
                if (it == 0) {
                    failJournalAfter = null
                    throw DocumentStorageException(DocumentStorageException.Reason.STORAGE)
                }
                failJournalAfter = it - 1
            }
            journals[namespace] = bytes.clone()
            journalWrites++
        }
    }

    private class Harness(
        val storage: MemoryStorage = MemoryStorage(),
        val endpoint: RemoteEndpoint = ENDPOINT,
        val user: RemoteUser = USER,
        val spaceId: String = SPACE,
        var generation: Long = 1,
    ) {
        val repo = DocumentsRepository(storage) { 0L }
        val owner = repo.select(endpoint)
        val scope get() = repo.commandScope(owner)

        fun event(frame: ServerFrame, gen: Long = generation, selected: Long = owner): List<DocumentSend> =
            repo.onEvent(selected, ConnectionEvent.Frame(gen, frame))

        fun hello(seq: Long = 999): List<DocumentSend> = event(
            ServerFrame.Hello(FrameCodec.PROTOCOL_VERSION.toLong(), user, RemoteSpace(spaceId, endpoint.space), seq),
        )

        fun synced(seq: Long = 10) { assertTrue(event(ServerFrame.Synced(seq)).isEmpty()) }

        fun change(content: String, rev: Long, path: String = PATH) {
            val v = version(content, rev)
            assertTrue(event(ServerFrame.Change(path, rev, v.hash, false, content, USER)).isEmpty())
        }

        fun tombstone(rev: Long, path: String = PATH) {
            assertTrue(event(ServerFrame.Change(path, rev, null, true, null, USER)).isEmpty())
        }

        fun online(content: String = "base", rev: Long = 3): Harness {
            assertSync(0, hello())
            change(content, rev)
            synced()
            return this
        }

        fun room(
            path: String = PATH, sid: String = SID, epoch: String = EPOCH,
            seeded: Boolean = true, scribe: Boolean = true,
        ): RoomWriteToken {
            joined(path, sid, epoch, seeded, scribe)
            return repo.confirmRoomReady(owner, generation, sid, epoch)
        }

        fun joined(
            path: String = PATH, sid: String = SID, epoch: String = EPOCH,
            seeded: Boolean = true, scribe: Boolean = true,
        ) {
            assertTrue(event(ServerFrame.Joined(
                sid, path, epoch, seeded, if (seeded) "AA==" else null, scribe, false,
                repo.view().documents.getValue(path).rev,
            )).isEmpty())
        }

        fun put(path: String = PATH): ClientFrame.Put = repo.nextWrite(scope, path)!!.let {
            assertEquals(generation, it.connectionGeneration)
            it.frame as ClientFrame.Put
        }

        fun ack(put: ClientFrame.Put, rev: Long) {
            assertTrue(event(ServerFrame.Ack(
                FrameId.Integer(put.id), put.path, rev, documentHash(put.content), false,
            )).isEmpty())
        }

        fun reconnect(nextGeneration: Long = generation + 1): List<DocumentSend> {
            assertTrue(repo.onEvent(owner, ConnectionEvent.State(generation, ConnectionPhase.BACKOFF)).isEmpty())
            generation = nextGeneration
            assertTrue(repo.onEvent(owner, ConnectionEvent.State(generation, ConnectionPhase.READY)).isEmpty())
            return hello()
        }

        companion object {
            fun assertSync(since: Long, sends: List<DocumentSend>) {
                assertEquals(1, sends.size)
                assertEquals(ClientFrame.Sync(since), sends.single().frame)
            }
        }
    }

    private fun assertSync(since: Long, sends: List<DocumentSend>) = Harness.assertSync(since, sends)

    private fun expectFailure(reason: DocumentFailure, block: () -> Unit) {
        try {
            block()
            fail("Expected $reason")
        } catch (error: DocumentRepositoryException) {
            assertEquals(reason, error.reason)
        }
    }

    private fun expectStorageFailure(block: () -> Unit) {
        try {
            block()
            fail("Expected failed persistent transaction")
        } catch (error: DocumentStorageException) {
            assertEquals(DocumentStorageException.Reason.STORAGE, error.reason)
        } catch (error: DocumentRepositoryException) {
            assertEquals(DocumentFailure.STORAGE, error.reason)
        }
    }

    private fun assertWriteBlocked(h: Harness, path: String = PATH) {
        try {
            assertNull(h.repo.nextWrite(h.scope, path))
        } catch (error: DocumentRepositoryException) {
            // A journal/application failure may deliberately freeze this repository until reload.
            assertTrue(error.reason in setOf(DocumentFailure.NOT_SYNCED, DocumentFailure.CORRUPT_JOURNAL, DocumentFailure.STORAGE))
        }
    }

    @Test fun firstHelloRequestsFullSyncAndNeverAdoptsAdvertisedSeq() {
        val h = Harness()
        assertNull(h.repo.view().namespace)
        assertWriteBlocked(h)
        assertSync(0, h.hello(seq = 900_000))
        assertEquals(DocumentNamespace(ENDPOINT.serverUrl, USER.id, SPACE), h.repo.view().namespace)
        assertEquals(0L, h.repo.view().cursor)
        assertFalse(h.repo.view().synced)
        expectFailure(DocumentFailure.NOT_SYNCED) { h.repo.queueCreate(h.scope, "new.md", "new") }
        h.change("base", 5)
        assertEquals(0L, h.repo.view().cursor)
        h.synced(5)
        assertTrue(h.repo.view().synced)
        assertEquals(5L, h.repo.view().cursor)
        assertTrue(h.repo.view().pending.isEmpty())
        assertNull(h.repo.nextWrite(h.scope, PATH))
    }

    @Test fun incompleteCacheIsDiscardedAndRestartUsesSyncZero() {
        val h = Harness()
        h.hello()
        h.change("partial snapshot", 8)
        assertEquals("partial snapshot", h.repo.view().documents.getValue(PATH).content)
        val restarted = Harness(h.storage, generation = 20)
        assertSync(0, restarted.hello(seq = 8))
        assertTrue(restarted.repo.view().documents.isEmpty())
        assertEquals(0L, restarted.repo.view().cursor)
        restarted.synced(8)
        assertTrue(restarted.repo.view().documents.isEmpty())
    }

    @Test fun completeCacheAndDurableCursorReloadButLiveChangesDoNotAdvanceCursor() {
        val h = Harness().online()
        h.change("live newer", 11)
        val restarted = Harness(h.storage, generation = 20)
        assertSync(10, restarted.hello(seq = 700))
        assertEquals(10L, restarted.repo.view().cursor)
        assertEquals(version("live newer", 11), restarted.repo.view().documents[PATH])
        assertFalse(restarted.repo.view().synced)
        restarted.synced(11)
        val again = Harness(h.storage, generation = 30)
        assertSync(11, again.hello(seq = 800))
    }

    @Test fun corruptCacheFallsBackToSyncZeroWithoutDiscardingDurableDraft() {
        val h = Harness().online()
        val generation = h.repo.saveDraft(h.scope, PATH, "unpublished")
        h.storage.caches.keys.toList().forEach { h.storage.caches[it] = byteArrayOf(0, 1, 2) }
        val restarted = Harness(h.storage, generation = 20)
        assertSync(0, restarted.hello())
        assertTrue(restarted.repo.view().documents.isEmpty())
        assertEquals(generation, restarted.repo.view().drafts.getValue(PATH).generation)
        assertEquals("unpublished", restarted.repo.view().drafts.getValue(PATH).content)
        assertNull(restarted.repo.nextWrite(restarted.scope, PATH))
    }

    @Test fun failedSyncedCacheCommitCannotAdvanceRestartCursor() {
        val h = Harness().online()
        assertSync(10, h.reconnect())
        h.change("new revision", 12)
        h.storage.failCacheAfter = 0
        expectStorageFailure { h.synced(12) }
        assertEquals(10L, h.repo.view().cursor)
        assertFalse(h.repo.view().synced)
        val restarted = Harness(h.storage, generation = 20)
        assertSync(10, restarted.hello(seq = 12))
        assertEquals("new revision", restarted.repo.view().documents.getValue(PATH).content)
    }

    @Test fun serverAccountAndSpaceNamespacesKeepTheirOwnCacheCursorAndJournal() {
        data class Identity(val endpoint: RemoteEndpoint, val user: RemoteUser, val space: String)
        val identities = listOf(
            Identity(ENDPOINT, USER, SPACE),
            Identity(ENDPOINT, USER.copy(id = "account-b"), SPACE),
            Identity(RemoteEndpoint.create("https://remote.example/other-proxy", "game"), USER, SPACE),
            Identity(RemoteEndpoint.create(ENDPOINT.serverUrl, "other-game"), USER, "space-b"),
            Identity(RemoteEndpoint.create("https://other.example/proxy", "game"), USER, SPACE),
        )
        val storage = MemoryStorage()
        identities.forEachIndexed { i, id ->
            val h = Harness(storage, id.endpoint, id.user, id.space)
            assertSync(0, h.hello())
            h.change("remote-$i", i + 1L)
            h.synced(i + 10L)
            h.repo.saveDraft(h.scope, PATH, "draft-$i")
        }
        identities.forEachIndexed { i, id ->
            val h = Harness(storage, id.endpoint, id.user, id.space, generation = 100)
            assertSync(i + 10L, h.hello())
            assertEquals(DocumentNamespace(id.endpoint.serverUrl, id.user.id, id.space), h.repo.view().namespace)
            assertEquals("remote-$i", h.repo.view().documents.getValue(PATH).content)
            assertEquals("draft-$i", h.repo.view().drafts.getValue(PATH).content)
            assertTrue(h.repo.view().recoveries.isEmpty())
            assertNull(h.repo.nextWrite(h.scope, PATH))
        }
        assertEquals(identities.size, storage.caches.size)
        // Two differently named spaces share the account journal, not their active drafts.
        assertEquals(identities.size - 1, storage.journals.size)
    }

    @Test fun replacedSpacePreservesInflightAndNewerDraftUnderOldSpaceAndNeverReplaysThem() {
        val old = Harness().online()
        val token = old.room()
        val firstGeneration = old.repo.queueRoomSnapshot(token, "in flight old-space")
        old.put()
        val secondGeneration = old.repo.queueRoomSnapshot(token, "newer old-space")
        val replacement = Harness(old.storage, spaceId = "replacement-id", generation = 20)
        assertSync(0, replacement.hello(seq = 30))
        assertTrue(replacement.repo.view().documents.isEmpty())
        assertTrue(replacement.repo.view().drafts.isEmpty())
        assertTrue(replacement.repo.view().pending.isEmpty())
        val recoveries = replacement.repo.view().recoveries
        assertEquals(setOf("in flight old-space", "newer old-space"), recoveries.map { it.content }.toSet())
        assertEquals(setOf(firstGeneration, secondGeneration), recoveries.map { it.generation }.toSet())
        assertTrue(recoveries.all { it.spaceId == SPACE && it.reason == RecoveryReason.SPACE_REPLACED })
        replacement.synced(30)
        assertNull(replacement.repo.nextWrite(replacement.scope, PATH))
        val restarted = Harness(old.storage, spaceId = "replacement-id", generation = 30)
        restarted.hello()
        assertEquals(recoveries, restarted.repo.view().recoveries)
        val returnToOld = Harness(old.storage, spaceId = SPACE, generation = 40)
        assertSync(0, returnToOld.hello())
        assertTrue(returnToOld.repo.view().drafts.isEmpty())
        assertTrue(returnToOld.repo.view().pending.isEmpty())
        assertEquals(recoveries, returnToOld.repo.view().recoveries)
        returnToOld.synced(40)
        assertNull(returnToOld.repo.nextWrite(returnToOld.scope, PATH))
    }

    @Test fun tombstoneIsDurableOccupiedAndDraftRetainsItsRealBase() {
        val h = Harness().online()
        h.tombstone(11)
        val deleted = DocumentVersion(11, null, true, null)
        assertEquals(deleted, h.repo.view().documents[PATH])
        expectFailure(DocumentFailure.PATH_OCCUPIED) { h.repo.queueCreate(h.scope, PATH, "recreate silently") }
        h.repo.saveDraft(h.scope, PATH, "local after deletion")
        assertEquals(deleted, h.repo.view().drafts.getValue(PATH).base)
        assertNull(h.repo.nextWrite(h.scope, PATH))
        val copy = h.repo.makeConflictCopy(h.scope, PATH)
        assertEquals(deleted, h.repo.view().recoveries.single().remote)
        val write = h.put(copy)
        assertEquals(0L, write.baseRev)
        assertNull(write.live)
        val restarted = Harness(h.storage, generation = 20)
        restarted.hello()
        assertEquals(deleted, restarted.repo.view().documents[PATH])
    }

    @Test fun recreateAfterTombstoneUsesNewestRemoteBaseWithoutArmingOriginal() {
        val h = Harness().online()
        h.tombstone(11)
        h.change("server recreated", 12)
        h.change("late old callback", 9)
        assertEquals(version("server recreated", 12), h.repo.view().documents[PATH])
        h.repo.saveDraft(h.scope, PATH, "local")
        assertEquals(version("server recreated", 12), h.repo.view().drafts.getValue(PATH).base)
        assertNull(h.repo.nextWrite(h.scope, PATH))
        assertEquals(version("server recreated", 12), h.repo.view().documents[PATH])
    }

    @Test fun ackThenMatchingChangeConfirmExactlyOneGeneration() = ackChangeRace(ackFirst = true)

    @Test fun matchingChangeThenAckConfirmExactlyOneGeneration() = ackChangeRace(ackFirst = false)

    private fun ackChangeRace(ackFirst: Boolean) {
        val h = Harness().online()
        h.repo.queueRoomSnapshot(h.room(), "sent")
        val put = h.put()
        if (ackFirst) { h.ack(put, 11); h.change("sent", 11) }
        else { h.change("sent", 11); assertTrue(h.repo.view().pending.containsKey(PATH)); h.ack(put, 11) }
        assertEquals(version("sent", 11), h.repo.view().documents[PATH])
        assertTrue(h.repo.view().drafts.isEmpty())
        assertTrue(h.repo.view().pending.isEmpty())
        assertTrue(h.repo.view().recoveries.isEmpty())
        assertNull(h.repo.nextWrite(h.scope, PATH))
        // A repeated ACK/change is harmless and cannot invent a draft or another write.
        h.ack(put, 11)
        h.change("sent", 11)
        val restarted = Harness(h.storage, generation = 20)
        restarted.hello()
        assertEquals(version("sent", 11), restarted.repo.view().documents[PATH])
        assertTrue(restarted.repo.view().drafts.isEmpty())
        assertTrue(restarted.repo.view().pending.isEmpty())
    }

    @Test fun oneInflightSnapshotIsImmutableAndNewerDraftSurvivesOlderAck() {
        val h = Harness().online()
        val token = h.room()
        val firstGeneration = h.repo.queueRoomSnapshot(token, "first")
        val first = h.put()
        assertEquals(3L, first.baseRev)
        assertEquals(SID, first.live)
        val newerGeneration = h.repo.queueRoomSnapshot(token, "newer")
        assertTrue(newerGeneration > firstGeneration)
        assertNull(h.repo.nextWrite(h.scope, PATH))
        assertEquals("first", h.repo.view().pending.getValue(PATH).content)
        assertEquals(firstGeneration, h.repo.view().pending.getValue(PATH).generation)
        assertEquals("newer", h.repo.view().drafts.getValue(PATH).content)
        h.ack(first, 11)
        assertEquals(newerGeneration, h.repo.view().drafts.getValue(PATH).generation)
        assertEquals("newer", h.repo.view().drafts.getValue(PATH).content)
        val second = h.put()
        assertTrue(second.id > first.id)
        assertEquals("newer", second.content)
        assertEquals(11L, second.baseRev)
        assertEquals(SID, second.live)
        assertNull(h.repo.nextWrite(h.scope, PATH))
    }

    @Test fun changeThenOlderAckDoesNotEraseNewerLocalGeneration() {
        val h = Harness().online()
        val token = h.room()
        h.repo.queueRoomSnapshot(token, "first")
        val put = h.put()
        val newer = h.repo.queueRoomSnapshot(token, "newer")
        h.change("first", 11)
        h.ack(put, 11)
        assertEquals(newer, h.repo.view().drafts.getValue(PATH).generation)
        assertEquals("newer", h.repo.view().drafts.getValue(PATH).content)
        // Change carries no room incarnation. The repository may conservatively retire the
        // room; surviving the ACK does not authorize replay through an unconfirmed new room.
        assertNull(h.repo.nextWrite(h.scope, PATH))
    }

    @Test fun newerRemoteChangeBeforeOlderAckCannotRollBackRemoteAndPreservesBothLocalVariants() {
        val h = Harness().online()
        val token = h.room()
        h.repo.queueRoomSnapshot(token, "in flight")
        val put = h.put()
        h.repo.queueRoomSnapshot(token, "newer draft")
        h.change("other author", 12)
        h.ack(put, 11)
        assertEquals(version("other author", 12), h.repo.view().documents[PATH])
        assertTrue(h.repo.view().pending.isEmpty())
        assertFalse(h.repo.view().drafts.containsKey(PATH))
        assertEquals(setOf("in flight", "newer draft"), h.repo.view().recoveries.map { it.content }.toSet())
        assertTrue(h.repo.view().recoveries.all { it.remote == version("other author", 12) })
        assertNull(h.repo.nextWrite(h.scope, PATH))
    }

    @Test fun unknownPendingAfterRestartResolvesMatchingHashWithoutReplayingLivePut() {
        val h = Harness().online()
        h.repo.queueRoomSnapshot(h.room(), "server accepted before crash")
        val put = h.put()
        val restarted = Harness(h.storage, generation = 20)
        assertSync(10, restarted.hello(seq = 11))
        assertEquals(put.content, restarted.repo.view().pending.getValue(PATH).content)
        assertNull(restarted.repo.nextWrite(restarted.scope, PATH))
        restarted.change(put.content, 11)
        restarted.synced(11)
        assertTrue(restarted.repo.view().pending.isEmpty())
        assertTrue(restarted.repo.view().drafts.isEmpty())
        assertTrue(restarted.repo.view().recoveries.isEmpty())
        assertNull(restarted.repo.nextWrite(restarted.scope, PATH))
    }

    @Test fun unknownPendingAfterRestartPreservesBothGenerationsWhenRemoteDiffers() {
        val h = Harness().online()
        val token = h.room()
        h.repo.queueRoomSnapshot(token, "unknown sent")
        h.put()
        h.repo.queueRoomSnapshot(token, "newer unsent")
        val restarted = Harness(h.storage, generation = 20)
        assertSync(10, restarted.hello())
        assertNull(restarted.repo.nextWrite(restarted.scope, PATH))
        restarted.change("remote different", 11)
        restarted.synced(11)
        assertTrue(restarted.repo.view().pending.isEmpty())
        assertFalse(restarted.repo.view().drafts.containsKey(PATH))
        assertEquals(setOf("unknown sent", "newer unsent"), restarted.repo.view().recoveries.map { it.content }.toSet())
        assertTrue(restarted.repo.view().recoveries.all {
            it.reason == RecoveryReason.OFFLINE_OR_RESTART && it.remote == version("remote different", 11)
        })
        assertNull(restarted.repo.nextWrite(restarted.scope, PATH))
        restarted.repo.view().drafts.keys.forEach { copy ->
            assertNotEquals(PATH, copy)
            val send = restarted.put(copy)
            assertNull(send.live)
            assertEquals(0L, send.baseRev)
        }
    }

    @Test fun matchingUnknownSnapshotStillPreservesNewerUnsentGenerationOnRestart() {
        val h = Harness().online()
        val token = h.room()
        h.repo.queueRoomSnapshot(token, "accepted")
        h.put()
        h.repo.queueRoomSnapshot(token, "newer local")
        val restarted = Harness(h.storage, generation = 20)
        restarted.hello()
        restarted.change("accepted", 11)
        restarted.synced(11)
        assertTrue(restarted.repo.view().pending.isEmpty())
        assertFalse(restarted.repo.view().drafts.containsKey(PATH))
        assertEquals(listOf("newer local"), restarted.repo.view().recoveries.map { it.content })
        assertEquals("newer local", restarted.repo.view().drafts.values.single().content)
        assertNull(restarted.repo.nextWrite(restarted.scope, PATH))
    }

    @Test fun unknownCreateMissingOnServerBecomesRecoveryNotBlindReplayAtOriginalName() {
        val h = Harness().online()
        h.repo.queueCreate(h.scope, "new.md", "unknown create")
        h.put("new.md")
        val restarted = Harness(h.storage, generation = 20)
        restarted.hello()
        restarted.synced(10)
        assertTrue(restarted.repo.view().pending.isEmpty())
        assertFalse(restarted.repo.view().drafts.containsKey("new.md"))
        assertEquals("unknown create", restarted.repo.view().recoveries.single().content)
        assertNull(restarted.repo.nextWrite(restarted.scope, "new.md"))
    }

    @Test fun enqueuedIsNotAckAndRejectedSendRemainsUnknownUntilNewSync() {
        val h = Harness().online()
        h.repo.queueRoomSnapshot(h.room(), "sent")
        val put = h.put()
        h.repo.onSendResult(h.owner, h.generation, put.id, SendResult.ENQUEUED)
        assertEquals("base", h.repo.view().documents.getValue(PATH).content)
        assertTrue(h.repo.view().pending.containsKey(PATH))
        assertTrue(h.repo.view().drafts.containsKey(PATH))
        h.repo.onSendResult(h.owner, h.generation, put.id, SendResult.BACKPRESSURE)
        assertFalse(h.repo.view().synced)
        assertTrue(h.repo.view().pending.containsKey(PATH))
        assertNull(h.repo.nextWrite(h.scope, PATH))
        assertSync(10, h.reconnect())
        h.change("sent", 11)
        h.synced(11)
        assertTrue(h.repo.view().pending.isEmpty())
        assertTrue(h.repo.view().drafts.isEmpty())
        assertNull(h.repo.nextWrite(h.scope, PATH))
    }

    @Test fun nackPersistsBothVersionsBeforeAnyCopySendAndNeverRebasesOriginal() {
        val h = Harness().online()
        val token = h.room()
        h.repo.queueRoomSnapshot(token, "in flight")
        val put = h.put()
        h.repo.queueRoomSnapshot(token, "newer local")
        val remote = version("concurrent remote", 11)
        assertTrue(h.event(ServerFrame.Nack(FrameId.Integer(put.id), PATH, remote)).isEmpty())
        val view = h.repo.view()
        assertEquals(remote, view.documents[PATH])
        assertFalse(view.drafts.containsKey(PATH))
        assertTrue(view.pending.isEmpty())
        assertEquals(setOf("in flight", "newer local"), view.recoveries.map { it.content }.toSet())
        assertTrue(view.recoveries.all { it.remote == remote && it.reason == RecoveryReason.REJECTED })
        assertTrue(view.drafts.values.all { it.base == null && it.kind == DraftKind.CONFLICT_COPY })
        assertNull(h.repo.nextWrite(h.scope, PATH))
        expectFailure(DocumentFailure.STALE_ROOM) { h.repo.queueRoomSnapshot(token, "must not overwrite") }
        // Read the durable journal with a completely new repository BEFORE requesting any send.
        val restarted = Harness(h.storage, generation = 20)
        restarted.hello()
        assertEquals(view.recoveries, restarted.repo.view().recoveries)
        assertEquals(view.drafts, restarted.repo.view().drafts)
        assertTrue(restarted.repo.view().pending.isEmpty())
        val copy = view.drafts.keys.single()
        val publish = h.put(copy)
        assertNotEquals(PATH, publish.path)
        assertEquals("newer local", publish.content)
        assertEquals(0L, publish.baseRev)
        assertNull(publish.live)
        assertNull(h.repo.nextWrite(h.scope, PATH))
    }

    @Test fun makeConflictCopyUsesCaseInsensitiveCollisionChecksForRemoteDraftAndPreviousCopies() {
        val h = Harness().online()
        h.change("existing case-only name", 11, COPY.uppercase(Locale.ROOT))
        val secondName = COPY.replace(").md", ") 2.md")
        h.repo.queueCreate(h.scope, secondName, "local competing create")
        h.repo.saveDraft(h.scope, PATH, "first local")
        val first = h.repo.makeConflictCopy(h.scope, PATH)
        assertEquals(COPY.replace(").md", ") 3.md"), first)
        h.repo.saveDraft(h.scope, PATH, "second local")
        val second = h.repo.makeConflictCopy(h.scope, PATH)
        assertEquals(COPY.replace(").md", ") 4.md"), second)
        assertNotEquals(first.lowercase(Locale.ROOT), second.lowercase(Locale.ROOT))
        assertEquals(setOf("first local", "second local"), h.repo.view().recoveries.map { it.content }.toSet())
        assertNull(h.repo.nextWrite(h.scope, PATH))
        assertEquals(0L, h.put(first).baseRev)
        assertEquals(0L, h.put(second).baseRev)
    }

    @Test fun copyNackKeepsCollidingRemoteAndChoosesAnotherNonNestedCopyName() {
        val h = Harness().online()
        h.repo.saveDraft(h.scope, PATH, "local")
        val copy = h.repo.makeConflictCopy(h.scope, PATH)
        assertEquals(COPY, copy)
        val put = h.put(copy)
        val remote = version("copy name already taken", 11)
        h.event(ServerFrame.Nack(FrameId.Integer(put.id), copy, remote))
        assertEquals(remote, h.repo.view().documents[copy])
        assertFalse(h.repo.view().drafts.containsKey(copy))
        assertTrue(h.repo.view().pending.isEmpty())
        val retryName = h.repo.view().drafts.keys.single()
        assertEquals(COPY.replace(").md", ") 2.md"), retryName)
        assertEquals(1, Regex("conflict alice").findAll(retryName).count())
        assertNull(h.repo.nextWrite(h.scope, copy))
        val retry = h.put(retryName)
        assertEquals(0L, retry.baseRev)
        assertNull(retry.live)
        assertEquals("local", retry.content)
        assertNull(h.repo.nextWrite(h.scope, PATH))
    }

    @Test fun caseOnlyRemoteCollisionIsVisibleAndBlocksExplicitCreate() {
        val h = Harness().online()
        h.change("case sibling", 11, "folder/NOTE.md")
        assertEquals(setOf(PATH, "folder/NOTE.md"), h.repo.view().caseCollisions)
        expectFailure(DocumentFailure.PATH_OCCUPIED) { h.repo.queueCreate(h.scope, "FOLDER/Note.md", "new") }
        assertTrue(h.repo.view().pending.isEmpty())
    }

    @Test fun collisionArrivingAfterQueueCreateRenamesDraftInsteadOfOverwriting() {
        val h = Harness().online()
        h.repo.queueCreate(h.scope, "Fresh.md", "local create")
        h.change("remote create", 11, "FRESH.MD")
        assertNull(h.repo.nextWrite(h.scope, "Fresh.md"))
        assertFalse(h.repo.view().drafts.containsKey("Fresh.md"))
        assertEquals("remote create", h.repo.view().documents.getValue("FRESH.MD").content)
        val renamed = h.repo.view().drafts.keys.single()
        assertNotEquals("fresh.md", renamed.lowercase(Locale.ROOT))
        val put = h.put(renamed)
        assertEquals(0L, put.baseRev)
        assertNull(put.live)
    }

    @Test fun offlineOriginalNeverBecomesNextWriteAndFreshRoomCannotAdoptIt() {
        val h = Harness().online()
        h.repo.onEvent(h.owner, ConnectionEvent.State(h.generation, ConnectionPhase.BACKOFF))
        h.repo.saveDraft(h.scope, PATH, "offline original")
        assertNull(h.repo.nextWrite(h.scope, PATH))
        assertSync(10, h.reconnect())
        h.synced(10)
        assertFalse(h.repo.view().drafts.containsKey(PATH))
        assertEquals("offline original", h.repo.view().recoveries.single().content)
        assertNull(h.repo.nextWrite(h.scope, PATH))
        val token = h.room(sid = "fresh-room", epoch = "fresh-epoch")
        assertNull(h.repo.nextWrite(h.scope, PATH))
        assertEquals("offline original", h.repo.view().drafts.values.single().content)
        h.repo.queueRoomSnapshot(token, "new real merged snapshot")
        assertEquals("new real merged snapshot", h.put().content)
    }

    @Test fun saveDraftWhileOnlineDisarmsPreviouslyQueuedOriginalAndRoomConfirmationPreservesIt() {
        val h = Harness().online()
        val token = h.room()
        h.repo.queueRoomSnapshot(token, "queued snapshot")
        h.repo.saveDraft(h.scope, PATH, "native local draft")
        assertNull(h.repo.nextWrite(h.scope, PATH))
        h.room(sid = "another-room", epoch = "another-epoch")
        assertFalse(h.repo.view().drafts.containsKey(PATH))
        assertEquals("native local draft", h.repo.view().recoveries.single().content)
        assertNull(h.repo.nextWrite(h.scope, PATH))
    }

    @Test fun openingAndConfirmingRoomDoesNotWriteAndUnchangedSnapshotDoesNotSend() {
        val h = Harness().online()
        val cacheWrites = h.storage.cacheWrites
        val journalWrites = h.storage.journalWrites
        val token = h.room()
        assertEquals(cacheWrites, h.storage.cacheWrites)
        assertEquals(journalWrites, h.storage.journalWrites)
        assertNull(h.repo.nextWrite(h.scope, PATH))
        assertTrue(h.repo.view().drafts.isEmpty())
        h.repo.queueRoomSnapshot(token, "base")
        assertNull(h.repo.nextWrite(h.scope, PATH))
        assertTrue(h.repo.view().pending.isEmpty())
    }

    @Test fun resetRetiresTokenAndRestartPreservesBothUnknownAndNewerSnapshots() {
        val h = Harness().online()
        val token = h.room()
        h.repo.queueRoomSnapshot(token, "unknown before reset")
        h.put()
        h.repo.queueRoomSnapshot(token, "newer before reset")
        h.event(ServerFrame.Reset(SID, PATH, ResetReason.REPLACED))
        expectFailure(DocumentFailure.STALE_ROOM) { h.repo.queueRoomSnapshot(token, "late editor") }
        assertNull(h.repo.nextWrite(h.scope, PATH))
        assertTrue(h.repo.view().recoveries.any { it.content == "newer before reset" && it.reason == RecoveryReason.REMOTE_RESET })
        val restarted = Harness(h.storage, generation = 20)
        restarted.hello()
        restarted.change("remote after reset", 11)
        restarted.synced(11)
        assertTrue(restarted.repo.view().pending.isEmpty())
        assertFalse(restarted.repo.view().drafts.containsKey(PATH))
        assertEquals(setOf("unknown before reset", "newer before reset"), restarted.repo.view().recoveries.map { it.content }.toSet())
        assertNull(restarted.repo.nextWrite(restarted.scope, PATH))
    }

    @Test fun oldProfileFramesAndSendCallbacksCannotMutateNewSelection() {
        val h = Harness().online()
        val oldToken = h.room()
        h.repo.queueRoomSnapshot(oldToken, "old profile pending")
        val oldPut = h.put()
        val nextEndpoint = RemoteEndpoint.create("https://other.example", "elsewhere")
        val newOwner = h.repo.select(nextEndpoint)
        assertTrue(newOwner > h.owner)
        assertTrue(h.event(ServerFrame.Hello(1, USER, RemoteSpace(SPACE, ENDPOINT.space), 900)).isEmpty())
        assertNull(h.repo.view().namespace)
        val newHello = ServerFrame.Hello(1, USER.copy(id = "other-account"), RemoteSpace("other-space", "elsewhere"), 50)
        assertSync(0, h.repo.onEvent(newOwner, ConnectionEvent.Frame(100, newHello)))
        h.repo.onEvent(newOwner, ConnectionEvent.Frame(100, ServerFrame.Synced(50)))
        val before = h.repo.view()
        h.change("stale old change", 999)
        h.ack(oldPut, 11)
        h.event(ServerFrame.Nack(FrameId.Integer(oldPut.id), PATH, version("stale nack", 999)))
        h.repo.onEvent(h.owner, ConnectionEvent.State(999, ConnectionPhase.STOPPED))
        h.repo.onSendResult(h.owner, h.generation, oldPut.id, SendResult.CLOSED)
        assertEquals(before, h.repo.view())
        expectFailure(DocumentFailure.STALE_ROOM) { h.repo.queueRoomSnapshot(oldToken, "late old editor") }
        expectFailure(DocumentFailure.STALE_ROOM) { h.repo.confirmRoomReady(h.owner, 100, SID, EPOCH) }
        val oldAgain = Harness(h.storage, generation = 200)
        oldAgain.hello()
        assertEquals("old profile pending", oldAgain.repo.view().pending.getValue(PATH).content)
    }

    @Test fun olderConnectionFramesStateAndSendResultsAreFencedAfterReconnect() {
        val h = Harness().online()
        val token = h.room()
        h.repo.queueRoomSnapshot(token, "old pending")
        val put = h.put()
        val oldGeneration = h.generation
        assertSync(10, h.reconnect())
        val before = h.repo.view()
        h.event(ServerFrame.Hello(1, USER, RemoteSpace(SPACE, ENDPOINT.space), 999), oldGeneration)
        h.event(ServerFrame.Change(PATH, 999, documentHash("stale"), false, "stale", USER), oldGeneration)
        h.event(ServerFrame.Ack(FrameId.Integer(put.id), PATH, 11, documentHash(put.content), false), oldGeneration)
        h.event(ServerFrame.Nack(FrameId.Integer(put.id), PATH, version("stale", 999)), oldGeneration)
        h.repo.onEvent(h.owner, ConnectionEvent.State(oldGeneration, ConnectionPhase.STOPPED))
        h.repo.onSendResult(h.owner, oldGeneration, put.id, SendResult.CLOSED)
        assertEquals(before, h.repo.view())
        expectFailure(DocumentFailure.STALE_ROOM) { h.repo.queueRoomSnapshot(token, "stale room snapshot") }
        h.change("old pending", 11)
        h.synced(11)
        assertTrue(h.repo.view().synced)
        assertTrue(h.repo.view().pending.isEmpty())
    }

    @Test fun newerConnectionStateImmediatelyFencesPreviousHelloBeforeNewHelloArrives() {
        val h = Harness().online()
        h.repo.onEvent(h.owner, ConnectionEvent.State(2, ConnectionPhase.AUTHENTICATING))
        val before = h.repo.view()
        assertFalse(before.synced)
        assertTrue(h.hello().isEmpty())
        h.change("old connection", 999)
        assertEquals(before, h.repo.view())
        h.generation = 2
        assertSync(10, h.hello())
    }

    @Test fun unrelatedAckNackAndSendResultDoNotDiscardCurrentPendingOrStopReadyState() {
        val h = Harness().online()
        h.repo.queueRoomSnapshot(h.room(), "pending")
        val put = h.put()
        val before = h.repo.view()
        h.event(ServerFrame.Ack(FrameId.Text(put.id.toString()), PATH, 11, documentHash(put.content), false))
        h.event(ServerFrame.Ack(FrameId.Integer(put.id + 1), PATH, 11, documentHash(put.content), false))
        h.event(ServerFrame.Ack(FrameId.Integer(put.id), "other.md", 11, documentHash(put.content), false))
        h.event(ServerFrame.Nack(FrameId.Integer(put.id + 1), PATH, version("unrelated", 12)))
        h.repo.onSendResult(h.owner, h.generation, put.id + 1, SendResult.NOT_READY)
        assertEquals(before, h.repo.view())
    }

    @Test fun roomRequiresCurrentOwnerGenerationEpochSeedAndScribe() {
        val h = Harness().online()
        h.joined(seeded = false)
        expectFailure(DocumentFailure.STALE_ROOM) { h.repo.confirmRoomReady(h.owner, h.generation, SID, EPOCH) }
        h.joined(scribe = false)
        expectFailure(DocumentFailure.NOT_SCRIBE) { h.repo.confirmRoomReady(h.owner, h.generation, SID, EPOCH) }
        h.joined()
        expectFailure(DocumentFailure.STALE_ROOM) { h.repo.confirmRoomReady(h.owner, h.generation, SID, "old-epoch") }
        expectFailure(DocumentFailure.STALE_ROOM) { h.repo.confirmRoomReady(h.owner + 1, h.generation, SID, EPOCH) }
        expectFailure(DocumentFailure.STALE_ROOM) { h.repo.confirmRoomReady(h.owner, h.generation + 1, SID, EPOCH) }
        val token = h.repo.confirmRoomReady(h.owner, h.generation, SID, EPOCH)
        h.repo.retireRoom(h.scope, SID)
        expectFailure(DocumentFailure.STALE_ROOM) { h.repo.queueRoomSnapshot(token, "retired") }
        val newToken = h.room(epoch = "new-incarnation")
        expectFailure(DocumentFailure.STALE_ROOM) { h.repo.queueRoomSnapshot(token, "old incarnation") }
        h.repo.queueRoomSnapshot(newToken, "current incarnation")
        assertEquals("current incarnation", h.put().content)
    }

    @Test fun incomingTombstoneCannotBeWrittenThroughPreviouslyReadyRoom() {
        val h = Harness().online()
        val token = h.room()
        h.tombstone(11)
        expectFailure(DocumentFailure.STALE_ROOM) { h.repo.queueRoomSnapshot(token, "late editor after delete") }
        assertNull(h.repo.nextWrite(h.scope, PATH))
        assertTrue(h.repo.view().documents.getValue(PATH).deleted)
    }

    @Test fun incomingRecreateCannotReuseRoomBoundToDeletedDocumentIncarnation() {
        val h = Harness().online()
        val token = h.room()
        h.tombstone(11)
        h.change("replacement incarnation", 12)
        expectFailure(DocumentFailure.STALE_ROOM) { h.repo.queueRoomSnapshot(token, "old incarnation") }
        assertNull(h.repo.nextWrite(h.scope, PATH))
        val fresh = h.room(sid = "new-room", epoch = "new-epoch")
        h.repo.queueRoomSnapshot(fresh, "new merged incarnation")
        val put = h.put()
        assertEquals(12L, put.baseRev)
        assertEquals("new-room", put.live)
    }

    @Test fun failedSaveDraftKeepsPreviousDurableGenerationAndNeverArmsOriginal() {
        val h = Harness().online()
        val old = h.repo.saveDraft(h.scope, PATH, "durable old")
        h.storage.failJournalAfter = 0
        expectStorageFailure { h.repo.saveDraft(h.scope, PATH, "must not appear") }
        assertEquals(old, h.repo.view().drafts.getValue(PATH).generation)
        assertEquals("durable old", h.repo.view().drafts.getValue(PATH).content)
        assertNull(h.repo.nextWrite(h.scope, PATH))
        val restarted = Harness(h.storage, generation = 20)
        restarted.hello()
        assertEquals(h.repo.view().drafts, restarted.repo.view().drafts)
    }

    @Test fun nextWritePersistsImmutablePendingBeforeReturningSendAndFailureReturnsNoSend() {
        val h = Harness().online()
        val generation = h.repo.queueRoomSnapshot(h.room(), "ready to send")
        h.storage.failJournalAfter = 0
        expectStorageFailure { h.repo.nextWrite(h.scope, PATH) }
        assertTrue(h.repo.view().pending.isEmpty())
        assertEquals(generation, h.repo.view().drafts.getValue(PATH).generation)
        val put = h.put()
        val restarted = Harness(h.storage, generation = 20)
        restarted.hello()
        val pending = restarted.repo.view().pending.getValue(PATH)
        assertEquals(put.id, pending.id)
        assertEquals(put.content, pending.content)
        assertEquals(put.baseRev, pending.baseRev)
        assertEquals(put.live, pending.live)
        assertEquals(generation, pending.generation)
        assertEquals(h.generation, pending.connectionGeneration)
        assertNull(restarted.repo.nextWrite(restarted.scope, PATH))
    }

    @Test fun failedAckCacheCommitLeavesPendingDurableForHashRecovery() {
        val h = Harness().online()
        h.repo.queueRoomSnapshot(h.room(), "accepted remotely")
        val put = h.put()
        h.storage.failCacheAfter = 0
        expectStorageFailure { h.ack(put, 11) }
        assertEquals("base", h.repo.view().documents.getValue(PATH).content)
        assertTrue(h.repo.view().pending.containsKey(PATH))
        assertTrue(h.repo.view().drafts.containsKey(PATH))
        val restarted = Harness(h.storage, generation = 20)
        assertSync(10, restarted.hello())
        restarted.change(put.content, 11)
        restarted.synced(11)
        assertTrue(restarted.repo.view().pending.isEmpty())
        assertTrue(restarted.repo.view().drafts.isEmpty())
        assertTrue(restarted.repo.view().recoveries.isEmpty())
    }

    @Test fun failedAckJournalRemovalAfterCacheCommitResolvesByCachedHashOnRestart() {
        val h = Harness().online()
        h.repo.queueRoomSnapshot(h.room(), "accepted remotely")
        val put = h.put()
        h.storage.failJournalAfter = 0
        expectStorageFailure { h.ack(put, 11) }
        assertEquals(put.content, h.repo.view().documents.getValue(PATH).content)
        assertTrue(h.repo.view().pending.containsKey(PATH))
        val restarted = Harness(h.storage, generation = 20)
        assertSync(10, restarted.hello())
        assertEquals(put.content, restarted.repo.view().documents.getValue(PATH).content)
        restarted.synced(11)
        assertTrue(restarted.repo.view().pending.isEmpty())
        assertTrue(restarted.repo.view().drafts.isEmpty())
        assertTrue(restarted.repo.view().recoveries.isEmpty())
        assertNull(restarted.repo.nextWrite(restarted.scope, PATH))
    }

    @Test fun failedConflictCopyCommitKeepsOriginalDraftAndDoesNotOfferNetworkWrite() {
        val h = Harness().online()
        val generation = h.repo.saveDraft(h.scope, PATH, "local original")
        h.storage.failJournalAfter = 0
        expectStorageFailure { h.repo.makeConflictCopy(h.scope, PATH) }
        assertEquals(generation, h.repo.view().drafts.getValue(PATH).generation)
        assertEquals("local original", h.repo.view().drafts.getValue(PATH).content)
        assertTrue(h.repo.view().recoveries.isEmpty())
        assertTrue(h.repo.view().pending.isEmpty())
        assertNull(h.repo.nextWrite(h.scope, PATH))
        val restarted = Harness(h.storage, generation = 20)
        restarted.hello()
        assertEquals(h.repo.view().drafts, restarted.repo.view().drafts)
    }

    @Test fun failedNackRecoveryCommitCannotLoseEitherLocalGeneration() {
        val h = Harness().online()
        val token = h.room()
        h.repo.queueRoomSnapshot(token, "unknown sent")
        val put = h.put()
        h.repo.queueRoomSnapshot(token, "newer local")
        h.storage.failJournalAfter = 1 // The old pending recovery is durable; saving the newer copy fails.
        expectStorageFailure {
            h.event(ServerFrame.Nack(FrameId.Integer(put.id), PATH, version("concurrent remote", 11)))
        }
        assertEquals("unknown sent", h.repo.view().pending.getValue(PATH).content)
        assertEquals("newer local", h.repo.view().drafts.getValue(PATH).content)
        assertWriteBlocked(h)
        val restarted = Harness(h.storage, generation = 20)
        restarted.hello()
        assertEquals("unknown sent", restarted.repo.view().pending.getValue(PATH).content)
        assertEquals("newer local", restarted.repo.view().drafts.getValue(PATH).content)
        assertTrue(restarted.repo.view().recoveries.any {
            it.content == "unknown sent" && it.remote == version("concurrent remote", 11)
        })
        restarted.change("concurrent remote", 11)
        restarted.synced(11)
        assertEquals(setOf("unknown sent", "newer local"), restarted.repo.view().recoveries.map { it.content }.toSet())
        assertNull(restarted.repo.nextWrite(restarted.scope, PATH))
    }

    @Test fun failedOldSpaceRecoveryCommitDoesNotExposeNewSpaceOrLoseOldDrafts() {
        val h = Harness().online()
        h.repo.saveDraft(h.scope, PATH, "old space local")
        val replaced = Harness(h.storage, spaceId = "replacement-id", generation = 20)
        h.storage.failJournalAfter = 0
        expectStorageFailure { replaced.hello() }
        assertFalse(replaced.repo.view().synced)
        assertWriteBlocked(replaced)
        val original = Harness(h.storage, generation = 30)
        original.hello()
        assertEquals("old space local", original.repo.view().drafts.getValue(PATH).content)
        val retry = Harness(h.storage, spaceId = "replacement-id", generation = 40)
        assertSync(0, retry.hello())
        assertEquals("old space local", retry.repo.view().recoveries.single().content)
        assertEquals(RecoveryReason.SPACE_REPLACED, retry.repo.view().recoveries.single().reason)
    }

    @Test fun coldOfflineSelectionReloadsOnlyExplicitAuthenticatedNamespaceAndNeverArmsOriginal() {
        val h = Harness().online()
        val previousGeneration = h.repo.saveDraft(h.scope, PATH, "checkpoint before exit")
        val namespace = h.repo.view().namespace!!
        val offline = DocumentsRepository(h.storage) { 0L }
        val owner = offline.select(ENDPOINT, namespace)
        val scope = offline.commandScope(owner)
        assertEquals(namespace, offline.view().namespace)
        assertEquals(version("base", 3), offline.view().documents[PATH])
        assertEquals(10L, offline.view().cursor)
        assertFalse(offline.view().synced)
        assertEquals(previousGeneration, offline.view().drafts.getValue(PATH).generation)
        assertNull(offline.nextWrite(scope, PATH))
        val nextGeneration = offline.saveDraft(scope, PATH, "edited without a connection")
        assertTrue(nextGeneration > previousGeneration)
        assertNull(offline.nextWrite(scope, PATH))
        val restarted = Harness(h.storage, generation = 20)
        restarted.hello()
        assertEquals(nextGeneration, restarted.repo.view().drafts.getValue(PATH).generation)
        assertEquals("edited without a connection", restarted.repo.view().drafts.getValue(PATH).content)
        assertNull(restarted.repo.nextWrite(restarted.scope, PATH))
    }

    @Test fun corruptDurableJournalIsNeverTreatedAsEmptyOrOverwritten() {
        val h = Harness().online()
        h.repo.saveDraft(h.scope, PATH, "must remain recoverable")
        val corrupted = "{truncated journal".toByteArray(Charsets.UTF_8)
        h.storage.journals.keys.toList().forEach { h.storage.journals[it] = corrupted.clone() }
        val writesBefore = h.storage.journalWrites
        val restarted = Harness(h.storage, generation = 20)
        expectFailure(DocumentFailure.CORRUPT_JOURNAL) { restarted.hello() }
        assertFalse(restarted.repo.view().synced)
        assertWriteBlocked(restarted)
        expectFailure(DocumentFailure.CORRUPT_JOURNAL) {
            restarted.repo.saveDraft(restarted.scope, PATH, "must not overwrite damaged journal")
        }
        assertEquals(writesBefore, h.storage.journalWrites)
        assertTrue(h.storage.journals.values.all { it.contentEquals(corrupted) })
        val again = Harness(h.storage, generation = 30)
        expectFailure(DocumentFailure.CORRUPT_JOURNAL) { again.hello() }
    }

    @Test fun staleProfileLocalCommandsAndAlreadyReturnedEffectsAreFencedEvenIfConnectionNumberRepeats() {
        val h = Harness().online()
        val oldScope = h.scope // Capture with the old intent, never reacquire after selection changes.
        h.repo.queueRoomSnapshot(h.room(), "old pending")
        val effect = h.repo.nextWrite(oldScope, PATH)!!
        assertEquals(h.owner, effect.owner)
        assertTrue(h.repo.isCurrent(effect))
        val newOwner = h.repo.select(ENDPOINT)
        assertFalse(h.repo.isCurrent(effect))
        val hello = ServerFrame.Hello(1, USER.copy(id = "account-b"), RemoteSpace(SPACE, ENDPOINT.space), 10)
        h.repo.onEvent(newOwner, ConnectionEvent.Frame(h.generation, hello))
        h.repo.onEvent(newOwner, ConnectionEvent.Frame(h.generation, ServerFrame.Synced(10)))
        val before = h.repo.view()
        assertFalse(h.repo.isCurrent(effect))
        expectFailure(DocumentFailure.STALE_ROOM) { h.repo.saveDraft(oldScope, PATH, "late old native edit") }
        expectFailure(DocumentFailure.STALE_ROOM) { h.repo.queueCreate(oldScope, "late.md", "late old create") }
        expectFailure(DocumentFailure.STALE_ROOM) { h.repo.makeConflictCopy(oldScope, PATH) }
        expectFailure(DocumentFailure.STALE_ROOM) { h.repo.nextWrite(oldScope, PATH) }
        expectFailure(DocumentFailure.STALE_ROOM) { h.repo.retireRoom(oldScope, SID) }
        assertEquals(before, h.repo.view())
    }

    @Test fun backoffAndStoppedRetireSameGenerationHelloRatherThanRestartingOldAttempt() {
        for (phase in listOf(ConnectionPhase.BACKOFF, ConnectionPhase.STOPPED)) {
            val h = Harness().online()
            h.repo.onEvent(h.owner, ConnectionEvent.State(h.generation, phase))
            val before = h.repo.view()
            assertFalse(before.synced)
            assertTrue(h.hello(seq = 999).isEmpty())
            h.repo.onEvent(h.owner, ConnectionEvent.State(h.generation, ConnectionPhase.READY))
            assertTrue(h.hello(seq = 999).isEmpty())
            assertTrue(h.event(ServerFrame.Synced(999)).isEmpty())
            assertEquals(before, h.repo.view())
            h.generation++
            assertSync(10, h.hello())
        }
    }

    @Test fun rejectedSingleGenerationMakesExactlyOneRecoveryAndKeepsRemoteSnapshotDurable() {
        val h = Harness().online()
        h.repo.queueRoomSnapshot(h.room(), "rejected local")
        val put = h.put()
        val remote = version("remote winner", 11)
        h.event(ServerFrame.Nack(FrameId.Integer(put.id), PATH, remote))
        val recovery = h.repo.view().recoveries.single()
        assertEquals("rejected local", recovery.content)
        assertEquals(remote, recovery.remote)
        assertEquals(RecoveryReason.REJECTED, recovery.reason)
        assertEquals(recovery.copyPath, h.repo.view().drafts.keys.single())
        assertTrue(h.repo.view().pending.isEmpty())
        val restarted = Harness(h.storage, generation = 20)
        restarted.hello()
        assertEquals(listOf(recovery), restarted.repo.view().recoveries)
        assertEquals(remote, restarted.repo.view().documents[PATH])
    }

    @Test fun invalidAckHashCannotConfirmOrDiscardPendingGeneration() {
        val h = Harness().online()
        h.repo.queueRoomSnapshot(h.room(), "pending")
        val put = h.put()
        expectFailure(DocumentFailure.INVALID_FRAME) {
            h.event(ServerFrame.Ack(FrameId.Integer(put.id), PATH, 11, documentHash("wrong snapshot"), false))
        }
        assertEquals("base", h.repo.view().documents.getValue(PATH).content)
        assertEquals("pending", h.repo.view().pending.getValue(PATH).content)
        assertEquals("pending", h.repo.view().drafts.getValue(PATH).content)
        assertWriteBlocked(h)
        val restarted = Harness(h.storage, generation = 20)
        restarted.hello()
        assertEquals("pending", restarted.repo.view().pending.getValue(PATH).content)
    }

    @Test fun matchingIncomingChangeMustCommitCacheBeforeDiscardingOnlyDurableLocalDraft() {
        val h = Harness().online()
        h.repo.saveDraft(h.scope, PATH, "matches incoming")
        h.storage.failCacheAfter = 0
        expectStorageFailure { h.change("matches incoming", 11) }
        assertEquals("base", h.repo.view().documents.getValue(PATH).content)
        assertEquals("matches incoming", h.repo.view().drafts.getValue(PATH).content)
        val restarted = Harness(h.storage, generation = 20)
        restarted.hello()
        assertEquals("matches incoming", restarted.repo.view().drafts.getValue(PATH).content)
        restarted.change("matches incoming", 11)
        restarted.synced(11)
        assertEquals(version("matches incoming", 11), restarted.repo.view().documents[PATH])
        assertTrue(restarted.repo.view().drafts.isEmpty())
        assertTrue(restarted.repo.view().recoveries.isEmpty())
        assertNull(restarted.repo.nextWrite(restarted.scope, PATH))
    }

    @Test fun failedNackCacheCommitStillLeavesBothVariantsInDurableRecoveryJournal() {
        val h = Harness().online()
        h.repo.queueRoomSnapshot(h.room(), "rejected local")
        val put = h.put()
        val remote = version("remote winner", 11)
        h.storage.failCacheAfter = 0
        expectStorageFailure { h.event(ServerFrame.Nack(FrameId.Integer(put.id), PATH, remote)) }
        assertEquals("base", h.repo.view().documents.getValue(PATH).content)
        assertWriteBlocked(h)
        val restarted = Harness(h.storage, generation = 20)
        restarted.hello()
        val recovery = restarted.repo.view().recoveries.single()
        assertEquals("rejected local", recovery.content)
        assertEquals(remote, recovery.remote)
        assertEquals("rejected local", restarted.repo.view().drafts.getValue(recovery.copyPath!!).content)
        assertTrue(restarted.repo.view().pending.isEmpty())
        assertNull(restarted.repo.nextWrite(restarted.scope, PATH))
        restarted.change("remote winner", 11)
        restarted.synced(11)
        assertEquals(remote, restarted.repo.view().documents[PATH])
        assertEquals(listOf(recovery), restarted.repo.view().recoveries)
        assertNull(restarted.repo.nextWrite(restarted.scope, PATH))
    }

    @Test fun replacementSpaceInvalidatesCapturedDocumentScopeWithinSameSelection() {
        val h = Harness().online()
        val oldScope = h.scope
        h.repo.saveDraft(oldScope, PATH, "old space intent")
        h.repo.onEvent(h.owner, ConnectionEvent.State(h.generation, ConnectionPhase.BACKOFF))
        h.generation++
        val replacement = ServerFrame.Hello(1, USER, RemoteSpace("replacement-id", ENDPOINT.space), 20)
        assertSync(0, h.event(replacement))
        h.synced(20)
        assertEquals("replacement-id", h.repo.view().namespace!!.spaceId)
        val before = h.repo.view()
        expectFailure(DocumentFailure.STALE_ROOM) { h.repo.saveDraft(oldScope, PATH, "late old edit") }
        expectFailure(DocumentFailure.STALE_ROOM) { h.repo.queueCreate(oldScope, "late.md", "late old create") }
        expectFailure(DocumentFailure.STALE_ROOM) { h.repo.makeConflictCopy(oldScope, PATH) }
        expectFailure(DocumentFailure.STALE_ROOM) { h.repo.nextWrite(oldScope, PATH) }
        expectFailure(DocumentFailure.STALE_ROOM) { h.repo.retireRoom(oldScope, SID) }
        assertEquals(before, h.repo.view())
        assertEquals("old space intent", before.recoveries.single().content)
        // A new intent can explicitly acquire the replacement namespace; the old one cannot.
        val replacementScope = h.scope
        h.repo.queueCreate(replacementScope, "fresh.md", "new space intent")
        val effect = h.repo.nextWrite(replacementScope, "fresh.md")!!
        assertTrue(h.repo.isCurrent(effect))
        assertEquals("new space intent", (effect.frame as ClientFrame.Put).content)
    }

    @Test fun differentAccountHelloRequiresExplicitSelectForOnlineAndOfflineBindings() {
        for (offline in listOf(false, true)) {
            val h = Harness().online()
            h.repo.saveDraft(h.scope, PATH, "account-a draft")
            val repo = if (offline) DocumentsRepository(h.storage) { 0L } else h.repo
            val owner = if (offline) repo.select(ENDPOINT, h.repo.view().namespace!!) else h.owner
            val writesBefore = h.storage.journalWrites
            val otherAccount = ServerFrame.Hello(1, USER.copy(id = "account-b"), RemoteSpace(SPACE, ENDPOINT.space), 20)
            expectFailure(DocumentFailure.INVALID_FRAME) {
                repo.onEvent(owner, ConnectionEvent.Frame(2, otherAccount))
            }
            assertEquals(USER.id, repo.view().namespace!!.accountId)
            assertFalse(repo.view().synced)
            assertEquals(writesBefore, h.storage.journalWrites)
            val restartedA = Harness(h.storage, generation = 30)
            restartedA.hello()
            assertEquals("account-a draft", restartedA.repo.view().drafts.getValue(PATH).content)
            val nextOwner = repo.select(ENDPOINT)
            assertSync(0, repo.onEvent(nextOwner, ConnectionEvent.Frame(3, otherAccount)))
            assertEquals("account-b", repo.view().namespace!!.accountId)
            assertTrue(repo.view().drafts.isEmpty())
            assertTrue(repo.view().recoveries.isEmpty())
        }
    }

    @Test fun returnedLiveEffectCannotReviveAfterRoomSidIsReusedForAnotherIncarnation() {
        val h = Harness().online()
        val scope = h.scope
        val token = h.room()
        h.repo.queueRoomSnapshot(token, "old materialized snapshot")
        val effect = h.repo.nextWrite(scope, PATH)!!
        assertTrue(h.repo.isCurrent(effect))
        h.repo.retireRoom(scope, SID)
        assertFalse(h.repo.isCurrent(effect))
        h.room(sid = SID, epoch = "replacement-room-epoch")
        assertFalse("A new room token/epoch must not reauthorize a previously returned live snapshot", h.repo.isCurrent(effect))
        assertTrue(h.repo.view().pending.containsKey(PATH)) // UNKNOWN remains durable, not replayable.
    }
}
