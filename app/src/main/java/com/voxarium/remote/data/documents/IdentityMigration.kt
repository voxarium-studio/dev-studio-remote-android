package com.voxarium.remote.data.documents

import com.voxarium.remote.data.room.RoomRecoveryStore
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import com.voxarium.remote.data.security.validCanonicalIdentityUrl
import com.voxarium.remote.data.security.validIdentityValue
import com.voxarium.remote.data.security.validStorageEpoch

/** Exact references, never credentials. The real Atomic backend discovers orphan/raw-only
 * room namespaces from validated envelopes automatically; recoverySpaceIds is an optional
 * additive hint for virtual/custom storage adapters, not a completeness precondition on disk. */
data class LegacyDocumentProfile(val profileId: String, val credentialId: String, val selector: String) {
    override fun toString() = "LegacyDocumentProfile(redacted)"
}
data class LegacyDocumentSource(
    val canonicalServerUrl: String, val accountId: String,
    val profiles: List<LegacyDocumentProfile> = emptyList(), val recoverySpaceIds: Set<String> = emptySet(),
) { override fun toString() = "LegacyDocumentSource(redacted)" }
enum class IdentityMigrationPhase { PREPARED, COPIED, VERIFIED, ACTIVATED }
enum class IdentityMigrationFailure { INVALID_INPUT, CORRUPT, SOURCE_CHANGED, DESTINATION_OCCUPIED, UNPROVEN_MAPPING, STORAGE }
class IdentityMigrationException(val reason: IdentityMigrationFailure) : IllegalStateException("Identity migration: ${reason.name}")
data class LegacyPartitionCandidate(val namespace: DocumentNamespace, val selectors: Set<String>, val archiveOnly: Boolean, val counts: Map<String, Int> = emptyMap()) {
    override fun toString() = "LegacyPartitionCandidate(redacted)"
}
data class IdentityMigrationStatus(
    val phase: IdentityMigrationPhase, val sourceFingerprint: String,
    val candidates: List<LegacyPartitionCandidate>, val activated: Set<String>,
) { override fun toString() = "IdentityMigrationStatus(redacted)" }

/** Offline copy-on-write migration (dev spec §16.8; android-design §§5.2–5.3).
 * Explicit invocation only; source is NEVER written/deleted. One ordered owner must quiesce
 * legacy repositories/rooms before calling. Root-level process locks cover the complete operation;
 * multiple Android processes and external backup replacement are not supported.
 *
 * Durable archive/journal/room bytes precede replaceable mirrors. Each commit is resumable;
 * the ONLY activation point is a separate atomic, checksummed activation manifest. There is no
 * network dependency, replay API, or automatic acceptance of candidate identity. External A5
 * verification/catalog UI must supply the explicit accepted tuple to activate().
 */
class IdentityMigration(private val legacy: DocumentStorage, private val destination: DocumentStorage) {
    init { require(legacy !== destination && !(legacy is AtomicDocumentStorage && legacy.sharesRoot(destination))) }
    private data class Blob(val key: String, val cache: Boolean, val bytes: ByteArray?) {
        fun descriptor() = obj("key" to key, "cache" to cache, "present" to (bytes != null), "length" to bytes?.size, "sha256" to bytes?.let(::hash))
    }
    private data class Snapshot(val fingerprint: String, val candidates: List<LegacyPartitionCandidate>, val blobs: List<Blob>)
    private fun key(s: LegacyDocumentSource) = tagged("identity-migration.v2", s.canonicalServerUrl, s.accountId)
    private fun archiveKey(s: LegacyDocumentSource, sourceKey: String) = tagged("legacy-archive.v2", key(s), sourceKey)
    private fun profileKey(s: LegacyDocumentSource, p: LegacyDocumentProfile) =
        tagged("profile-binding.v1", s.canonicalServerUrl, p.selector, p.profileId, p.credentialId)

