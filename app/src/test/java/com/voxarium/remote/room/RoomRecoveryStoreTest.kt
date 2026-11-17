package com.voxarium.remote.room

import com.voxarium.remote.data.documents.DocumentNamespace
import com.voxarium.remote.data.documents.DocumentStorage
import com.voxarium.remote.data.documents.DocumentStorageException
import com.voxarium.remote.data.room.*
import com.voxarium.remote.data.transport.FrameCodec
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.UUID

/** Tests the durable API/restart boundary; AtomicDocumentStorage owns filesystem atomicity. */
class RoomRecoveryStoreTest {
    private val namespace = DocumentNamespace("https://remote.example/proxy", "account-a", "space-a")
    private val context = EditorContext(3, 9, "sid-old")
    private val snapshot = RoomSnapshot("---\r\nkey: value\r\n---\r\n\r\n# Снимок 😀\r\n", 17)
    private class MemoryStorage : DocumentStorage {
        val drafts = linkedMapOf<String, ByteArray>()
        var failWrite = false
        var failRead = false
        var writes = 0
        override fun readCache(namespace: String): ByteArray? = error("recovery must not use cache")
        override fun writeCache(namespace: String, bytes: ByteArray): Unit = error("recovery must not use cache")
        override fun readDrafts(namespace: String): ByteArray? {
            if (failRead) throw DocumentStorageException(DocumentStorageException.Reason.STORAGE)
            return drafts[namespace]?.clone()
        }
        override fun writeDrafts(namespace: String, bytes: ByteArray) {
            if (failWrite) throw DocumentStorageException(DocumentStorageException.Reason.STORAGE)
            drafts[namespace] = bytes.clone(); writes++
        }
        fun onlyText() = drafts.values.single().toString(Charsets.UTF_8)
        fun replace(text: String) { drafts[drafts.keys.single()] = text.toByteArray(Charsets.UTF_8) }
    }
    private fun append(store: RoomRecoveryStore, raw: List<String> = listOf("AAE=", "//8=")) =
        store.persist(namespace, "folder/note.md", context, snapshot, raw)
    private fun failure(reason: RoomRecoveryFailure, action: () -> Unit) {
        try { action(); fail("expected redacted recovery failure") }
        catch (e: RoomRecoveryException) {
            assertEquals(reason, e.reason)
            assertNull(e.cause)
            assertFalse(e.message!!.contains("note.md"))
            assertFalse(e.message!!.contains("Снимок"))
        }
    }

    @Test fun exactSnapshotsAndRawDiagnosticsSurviveRestartWithoutReplay() {
        val storage = MemoryStorage(); append(RoomRecoveryStore(storage))
        val reopened = RoomRecoveryStore(storage)
        val entry = reopened.list(namespace).single()
        assertEquals(namespace, entry.namespace)
        assertEquals("folder/note.md", entry.path)
        assertEquals(context, entry.context)
        assertEquals(snapshot, entry.snapshot)
        assertEquals(listOf("AAE=", "//8="), entry.raw)
        assertEquals(entry.id, UUID.fromString(entry.id).toString())
        assertEquals(entry, reopened.read(namespace, entry.id))
        assertNull(reopened.read(namespace, UUID.randomUUID().toString()))
        assertEquals(1, storage.writes) // Reads expose diagnostics only; no replay/cleanup side effects.
    }

    @Test fun repeatedCheckpointsAreDistinctAndDoNotOverwriteOrMutateCallerList() {
        val storage = MemoryStorage(); val store = RoomRecoveryStore(storage)
        val raw = mutableListOf("AAE="); append(store, raw); raw[0] = "Ag=="
        append(store, raw)
        val entries = store.list(namespace)
        assertEquals(2, entries.size); assertNotEquals(entries[0].id, entries[1].id)
        assertEquals(listOf("AAE="), entries[0].raw); assertEquals(listOf("Ag=="), entries[1].raw)
        assertEquals(2, RoomRecoveryStore(storage).list(namespace).size)
    }

    @Test fun unknownSchemaBeforeMountCanStoreNullSnapshotAndExactRawState() {
        val storage = MemoryStorage(); val store = RoomRecoveryStore(storage)
        store.persist(namespace, "note.md", context, null, listOf("", "AA=="))
        val entry = RoomRecoveryStore(storage).list(namespace).single()
        assertNull(entry.snapshot); assertEquals(listOf("", "AA=="), entry.raw)
    }

