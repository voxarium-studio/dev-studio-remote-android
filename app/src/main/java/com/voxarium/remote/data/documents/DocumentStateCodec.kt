package com.voxarium.remote.data.documents

import com.voxarium.remote.data.transport.*
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import com.voxarium.remote.data.room.RoomRecoveryStore

/** Private, versioned local state, not a new server format. Cache and journal are separate blobs. */
internal object DocumentStateCodec {
    private fun fields(o: JSONObject, vararg expected: String) { require(o.keys().asSequence().toSet() == expected.toSet()) }
    private fun string(o: JSONObject, k: String): String {
        val value = o.get(k) as? String ?: error("string")
        var i = 0
        while (i < value.length) {
            val c = value[i++]
            if (c.isHighSurrogate()) require(i < value.length && value[i++].isLowSurrogate())
            else require(!c.isLowSurrogate())
        }
        return value
    }
    private fun bool(o: JSONObject, k: String) = o.get(k) as? Boolean ?: error("boolean")
    private fun obj(vararg pairs: Pair<String, Any?>) = JSONObject().apply { pairs.forEach { put(it.first, it.second ?: JSONObject.NULL) } }
    private fun version(v: DocumentVersion?): Any = v?.let { obj("rev" to it.rev, "hash" to it.hash, "deleted" to it.deleted, "content" to it.content) } ?: JSONObject.NULL
    private fun version(o: JSONObject, key: String): DocumentVersion? = if (o.isNull(key)) null else version(o.getJSONObject(key))
    private fun version(o: JSONObject): DocumentVersion {
        fields(o,"rev","hash","deleted","content")
        val v = DocumentVersion(num(o,"rev"), if(o.isNull("hash")) null else string(o,"hash"), bool(o,"deleted"), if(o.isNull("content")) null else string(o,"content"))
        // Reuse wire validation and verify hashes, not merely their shape.
        FrameCodec.decode(obj("t" to "change", "path" to "check.md", "rev" to v.rev, "hash" to v.hash, "deleted" to v.deleted, "content" to v.content, "by" to null).toString())
        require(v.deleted || documentHash(v.content!!) == v.hash)
        return v
    }
    private fun num(o: JSONObject,k: String): Long {
        val n = o.get(k); require(n is Int || n is Long)
        return (n as Number).toLong().also { require(it in 0..FrameCodec.MAX_INTEGER) }
    }
    private fun path(p: String, text: String = "") { FrameCodec.encode(ClientFrame.Put(0,p,text,0)); require(p == p.trim()) }
    private fun objects(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it) }
    internal fun strictObject(bytes: ByteArray): JSONObject {
        require(bytes.size <= AtomicDocumentStorage.MAX_PAYLOAD_BYTES)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        RoomRecoveryStore.validateJson(text)
        return JSONObject(text)
    }
    private fun read(bytes: ByteArray) = strictObject(bytes).also { require(num(it,"v") == 1L) }
    /** Read-only staging reservation (android-design §5.2). The migration record is durable
     * BEFORE the first partition blob; metadata may not exist at the crash boundary yet.
     * Unknown/corrupt records never turn a staged queue into a fresh writable partition. */
    fun migrationClaimsPartition(bytes: ByteArray, p: DocumentPartition): Boolean {
        val record=strictObject(bytes)
        fields(record,"v","phase","fingerprint","candidates","blobs")
        require(num(record,"v") == 2L && string(record,"phase") in listOf("PREPARED","COPIED","VERIFIED"))
        require(Regex("[a-f0-9]{64}").matches(string(record,"fingerprint")))
        val candidates=objects(record.getJSONArray("candidates"))
        val identities=candidates.map { c ->
            fields(c,"server","account","space","selectors","archiveOnly","counts")
            require(string(c,"server") == p.canonicalServerUrl && string(c,"account") == p.accountId)
            val space=string(c,"space");require(space.isNotEmpty())
            val selectors=c.getJSONArray("selectors")
            val names=(0 until selectors.length()).map { index ->
                // Reuse strict Unicode/type checking without JSONTokener coercion.
                string(obj("value" to selectors.get(index)),"value")
            }
            require(names.toSet().size == names.size)
            bool(c,"archiveOnly")
            val counts=c.getJSONObject("counts")
            counts.keys().asSequence().forEach { num(counts,it) }
            space
        }
        require(identities.toSet().size == identities.size)
        val blobs=objects(record.getJSONArray("blobs"))
        val keys=blobs.map { d ->
            fields(d,"key","cache","present","length","sha256")
            val key=string(d,"key");require(key.isNotEmpty());bool(d,"cache")
            if(bool(d,"present")) {
                require(num(d,"length") <= AtomicDocumentStorage.MAX_PAYLOAD_BYTES)
                require(Regex("[a-f0-9]{64}").matches(string(d,"sha256")))
            } else require(d.isNull("length") && d.isNull("sha256"))
            key
        }
        require(keys.toSet().size == keys.size)
        return keys.any { it == p.journalKey() || it == p.namespace.storageKey() ||
            it == "partition-metadata.v2:" + p.namespace.storageKey() } ||
            (p.storageEpoch == p.projectId && candidates.any { string(it,"space") == p.projectId && !bool(it,"archiveOnly") })
    }
    fun identity(n: DocumentNamespace): ByteArray {
        require(n.storageEpoch == null) // This is the legacy profile-binding codec, never v2 metadata.
        return obj("v" to 1,"server" to n.server,"account" to n.accountId,"space" to n.spaceId).toString().toByteArray(Charsets.UTF_8)
    }
    fun identity(bytes: ByteArray): DocumentNamespace = read(bytes).let {
        fields(it,"v","server","account","space")
        DocumentNamespace(string(it,"server"),string(it,"account"),string(it,"space")).also { n ->
            require(n.server.isNotEmpty() && n.accountId.isNotEmpty() && n.spaceId.isNotEmpty())
        }
    }
    fun mirror(m: Mirror): ByteArray = obj("v" to 1,"complete" to m.complete,"cursor" to m.cursor,"files" to JSONArray(m.files.map { (p,v) -> obj("path" to p,"value" to version(v)) })).toString().toByteArray(Charsets.UTF_8)
    fun mirror(bytes: ByteArray): Mirror = read(bytes).let { o ->
        fields(o,"v","complete","cursor","files")
        val files = objects(o.getJSONArray("files")).associate { fields(it,"path","value"); val p=string(it,"path");path(p);p to version(it,"value")!! }
        require(files.size == o.getJSONArray("files").length()); Mirror(bool(o,"complete"),num(o,"cursor"),files)
    }
    fun journal(j: Journal): ByteArray = obj("v" to 1,"nextId" to j.nextId,"nextGeneration" to j.nextGeneration,
        "lastSpace" to JSONObject(j.lastSpace),
        "spaces" to JSONArray(j.spaces.map { (space,s) -> obj("space" to space,
            "drafts" to JSONArray(s.drafts.values.map { d -> obj("path" to d.path,"content" to d.content,"generation" to d.generation,"base" to version(d.base),"kind" to d.kind.name) }),
            "pending" to JSONArray(s.pending.values.map { p -> obj("id" to p.id,"path" to p.path,"content" to p.content,"generation" to p.generation,"baseRev" to p.baseRev,"live" to p.live,"connection" to p.connectionGeneration) }),
            "recoveries" to JSONArray(s.recoveries.map { r -> obj("id" to r.id,"space" to r.spaceId,"path" to r.path,"content" to r.content,"generation" to r.generation,"remote" to version(r.remote),"reason" to r.reason.name,"copyPath" to r.copyPath) }))
        })).toString().toByteArray(Charsets.UTF_8)
    fun journal(bytes: ByteArray): Journal = read(bytes).let { o ->
        fields(o,"v","nextId","nextGeneration","lastSpace","spaces")
        val spaces = objects(o.getJSONArray("spaces")).associate { s ->
            fields(s,"space","drafts","pending","recoveries")
            val drafts = objects(s.getJSONArray("drafts")).associate { d ->
                fields(d,"path","content","generation","base","kind")
                val p=string(d,"path");val c=string(d,"content");path(p,c)
                p to DocumentDraft(p,c,num(d,"generation"),version(d,"base"),DraftKind.valueOf(string(d,"kind")))
            }
            val pending = objects(s.getJSONArray("pending")).associate { p ->
                fields(p,"id","path","content","generation","baseRev","live","connection")
                val name=string(p,"path");val c=string(p,"content");path(name,c)
                name to PendingDocumentWrite(num(p,"id"),name,c,num(p,"generation"),num(p,"baseRev"),if(p.isNull("live")) null else string(p,"live"),num(p,"connection"))
            }
            val recovery = objects(s.getJSONArray("recoveries")).map { r ->
                fields(r,"id","space","path","content","generation","remote","reason","copyPath")
                val p=string(r,"path");val c=string(r,"content");path(p,c)
                DocumentRecovery(num(r,"id"),string(r,"space"),p,c,num(r,"generation"),version(r,"remote"),RecoveryReason.valueOf(string(r,"reason")),if(r.isNull("copyPath")) null else string(r,"copyPath").also { path(it) })
            }
            require(drafts.size == s.getJSONArray("drafts").length() && pending.size == s.getJSONArray("pending").length())
            require(recovery.map { it.id }.toSet().size == recovery.size)
            string(s,"space") to SpaceJournal(drafts,pending,recovery)
        }
        require(spaces.size == o.getJSONArray("spaces").length())
        val ls=o.getJSONObject("lastSpace");val last=ls.keys().asSequence().associateWith { string(ls,it) }
        val j=Journal(num(o,"nextId"),num(o,"nextGeneration"),spaces,last)
        require(j.nextId > (spaces.values.flatMap { s -> s.pending.values.map { it.id } + s.recoveries.map { it.id } }.maxOrNull() ?: 0))
        require(j.nextGeneration > (spaces.values.flatMap { s -> s.drafts.values.map { it.generation } + s.pending.values.map { it.generation } + s.recoveries.map { it.generation } }.maxOrNull() ?: 0))
        j
    }
}