    /** Prepare/copy/verify only. Restarting this call is idempotent, never an implicit activation. */
    fun resume(source: LegacyDocumentSource): IdentityMigrationStatus = access {
        validate(source)
        val recordKey = key(source)
        var record = readRecord(recordKey)
        val active = activeIds(recordKey)
        // Never reimport stale v1 over v2 edits, even if source changed after activation.
        if (active.isNotEmpty()) {
            if (record == null || record.getString("phase") != IdentityMigrationPhase.VERIFIED.name) fail(IdentityMigrationFailure.CORRUPT)
            return@access status(record, active)
        }
        val snapshot = snapshot(source)
        if (record == null) {
            record = obj("v" to 2, "phase" to IdentityMigrationPhase.PREPARED.name, "fingerprint" to snapshot.fingerprint,
                "candidates" to JSONArray(snapshot.candidates.map { candidate(it) }),
                "blobs" to JSONArray(snapshot.blobs.map { it.descriptor() }))
            // Never claim an unrelated/older v2 partition as this migration's output.
            snapshot.blobs.forEach { if (read(it.key, it.cache) != null) fail(IdentityMigrationFailure.DESTINATION_OCCUPIED) }
            commit(recordKey, record)
        } else {
            if (record.getString("fingerprint") != snapshot.fingerprint) fail(IdentityMigrationFailure.SOURCE_CHANGED)
            val expected = obj("v" to 2, "phase" to record.getString("phase"), "fingerprint" to snapshot.fingerprint,
                "candidates" to JSONArray(snapshot.candidates.map { candidate(it) }), "blobs" to JSONArray(snapshot.blobs.map { it.descriptor() }))
            if (!equivalent(record, expected)) fail(IdentityMigrationFailure.CORRUPT)
        }
        // Snapshot order is contractual: every durable blob BEFORE any cache write.
        for (blob in snapshot.blobs) {
            val before = read(blob.key, blob.cache)
            if (blob.bytes == null) {
                if (before != null) fail(IdentityMigrationFailure.DESTINATION_OCCUPIED)
            } else if (before == null) {
                if (blob.cache) destination.writeCache(blob.key, blob.bytes) else destination.writeDrafts(blob.key, blob.bytes)
            } else if (!before.contentEquals(blob.bytes)) fail(IdentityMigrationFailure.CORRUPT)
            exactReadback(blob)
        }
        if (record.getString("phase") == IdentityMigrationPhase.PREPARED.name) {
            record.put("phase", IdentityMigrationPhase.COPIED.name); commit(recordKey, record)
        }
        verify(record)
        record.put("phase", IdentityMigrationPhase.VERIFIED.name); commit(recordKey, record)
        status(record, emptySet())
    }

    /** Confirmation is exact and optimistic: fingerprint is the one shown with the mapping.
     * Only a normal migration (ID == old UUID, epoch == old UUID) can import cursor/base. Any
     * replacement needs a NEW partition/full reconciliation, never reassignment of this queue. */
    fun activate(source: LegacyDocumentSource, expectedFingerprint: String, accepted: DocumentPartition): IdentityMigrationStatus = access {
        validate(source)
        val recordKey = key(source)
        val record = readRecord(recordKey) ?: fail(IdentityMigrationFailure.UNPROVEN_MAPPING)
        if (record.getString("fingerprint") != expectedFingerprint || record.getString("phase") != IdentityMigrationPhase.VERIFIED.name)
            fail(IdentityMigrationFailure.UNPROVEN_MAPPING)
        val candidate = candidates(record).singleOrNull { it.namespace.spaceId == accepted.projectId }
            ?: fail(IdentityMigrationFailure.UNPROVEN_MAPPING)
        if (candidate.archiveOnly || accepted.canonicalServerUrl != source.canonicalServerUrl || accepted.accountId != source.accountId ||
            accepted.storageEpoch != candidate.namespace.spaceId) fail(IdentityMigrationFailure.UNPROVEN_MAPPING)
        val activation = activations()
        val entries = activation.getJSONObject("sources")
        val existing = if (entries.has(recordKey)) entries.getJSONObject(recordKey) else obj("fingerprint" to expectedFingerprint, "ids" to JSONArray())
        if (existing.getString("fingerprint") != expectedFingerprint) fail(IdentityMigrationFailure.CORRUPT)
        val ids = stringList(existing.getJSONArray("ids")).toSet()
        if (accepted.projectId in ids) return@access status(record, ids)
        // After first activation mutable v2 files may contain new edits; never verify against old
        // hashes or import again. Each not-yet-active partition is checked separately below.
        if (ids.isEmpty()) {
            if (snapshot(source).fingerprint != expectedFingerprint) fail(IdentityMigrationFailure.SOURCE_CHANGED)
            verify(record)
        } else verify(record, accepted)
        existing.put("ids", JSONArray((ids + accepted.projectId).sorted()))
        entries.put(recordKey, existing)
        commit(ACTIVATION_KEY, activation) // single atomic publication; no second commit is required
        status(record, ids + accepted.projectId)
    }