    @Test fun serverAccountSpaceAndLengthPrefixedKeysAreIsolated() {
        val storage = MemoryStorage(); val store = RoomRecoveryStore(storage); append(store)
        val id = store.list(namespace).single().id
        val others = listOf(
            namespace.copy(server = "https://other.example/proxy"),
            namespace.copy(accountId = "account-b"), namespace.copy(spaceId = "space-b"),
            DocumentNamespace("ab", "c", "d"), DocumentNamespace("a", "bc", "d"),
        )
        others.forEach { n ->
            assertTrue(store.list(n).isEmpty()); assertNull(store.read(n, id))
            store.persist(n, "note.md", context, null, emptyList())
        }
        assertEquals(6, storage.drafts.size)
        assertTrue(storage.drafts.keys.all { it.startsWith("16:room-recovery.v1") })
        assertEquals(snapshot, RoomRecoveryStore(storage).read(namespace, id)!!.snapshot)
    }

    @Test fun namespacedPayloadCannotBeCopiedToAnotherAccount() {
        val storage = MemoryStorage(); val store = RoomRecoveryStore(storage); append(store)
        val original = storage.drafts.values.single().clone()
        val other = namespace.copy(accountId = "account-b")
        store.persist(other, "note.md", context, null, emptyList())
        val otherKey = storage.drafts.keys.last(); storage.drafts[otherKey] = original
        failure(RoomRecoveryFailure.CORRUPT) { store.list(other) }
        val before = original.clone()
        failure(RoomRecoveryFailure.CORRUPT) { store.persist(other, "note.md", context, null, emptyList()) }
        assertArrayEquals(before, storage.drafts[otherKey])
    }

    @Test fun writeAndReadFailuresNeverClearExistingRecovery() {
        val storage = MemoryStorage(); val store = RoomRecoveryStore(storage); append(store)
        val before = storage.drafts.values.single().clone()
        storage.failWrite = true
        failure(RoomRecoveryFailure.STORAGE) { append(store) }
        assertArrayEquals(before, storage.drafts.values.single())
        storage.failWrite = false; storage.failRead = true
        failure(RoomRecoveryFailure.STORAGE) { append(store) }
        assertEquals(1, storage.writes)
        storage.failRead = false
        assertEquals(1, RoomRecoveryStore(storage).list(namespace).size)
    }

    @Test fun corruptJournalNeverBecomesEmptyOrGetsReplaced() {
        val storage = MemoryStorage(); val store = RoomRecoveryStore(storage); append(store)
        val valid = storage.onlyText()
        val corrupt = listOf("", "{}", valid.dropLast(1), "$valid trailing", "$valid {}", valid.replaceFirst("\"version\":1", "\"version\":2"))
        corrupt.forEach { text ->
            storage.replace(text)
            failure(RoomRecoveryFailure.CORRUPT) { store.list(namespace) }
            failure(RoomRecoveryFailure.CORRUPT) { append(store) }
            assertEquals(text, storage.onlyText())
        }
        assertEquals(1, storage.writes)
    }

    @Test fun strictGrammarRejectsAndroidJsonExtensionsAndDuplicateEscapedKeys() {
        val storage = MemoryStorage(); val store = RoomRecoveryStore(storage); append(store)
        val valid = storage.onlyText()
        val corrupt = listOf(
            valid.replaceFirst("{", "{\"version\":1,"),
            valid.replaceFirst("{", "{\"vers\\u0069on\":1,"),
            valid.replaceFirst("{", "{/*comment*/"),
            valid.replaceFirst("\"version\"", "'version'"),
            valid.replaceFirst("\"version\"", "version"),
            valid.dropLast(1) + ",}",
            valid.replaceFirst(":1", ":01"),
            valid.replaceFirst(":1", ":+1"),
        )
        corrupt.forEach { storage.replace(it); failure(RoomRecoveryFailure.CORRUPT) { store.list(namespace) } }
    }

