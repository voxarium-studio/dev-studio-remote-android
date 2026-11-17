package com.voxarium.remote.data.room

import com.voxarium.remote.data.documents.DocumentNamespace
import com.voxarium.remote.data.documents.DocumentStorage
import com.voxarium.remote.data.documents.DocumentStorageException
import com.voxarium.remote.data.security.validCanonicalIdentityUrl
import com.voxarium.remote.data.security.validIdentityValue
import com.voxarium.remote.data.transport.ClientFrame
import com.voxarium.remote.data.transport.FrameCodec
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

/** Local diagnostics only: never apply these bytes to a new room (protocol §§5,6,8). */
fun interface RoomRecoverySink {
    fun persist(namespace: DocumentNamespace, path: String, context: EditorContext, snapshot: RoomSnapshot?, raw: List<String>)
}

data class RoomRecoveryEntry(
    val id: String,
    val namespace: DocumentNamespace,
    val path: String,
    val context: EditorContext,
    val snapshot: RoomSnapshot?,
    /** Canonical base64 diagnostics, NOT a replay queue or an authorization to write. */
    val raw: List<String>,
) {
    override fun toString() = "RoomRecoveryEntry(redacted)"
}

enum class RoomRecoveryFailure { INVALID_INPUT, CORRUPT, TOO_LARGE, STORAGE }
class RoomRecoveryException(val reason: RoomRecoveryFailure) : IllegalStateException("Room recovery: ${reason.name}")

/** Append-only recovery journal over the existing atomic storage. No eviction/deletion or network IO.
 * Call on the ordered IO worker. DocumentStorage.exclusive protects read/modify/write; custom
 * storage adapters sharing a backing store must share that lock. Interprocess writers are unsupported.
 * Each exact server/account/space gets a distinct, versioned key, separate from Documents journals.
 * Corrupt/oversized/unavailable journals are never replaced with an empty journal.
 */
class RoomRecoveryStore(private val storage: DocumentStorage) : RoomRecoverySink {
    companion object {
        const val MAX_ENTRIES = 128
        // Coordinator: at most 256 buffered frames + one applying frame + winning state.
        const val MAX_RAW_ITEMS = 258
        /** Decoded bytes per canonical base64 diagnostic; decoding/replay is intentionally absent. */
        const val MAX_RAW_BYTES = 8 * 1024 * 1024
        const val MAX_ENTRY_RAW_BYTES = 32 * 1024 * 1024
        const val MAX_STORE_BYTES = 48 * 1024 * 1024
        private const val VERSION = 1
        internal fun validateJson(text: String) = StrictJson(text).check()
    }

    override fun persist(namespace: DocumentNamespace, path: String, context: EditorContext, snapshot: RoomSnapshot?, raw: List<String>) {
        storage.exclusive {
            input { validateNamespace(namespace); validate(path, context, snapshot, raw) }
            val entries = load(namespace)
            if (entries.size >= MAX_ENTRIES) fail(RoomRecoveryFailure.TOO_LARGE)
            var id: String
            do { id = UUID.randomUUID().toString() } while (entries.any { it.id == id })
            val entry = RoomRecoveryEntry(id, namespace, path, context, snapshot, raw.toList())
            val encoded = encode(namespace, entries + entry)
            if (encoded.size > MAX_STORE_BYTES) fail(RoomRecoveryFailure.TOO_LARGE)
            io {
                storage.writeDrafts(key(namespace), encoded)
                if (storage.readDrafts(key(namespace))?.contentEquals(encoded) != true)
                    fail(RoomRecoveryFailure.STORAGE)
            }
        }
    }

    fun list(namespace: DocumentNamespace): List<RoomRecoveryEntry> = storage.exclusive {
        input { validateNamespace(namespace) }
        load(namespace)
    }

    fun read(namespace: DocumentNamespace, id: String): RoomRecoveryEntry? = storage.exclusive {
        input { validateNamespace(namespace); uuid(id) }
        load(namespace).firstOrNull { it.id == id }
    }

    internal fun key(n: DocumentNamespace) = (if (n.storageEpoch == null)
        listOf("room-recovery.v1", n.server, n.accountId, n.spaceId)
        else listOf("room-recovery.v2", n.server, n.accountId, n.spaceId, n.storageEpoch))
        .joinToString("") { "${it.length}:$it" }

    /** Validated byte-exact archive. Migration never decodes or applies raw Yjs diagnostics. */
    internal fun archive(n: DocumentNamespace): ByteArray? = storage.exclusive {
        val bytes = io { storage.readDraftsForMigration(key(n)) } ?: return@exclusive null
        decode(n, bytes) // Full bounds/schema/provenance validation, including raw-only records.
        bytes.clone()
    }

    private fun load(n: DocumentNamespace): List<RoomRecoveryEntry> {
        val bytes = io { storage.readDrafts(key(n)) } ?: return emptyList()
        return decode(n, bytes)
    }