    fun status(source: LegacyDocumentSource): IdentityMigrationStatus? = access {
        validate(source); readRecord(key(source))?.let { status(it, activeIds(key(source))) }
    }

    /** Byte-exact read/copy archive, with original namespace in the room payload. No replay.
     * Access must be explicitly authorized for the old account by the caller, not current UI. */
    fun archivedRoom(source: LegacyDocumentSource, spaceId: String): ByteArray? = access {
        validate(source)
        val record = readRecord(key(source)) ?: return@access null
        if (candidates(record).none { it.namespace.spaceId == spaceId }) fail(IdentityMigrationFailure.INVALID_INPUT)
        val sourceKey = RoomRecoveryStore(legacy).key(DocumentNamespace(source.canonicalServerUrl, source.accountId, spaceId))
        val destKey = archiveKey(source, sourceKey)
        val descriptor = objects(record.getJSONArray("blobs")).singleOrNull { it.getString("key") == destKey } ?: return@access null
        verifiedRead(descriptor)
    }

    fun archivedAccountJournal(source: LegacyDocumentSource): ByteArray? = archive(source,
        DocumentNamespace(source.canonicalServerUrl, source.accountId, "@journal").storageKey())

    fun archivedMirror(source: LegacyDocumentSource, spaceId: String): ByteArray? = archive(source,
        "cache:" + DocumentNamespace(source.canonicalServerUrl, source.accountId, spaceId).storageKey())

    private fun archive(source: LegacyDocumentSource, sourceKey: String): ByteArray? = access {
        validate(source)
        val record = readRecord(key(source)) ?: return@access null
        val descriptor = objects(record.getJSONArray("blobs")).singleOrNull { it.getString("key") == archiveKey(source, sourceKey) }
            ?: return@access null
        verifiedRead(descriptor)
    }