    @Test fun strictTypesAndFieldsRejectCoercionUnknownKeysAndMissingNullableField() {
        val storage = MemoryStorage(); val store = RoomRecoveryStore(storage); append(store)
        val valid = storage.onlyText()
        val variants = listOf<(JSONObject) -> Unit>(
            { it.put("version", "1") }, { it.put("unknown", true) },
            { it.getJSONArray("entries").getJSONObject(0).remove("snapshot") },
            { it.getJSONArray("entries").getJSONObject(0).getJSONObject("context").put("documentGeneration", true) },
            { it.getJSONArray("entries").getJSONObject(0).getJSONObject("context").put("documentGeneration", -1) },
            { it.getJSONArray("entries").getJSONObject(0).getJSONObject("context").put("connectionGeneration", FrameCodec.MAX_INTEGER + 1) },
            { it.getJSONArray("entries").getJSONObject(0).getJSONObject("snapshot").put("markdown", 123) },
            { it.getJSONArray("entries").getJSONObject(0).getJSONArray("raw").put(0, JSONObject.NULL) },
            { it.getJSONArray("entries").put(it.getJSONArray("entries").getJSONObject(0)) },
        )
        variants.forEach { modify ->
            val o = JSONObject(valid); modify(o); storage.replace(o.toString())
            failure(RoomRecoveryFailure.CORRUPT) { store.list(namespace) }
        }
        // JSONObject may normalize 1.0 to 1 when writing; exercise the literal explicitly.
        storage.replace(valid.replaceFirst("\"version\":1", "\"version\":1.0"))
        failure(RoomRecoveryFailure.CORRUPT) { store.list(namespace) }
    }

    @Test fun malformedUtf8AndUnpairedUnicodeAreNeverLossilyDecoded() {
        val storage = MemoryStorage(); val store = RoomRecoveryStore(storage); append(store)
        val key = storage.drafts.keys.single(); storage.drafts[key] = byteArrayOf(0xc3.toByte(), 0x28)
        failure(RoomRecoveryFailure.CORRUPT) { store.list(namespace) }
        failure(RoomRecoveryFailure.CORRUPT) { append(store) }
        val fresh = MemoryStorage(); val freshStore = RoomRecoveryStore(fresh)
        failure(RoomRecoveryFailure.INVALID_INPUT) { freshStore.persist(namespace, "note.md", context, RoomSnapshot("\uD800", 0), emptyList()) }
        failure(RoomRecoveryFailure.INVALID_INPUT) { freshStore.persist(namespace.copy(accountId = "\uDC00"), "note.md", context, null, emptyList()) }
        assertTrue(fresh.drafts.isEmpty())
    }

    @Test fun invalidInputsDoNotWriteOrExposePayloads() {
        val storage = MemoryStorage(); val store = RoomRecoveryStore(storage)
        listOf("../note.md", "note.exe", " note.md", "/note.md").forEach { path ->
            failure(RoomRecoveryFailure.INVALID_INPUT) { store.persist(namespace, path, context, snapshot, emptyList()) }
        }
        listOf(context.copy(documentGeneration = -1), context.copy(connectionGeneration = FrameCodec.MAX_INTEGER + 1), context.copy(sid = ""), context.copy(sid = "x".repeat(65))).forEach { c ->
            failure(RoomRecoveryFailure.INVALID_INPUT) { store.persist(namespace, "note.md", c, snapshot, emptyList()) }
        }
        failure(RoomRecoveryFailure.INVALID_INPUT) { store.persist(namespace, "note.md", context, snapshot.copy(generation = -1), emptyList()) }
        failure(RoomRecoveryFailure.INVALID_INPUT) { store.read(namespace, "not-a-uuid") }
        assertTrue(storage.drafts.isEmpty())
        append(store)
        val entry = store.list(namespace).single()
        assertEquals("RoomRecoveryEntry(redacted)", entry.toString())
    }