    /** Inventory-only: validates the COMPLETE payload and original namespace before discovery.
     * No lossy decode of raw diagnostics and no storage writes/recovery renames. */
    internal fun inventoryNamespace(bytes: ByteArray): DocumentNamespace {
        val n = try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            require(bytes.size <= MAX_STORE_BYTES)
            StrictJson(text).check()
            val o = JSONObject(text)
            require(number(o, "version") == 1L) // Only the legacy v1 source root is scanned.
            DocumentNamespace(string(o, "server"), string(o, "account"), string(o, "space"))
        } catch (_: Exception) { fail(RoomRecoveryFailure.CORRUPT) }
        if (!validCanonicalIdentityUrl(n.server) || !validIdentityValue(n.accountId)) fail(RoomRecoveryFailure.CORRUPT)
        validateNamespace(n)
        decode(n, bytes)
        return n
    }

    internal fun archiveEntryCount(n: DocumentNamespace, bytes: ByteArray) = decode(n, bytes).size

    private fun decode(n: DocumentNamespace, bytes: ByteArray): List<RoomRecoveryEntry> {
        if (bytes.size > MAX_STORE_BYTES) fail(RoomRecoveryFailure.TOO_LARGE)
        return try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            StrictJson(text).check()
            val root = JSONObject(text)
            if (n.storageEpoch == null) fields(root, "version", "server", "account", "space", "entries")
            else { fields(root, "version", "server", "account", "space", "epoch", "entries"); require(string(root, "epoch") == n.storageEpoch) }
            require(number(root, "version") == (if (n.storageEpoch == null) VERSION.toLong() else 2L))
            require(string(root, "server") == n.server && string(root, "account") == n.accountId && string(root, "space") == n.spaceId)
            val array = root.get("entries") as? JSONArray ?: error("array")
            require(array.length() <= MAX_ENTRIES)
            val seen = hashSetOf<String>()
            (0 until array.length()).map { index ->
                val o = array.get(index) as? JSONObject ?: error("entry")
                fields(o, "id", "path", "context", "snapshot", "raw")
                val id = string(o, "id"); uuid(id); require(seen.add(id))
                val c = o.get("context") as? JSONObject ?: error("context")
                fields(c, "documentGeneration", "connectionGeneration", "sid")
                val context = EditorContext(number(c, "documentGeneration"), number(c, "connectionGeneration"), string(c, "sid"))
                val snapshot = if (o.get("snapshot") === JSONObject.NULL) null else {
                    val s = o.get("snapshot") as? JSONObject ?: error("snapshot")
                    fields(s, "markdown", "generation")
                    RoomSnapshot(string(s, "markdown"), number(s, "generation"))
                }
                val rawArray = o.get("raw") as? JSONArray ?: error("raw")
                require(rawArray.length() <= MAX_RAW_ITEMS)
                val raw = (0 until rawArray.length()).map { rawArray.get(it) as? String ?: error("raw") }
                val path = string(o, "path")
                validate(path, context, snapshot, raw)
                RoomRecoveryEntry(id, n, path, context, snapshot, raw)
            }
        } catch (_: Exception) { fail(RoomRecoveryFailure.CORRUPT) }
    }

    private fun obj(vararg values: Pair<String, Any?>) = JSONObject().apply {
        values.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) }
    }
    private fun encode(n: DocumentNamespace, entries: List<RoomRecoveryEntry>): ByteArray = obj(
        "version" to (if (n.storageEpoch == null) VERSION else 2), "server" to n.server, "account" to n.accountId, "space" to n.spaceId,
        "entries" to JSONArray(entries.map { e -> obj(
            "id" to e.id, "path" to e.path,
            "context" to obj("documentGeneration" to e.context.documentGeneration, "connectionGeneration" to e.context.connectionGeneration, "sid" to e.context.sid),
            "snapshot" to e.snapshot?.let { obj("markdown" to it.markdown, "generation" to it.generation) },
            "raw" to JSONArray(e.raw),
        ) }),
    ).apply { n.storageEpoch?.let { put("epoch", it) } }.toString().toByteArray(Charsets.UTF_8)

    private fun validateNamespace(n: DocumentNamespace) {
        require(n.server.isNotEmpty() && n.server.length <= 16384); unicode(n.server)
        listOfNotNull(n.accountId, n.spaceId, n.storageEpoch).forEach { require(it.isNotEmpty() && it.length <= 4096); unicode(it) }
    }
    private fun validate(path: String, context: EditorContext, snapshot: RoomSnapshot?, raw: List<String>) {
        require(path == path.trim()); unicode(path)
        FrameCodec.encode(ClientFrame.Put(0, path, snapshot?.markdown ?: "", 0))
        require(context.documentGeneration in 0..FrameCodec.MAX_INTEGER && context.connectionGeneration in 0..FrameCodec.MAX_INTEGER)
        require(context.sid.isNotEmpty() && context.sid.length <= 64 && context.sid.none { it < ' ' }); unicode(context.sid)
        snapshot?.let { require(it.generation in 0..FrameCodec.MAX_INTEGER); unicode(it.markdown) }
        require(raw.size <= MAX_RAW_ITEMS)
        var total = 0L
        raw.forEach { total += base64Size(it) }
        require(total <= MAX_ENTRY_RAW_BYTES)
    }
    private fun base64Size(s: String): Int {
        require(s.length <= 4 * ((MAX_RAW_BYTES + 2) / 3) && s.length % 4 == 0)
        val padding = if (s.endsWith("==")) 2 else if (s.endsWith("=")) 1 else 0
        val size = s.length / 4 * 3 - padding
        require(size <= MAX_RAW_BYTES)
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        for (i in 0 until s.length - padding) require(alphabet.indexOf(s[i]) >= 0)
        if (padding != 0) {
            val last = alphabet.indexOf(s[s.length - padding - 1])
            require(last >= 0 && (last and (if (padding == 2) 15 else 3)) == 0)
        }
        return size
    }
    private fun unicode(s: String) {
        var i = 0
        while (i < s.length) {
            val c = s[i++]
            if (c.isHighSurrogate()) require(i < s.length && s[i++].isLowSurrogate())
            else require(!c.isLowSurrogate())
        }
    }
    private fun uuid(s: String) { require(UUID.fromString(s).toString() == s) }
    private fun fields(o: JSONObject, vararg expected: String) { require(o.keys().asSequence().toSet() == expected.toSet()) }
    private fun string(o: JSONObject, key: String) = o.get(key) as? String ?: error("string")
    private fun number(o: JSONObject, key: String): Long {
        val v = o.get(key)
        require(v is Int || v is Long)
        return (v as Number).toLong().also { require(it in 0..FrameCodec.MAX_INTEGER) }
    }
    private fun fail(reason: RoomRecoveryFailure): Nothing = throw RoomRecoveryException(reason)
    private inline fun <T> input(block: () -> T): T = try { block() } catch (_: Exception) { fail(RoomRecoveryFailure.INVALID_INPUT) }
    private inline fun <T> io(block: () -> T): T = try { block() } catch (e: DocumentStorageException) {
        fail(when (e.reason) {
            DocumentStorageException.Reason.CORRUPT -> RoomRecoveryFailure.CORRUPT
            DocumentStorageException.Reason.TOO_LARGE -> RoomRecoveryFailure.TOO_LARGE
            DocumentStorageException.Reason.STORAGE -> RoomRecoveryFailure.STORAGE
        })
    } catch (_: Exception) { fail(RoomRecoveryFailure.STORAGE) }

    /** org.json is permissive on Android: reject extensions, duplicate (also escaped) keys,
     * trailing data and excessive nesting BEFORE JSONObject parsing. No payload in errors. */
    private class StrictJson(private val text: String) {
        private var at = 0
        private fun ws() { while (at < text.length && text[at] in " \t\r\n") at++ }
        private fun take(c: Char): Boolean = if (at < text.length && text[at] == c) { at++; true } else false
        private fun string(): String {
            val start = at
            require(take('"'))
            while (at < text.length) {
                val c = text[at++]
                if (c == '"') return JSONObject("{\"k\":" + text.substring(start, at) + "}").getString("k")
                require(c >= ' ')
                if (c == '\\') {
                    require(at < text.length)
                    val escaped = text[at++]
                    if (escaped == 'u') {
                        repeat(4) { require(at < text.length && text[at++] in "0123456789abcdefABCDEF") }
                    } else require(escaped in "\"\\/bfnrt")
                }
            }
            error("string")
        }
        private fun digits() { val start = at; while (at < text.length && text[at] in '0'..'9') at++; require(at > start) }
        private fun number() {
            take('-')
            if (!take('0')) digits()
            if (take('.')) digits()
            if (take('e') || take('E')) { if (!take('+')) take('-'); digits() }
        }
        private fun value(depth: Int) {
            require(depth <= 12); ws(); require(at < text.length)
            when (text[at]) {
                '{' -> {
                    at++; ws(); val keys = hashSetOf<String>()
                    if (!take('}')) {
                        do { ws(); require(keys.add(string())); ws(); require(take(':')); value(depth + 1); ws() } while (take(','))
                        require(take('}'))
                    }
                }
                '[' -> {
                    at++; ws()
                    if (!take(']')) { do { value(depth + 1); ws() } while (take(',')); require(take(']')) }
                }
                '"' -> { string() }
                't', 'f', 'n' -> {
                    val literal = when (text[at]) { 't' -> "true"; 'f' -> "false"; else -> "null" }
                    require(text.regionMatches(at, literal, 0, literal.length)); at += literal.length
                }
                else -> number()
            }
        }
        fun check() { value(0); ws(); require(at == text.length) }
    }
}