    private fun snapshot(source: LegacyDocumentSource): Snapshot {
        val origin = linkedMapOf<String, ByteArray?>()
        val journalKey = DocumentNamespace(source.canonicalServerUrl, source.accountId, "@journal").storageKey()
        val journalBytes = legacy.readDraftsForMigration(journalKey)
        origin[journalKey] = journalBytes
        val journal = journalBytes?.let { DocumentStateCodec.journal(it) } ?: Journal()
        val discovered = legacy.migrationRoomNamespaces().filter { it.server == source.canonicalServerUrl && it.accountId == source.accountId }
        require(discovered.all { it.storageEpoch == null })
        val namespaces = (journal.spaces.keys + journal.lastSpace.values + source.recoverySpaceIds + discovered.map { it.spaceId }).toMutableSet()
        require(namespaces.size <= AtomicDocumentStorage.MAX_INVENTORY_DIRECTORIES)
        val selectors = mutableMapOf<String, MutableSet<String>>()
        val authenticatedProfiles = mutableMapOf<LegacyDocumentProfile, DocumentNamespace>()
        journal.lastSpace.forEach { (name, id) -> selectors.getOrPut(id) { mutableSetOf() }.add(name) }
        for (profile in source.profiles.sortedWith(compareBy({ it.profileId }, { it.credentialId }, { it.selector }))) {
            val k = profileKey(source, profile); val bytes = legacy.readDraftsForMigration(k); origin[k] = bytes
            if (bytes != null) {
                val n = DocumentStateCodec.identity(bytes)
                if (n.server != source.canonicalServerUrl || n.accountId != source.accountId || n.storageEpoch != null) fail(IdentityMigrationFailure.UNPROVEN_MAPPING)
                authenticatedProfiles[profile] = n
                namespaces.add(n.spaceId)
                selectors.getOrPut(n.spaceId) { mutableSetOf() }.add(profile.selector)
                // A profile/history disagreement archives BOTH IDs, never chooses a winner.
                journal.lastSpace[profile.selector]?.takeIf { it != n.spaceId }?.let { old ->
                    selectors.getOrPut(old) { mutableSetOf() }.add("!ambiguous:" + profile.selector)
                    selectors.getOrPut(n.spaceId) { mutableSetOf() }.add("!ambiguous:" + profile.selector)
                }
            }
        }
        val rooms = RoomRecoveryStore(legacy)
        val mirrors = linkedMapOf<DocumentNamespace, ByteArray?>()
        val result = namespaces.sorted().map { id ->
            val n = DocumentNamespace(source.canonicalServerUrl, source.accountId, id)
            val roomKey = rooms.key(n); origin[roomKey] = rooms.archive(n)
            val roomCount = origin[roomKey]?.let { rooms.archiveEntryCount(n, it) } ?: 0
            val bytes = legacy.readCacheForMigration(n.storageKey()); bytes?.let { DocumentStateCodec.mirror(it) }
            origin["cache:" + n.storageKey()] = bytes; mirrors[n] = bytes
            val names = selectors[id]?.toSet() ?: emptySet()
            val state = journal.spaces[id] ?: SpaceJournal()
            LegacyPartitionCandidate(n, names, names.size != 1 || !validStorageEpoch(id) || names.any { it.startsWith("!ambiguous:") },
                mapOf("drafts" to state.drafts.size, "pending" to state.pending.size, "recoveries" to state.recoveries.size,
                    "files" to (bytes?.let { DocumentStateCodec.mirror(it).files.size } ?: 0), "rooms" to roomCount))
        }
        val durable = origin.map { (k, bytes) -> Blob(archiveKey(source, k), false, bytes) }.toMutableList()
        val caches = mutableListOf<Blob>()
        for (c in result.filterNot { it.archiveOnly }) {
            val n = c.namespace
            val partition = DocumentPartition(n.server, n.spaceId, n.accountId, n.spaceId)
            val old = journal.spaces[n.spaceId] ?: SpaceJournal()
            // Immutable bytes/generations/base/counters/reserved names retained. Live/process
            // authority is revoked; exact original journal remains available in the archive.
            val recovered = old.copy(pending = old.pending.mapValues { (_, p) -> p.copy(live = null, connectionGeneration = 0) })
            val extracted = Journal(journal.nextId, journal.nextGeneration, mapOf(n.spaceId to recovered),
                journal.lastSpace.filterValues { it == n.spaceId })
            durable.add(Blob(partition.journalKey(), false, DocumentStateCodec.journal(extracted)))
            // Legacy room diagnostics stay in the provenance archive, not a live v2 room.
            // Still reserve its empty target slot so activation cannot adopt foreign recovery.
            durable.add(Blob(rooms.key(partition.namespace), false, null))
            val metadata = obj("v" to 2, "server" to n.server, "account" to n.accountId, "project" to n.spaceId,
                "epoch" to n.spaceId, "canonicalizer" to 1,
                "profiles" to JSONArray(source.profiles.filter { authenticatedProfiles[it] == n }.map {
                    obj("profile" to it.profileId, "credential" to it.credentialId, "selector" to it.selector)
                }))
            durable.add(Blob("partition-metadata.v2:" + partition.namespace.storageKey(), false, metadata.toString().toByteArray(Charsets.UTF_8)))
            caches.add(Blob(partition.namespace.storageKey(), true, mirrors[n])) // Reserve ABSENT mirrors too.
        }
        // Include inventory and ABSENCE in the fingerprint, not only extant byte payloads.
        val identity = obj("server" to source.canonicalServerUrl, "account" to source.accountId,
            "profiles" to JSONArray(source.profiles.map { tagged(it.profileId, it.credentialId, it.selector) }.sorted()),
            "recoverySpaces" to JSONArray(source.recoverySpaceIds.sorted()),
            "sources" to JSONArray(origin.toSortedMap().map { (k, bytes) -> obj("key" to k, "sha256" to bytes?.let(::hash), "length" to bytes?.size) }))
        return Snapshot(hash(identity.toString().toByteArray(Charsets.UTF_8)), result, durable + caches)
    }