    @Test fun rawDiagnosticsMustBeCanonicalBoundedBase64() {
        val storage = MemoryStorage(); val store = RoomRecoveryStore(storage)
        listOf("A", "AA=", "AA===", "AA\n=", "AB==", "AAB=", "====", "-AAA", "ĀAAA").forEach { raw ->
            failure(RoomRecoveryFailure.INVALID_INPUT) { append(store, listOf(raw)) }
        }
        failure(RoomRecoveryFailure.INVALID_INPUT) { append(store, List(RoomRecoveryStore.MAX_RAW_ITEMS + 1) { "" }) }
        assertTrue(storage.drafts.isEmpty())
        val exact = Base64.getEncoder().encodeToString(ByteArray(RoomRecoveryStore.MAX_RAW_BYTES))
        append(store, listOf(exact))
        assertEquals(exact, RoomRecoveryStore(storage).list(namespace).single().raw.single())
        val before = storage.drafts.values.single().clone()
        val tooLarge = Base64.getEncoder().encodeToString(ByteArray(RoomRecoveryStore.MAX_RAW_BYTES + 1))
        failure(RoomRecoveryFailure.INVALID_INPUT) { append(store, listOf(tooLarge)) }
        failure(RoomRecoveryFailure.INVALID_INPUT) { append(store, List(4) { exact } + "AA==") }
        assertArrayEquals(before, storage.drafts.values.single())
    }

    @Test fun allBufferedFramesAndApplyingFrameAndWinnerCanBePreserved() {
        val storage = MemoryStorage(); val store = RoomRecoveryStore(storage)
        val raw = List(258) { "AA==" }
        append(store, raw)
        assertEquals(raw, RoomRecoveryStore(storage).list(namespace).single().raw)
        val before = storage.drafts.values.single().clone()
        failure(RoomRecoveryFailure.INVALID_INPUT) { append(store, raw + "AA==") }
        assertArrayEquals(before, storage.drafts.values.single())
    }

    @Test fun fullJournalFailsWithoutEvictingOldEntries() {
        val storage = MemoryStorage(); val store = RoomRecoveryStore(storage)
        repeat(RoomRecoveryStore.MAX_ENTRIES) { store.persist(namespace, "note.md", context, null, emptyList()) }
        val before = storage.drafts.values.single().clone()
        failure(RoomRecoveryFailure.TOO_LARGE) { append(store) }
        assertArrayEquals(before, storage.drafts.values.single())
        assertEquals(RoomRecoveryStore.MAX_ENTRIES, RoomRecoveryStore(storage).list(namespace).size)
    }

    @Test fun oversizedStoredBlobFailsBeforeParsingAndIsNotOverwritten() {
        val storage = MemoryStorage(); val store = RoomRecoveryStore(storage); append(store)
        val key = storage.drafts.keys.single()
        storage.drafts[key] = ByteArray(RoomRecoveryStore.MAX_STORE_BYTES + 1)
        failure(RoomRecoveryFailure.TOO_LARGE) { store.list(namespace) }
        failure(RoomRecoveryFailure.TOO_LARGE) { append(store) }
        assertEquals(RoomRecoveryStore.MAX_STORE_BYTES + 1, storage.drafts.getValue(key).size)
        assertEquals(1, storage.writes)
    }

    @Test fun v2EpochIsPartOfBothKeyAndPayloadWithoutChangingV1Keys() {
        val storage = MemoryStorage(); val store = RoomRecoveryStore(storage)
        val first = namespace.copy(storageEpoch = "epoch-a")
        val second = namespace.copy(storageEpoch = "epoch-b")
        store.persist(first, "note.md", context, null, listOf("AAE=", "//8="))
        store.persist(second, "note.md", context, snapshot, emptyList())
        assertEquals(listOf("AAE=", "//8="), RoomRecoveryStore(storage).list(first).single().raw)
        assertEquals(snapshot, RoomRecoveryStore(storage).list(second).single().snapshot)
        assertTrue(store.list(namespace).isEmpty())
        val firstKey = store.key(first); val secondKey = store.key(second)
        assertNotEquals(firstKey, secondKey)
        storage.drafts[secondKey] = storage.drafts.getValue(firstKey).clone()
        failure(RoomRecoveryFailure.CORRUPT) { store.list(second) }
        assertEquals(first, store.list(first).single().namespace)
    }

    @Test fun byteExactArchiveRetainsRawOnlyEntryAndOriginalContext() {
        val storage = MemoryStorage(); val store = RoomRecoveryStore(storage)
        store.persist(namespace, "note.md", context, null, listOf("", "AAE=", "//8="))
        val before = storage.drafts.values.single().clone()
        assertArrayEquals(before, store.archive(namespace))
        val count = storage.writes
        assertArrayEquals(before, store.archive(namespace))
        assertEquals(count, storage.writes)
    }
}