    private fun validate(s: LegacyDocumentSource) = try {
        require(validCanonicalIdentityUrl(s.canonicalServerUrl) && validIdentityValue(s.accountId))
        require(s.profiles.size <= 4096 && s.recoverySpaceIds.size <= 4096)
        require(s.profiles.map { profileKey(s, it) }.toSet().size == s.profiles.size)
        (s.recoverySpaceIds + s.profiles.flatMap { listOf(it.profileId, it.credentialId, it.selector) })
            .forEach { value ->
                require(value.isNotEmpty() && value.length <= 4096)
                var i = 0
                while (i < value.length) {
                    val c = value[i++]
                    if (c.isHighSurrogate()) require(i < value.length && value[i++].isLowSurrogate())
                    else require(!c.isLowSurrogate())
                }
            }
    } catch (_: IllegalArgumentException) { fail(IdentityMigrationFailure.INVALID_INPUT) }
    private fun candidate(c: LegacyPartitionCandidate) = obj("server" to c.namespace.server, "account" to c.namespace.accountId,
        "space" to c.namespace.spaceId, "selectors" to JSONArray(c.selectors.sorted()), "archiveOnly" to c.archiveOnly, "counts" to JSONObject(c.counts))
    private fun candidates(record: JSONObject) = objects(record.getJSONArray("candidates")).map {
        LegacyPartitionCandidate(DocumentNamespace(it.getString("server"), it.getString("account"), it.getString("space")),
            stringList(it.getJSONArray("selectors")).toSet(), it.getBoolean("archiveOnly"),
            it.getJSONObject("counts").let { counts -> counts.keys().asSequence().associateWith { k -> counts.getInt(k).also { n -> require(n >= 0) } } })
    }
    private fun readRecord(k: String): JSONObject? = destination.readDraftsForMigration(k)?.let { bytes ->
        DocumentStateCodec.strictObject(bytes).also {
            require(it.keys().asSequence().toSet() == setOf("v", "phase", "fingerprint", "candidates", "blobs"))
            require(it.get("v") == 2 && it.getString("phase") in listOf("PREPARED", "COPIED", "VERIFIED"))
            require(Regex("[a-f0-9]{64}").matches(it.getString("fingerprint")))
            require(candidates(it).map { c -> c.namespace }.toSet().size == it.getJSONArray("candidates").length())
            require(objects(it.getJSONArray("blobs")).map { d -> d.getString("key") }.toSet().size == it.getJSONArray("blobs").length())
            objects(it.getJSONArray("blobs")).forEach { d ->
                require(d.keys().asSequence().toSet() == setOf("key", "cache", "present", "length", "sha256"))
                require(d.get("key") is String && d.getString("key").isNotEmpty() && d.get("cache") is Boolean && d.get("present") is Boolean)
                if (d.getBoolean("present")) {
                    require(d.get("length") is Int && d.getInt("length") in 0..AtomicDocumentStorage.MAX_PAYLOAD_BYTES)
                    require(d.get("sha256") is String && Regex("[a-f0-9]{64}").matches(d.getString("sha256")))
                } else require(d.isNull("length") && d.isNull("sha256"))
            }
        }
    }
    private fun activations(): JSONObject = destination.readDraftsForMigration(ACTIVATION_KEY)?.let {
        DocumentStateCodec.strictObject(it).also { o ->
            require(o.keys().asSequence().toSet() == setOf("v", "sources") && o.get("v") == 2)
            o.getJSONObject("sources").keys().asSequence().forEach { k ->
                val r = o.getJSONObject("sources").getJSONObject(k)
                require(r.keys().asSequence().toSet() == setOf("fingerprint", "ids"))
                require(Regex("[a-f0-9]{64}").matches(r.getString("fingerprint")))
                require(stringList(r.getJSONArray("ids")).toSet().size == r.getJSONArray("ids").length())
            }
        }
    } ?: obj("v" to 2, "sources" to JSONObject())
    private fun activeIds(k: String): Set<String> = activations().getJSONObject("sources").let { sources ->
        if (!sources.has(k)) emptySet() else {
            val entry = sources.getJSONObject(k)
            val record = readRecord(k) ?: fail(IdentityMigrationFailure.CORRUPT)
            if (record.getString("fingerprint") != entry.getString("fingerprint")) fail(IdentityMigrationFailure.CORRUPT)
            stringList(entry.getJSONArray("ids")).toSet().also { ids ->
                require(ids.all { id -> candidates(record).any { it.namespace.spaceId == id && !it.archiveOnly } })
            }
        }
    }
    private fun status(r: JSONObject, ids: Set<String>) = IdentityMigrationStatus(
        if (ids.isNotEmpty()) IdentityMigrationPhase.ACTIVATED else IdentityMigrationPhase.valueOf(r.getString("phase")),
        r.getString("fingerprint"), candidates(r), ids)
    private fun read(k: String, cache: Boolean) = if (cache) destination.readCacheForMigration(k) else destination.readDraftsForMigration(k)
    private fun verify(record: JSONObject, only: DocumentPartition? = null) {
        for (d in objects(record.getJSONArray("blobs"))) {
            if (only == null || d.getString("key") in setOf(only.journalKey(), only.namespace.storageKey(), "partition-metadata.v2:" + only.namespace.storageKey(), RoomRecoveryStore(destination).key(only.namespace)) ||
                d.getString("key").startsWith(tagged("legacy-archive.v2"))) verifiedRead(d)
        }
    }
    private fun verifiedRead(d: JSONObject): ByteArray? {
        val bytes = read(d.getString("key"), d.getBoolean("cache"))
        if (!d.getBoolean("present")) {
            if (bytes != null) fail(IdentityMigrationFailure.DESTINATION_OCCUPIED)
            return null
        }
        if (bytes == null || bytes.size != d.getInt("length") || hash(bytes) != d.getString("sha256")) fail(IdentityMigrationFailure.CORRUPT)
        return bytes
    }
    private fun exactReadback(b: Blob) {
        val bytes = read(b.key, b.cache)
        if (if (b.bytes == null) bytes != null else bytes?.contentEquals(b.bytes) != true) fail(IdentityMigrationFailure.STORAGE)
    }
    private fun commit(k: String, o: JSONObject) {
        val bytes = o.toString().toByteArray(Charsets.UTF_8)
        destination.writeDrafts(k, bytes)
        if (destination.readDraftsForMigration(k)?.contentEquals(bytes) != true) fail(IdentityMigrationFailure.STORAGE)
    }
    private fun <T> access(block: () -> T): T = destination.exclusive { legacy.exclusive {
        try { block() }
        catch (e: IdentityMigrationException) { throw IdentityMigrationException(e.reason) }
        catch (e: DocumentStorageException) { fail(if (e.reason == DocumentStorageException.Reason.CORRUPT) IdentityMigrationFailure.CORRUPT else IdentityMigrationFailure.STORAGE) }
        catch (e: com.voxarium.remote.data.room.RoomRecoveryException) { fail(if (e.reason == com.voxarium.remote.data.room.RoomRecoveryFailure.STORAGE) IdentityMigrationFailure.STORAGE else IdentityMigrationFailure.CORRUPT) }
        catch (_: java.nio.charset.CharacterCodingException) { fail(IdentityMigrationFailure.CORRUPT) }
        catch (_: IllegalArgumentException) { fail(IdentityMigrationFailure.CORRUPT) }
        catch (_: org.json.JSONException) { fail(IdentityMigrationFailure.CORRUPT) }
        catch (_: IllegalStateException) { fail(IdentityMigrationFailure.CORRUPT) }
        catch (_: Exception) { fail(IdentityMigrationFailure.STORAGE) }
    } }
    companion object {
        internal const val ACTIVATION_KEY = "identity-activation.v2"
        /** No staged bytes may be presented as an active offline partition. */
        internal fun isActivated(storage: DocumentStorage, p: DocumentPartition): Boolean = storage.exclusive {
            val bytes = storage.readDrafts(ACTIVATION_KEY) ?: return@exclusive false
            val root = DocumentStateCodec.strictObject(bytes)
            require(root.get("v") == 2)
            val k = tagged("identity-migration.v2", p.canonicalServerUrl, p.accountId)
            val sources = root.getJSONObject("sources")
            if (!sources.has(k)) return@exclusive false
            val source = sources.getJSONObject(k)
            val record = storage.readDrafts(k)?.let(DocumentStateCodec::strictObject) ?: error("record")
            require(record.get("v") == 2 && record.getString("phase") == "VERIFIED" && record.getString("fingerprint") == source.getString("fingerprint"))
            p.storageEpoch == p.projectId && p.projectId in stringList(source.getJSONArray("ids")) &&
                objects(record.getJSONArray("candidates")).any {
                    it.getString("space") == p.projectId && it.getString("server") == p.canonicalServerUrl &&
                        it.getString("account") == p.accountId && !it.getBoolean("archiveOnly")
                }
        }
        private fun fail(r: IdentityMigrationFailure): Nothing = throw IdentityMigrationException(r)
        private fun hash(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun tagged(vararg s: String) = s.joinToString("") { "${it.length}:$it" }
        private fun obj(vararg p: Pair<String, Any?>) = JSONObject().apply { p.forEach { put(it.first, it.second ?: JSONObject.NULL) } }
        private fun objects(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it) }
        private fun stringList(a: JSONArray) = (0 until a.length()).map { a.get(it) as? String ?: error("string") }
        private fun equivalent(a: Any, b: Any): Boolean = when {
            a is JSONObject && b is JSONObject -> {
                val keys = a.keys().asSequence().toSet()
                keys == b.keys().asSequence().toSet() && keys.all { equivalent(a.get(it), b.get(it)) }
            }
            a is JSONArray && b is JSONArray -> a.length() == b.length() && (0 until a.length()).all { equivalent(a.get(it), b.get(it)) }
            else -> a == b
        }
    }
}
