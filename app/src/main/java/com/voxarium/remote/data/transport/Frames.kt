package com.voxarium.remote.data.transport

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Wire-only values. No room orchestration or persistence (protocol §§3–4). */
data class RemoteUser(val id: String, val name: String, val color: String) {
    val displayName get() = name
    override fun toString() = "RemoteUser(redacted)"
}
data class RemoteSpace(val id: String, val name: String, val storageEpoch: String? = null) {
    val displayName get() = name
    override fun toString() = "RemoteSpace(redacted)"
}
data class RemoteIdentity(val protocol: Long, val user: RemoteUser) {
    override fun toString() = "RemoteIdentity(redacted)"
}
sealed class FrameId {
    data class Integer(val value: Long) : FrameId()
    data class Text(val value: String) : FrameId()
    data object Null : FrameId()
    final override fun toString() = "FrameId(redacted)"
}
data class DocumentVersion(val rev: Long, val hash: String?, val deleted: Boolean, val content: String?) {
    override fun toString() = "DocumentVersion(redacted)"
}
data class RemotePeer(val user: RemoteUser, val connections: Long, val docs: List<String>) {
    override fun toString() = "RemotePeer(redacted)"
}
enum class JoinFailure { MISSING, STALE }
enum class ResetReason { REPLACED, DELETED }

sealed class ServerFrame {
    data class Hello(val protocol: Long, val user: RemoteUser, val space: RemoteSpace, val seq: Long) : ServerFrame()
    data class Change(val path: String, val rev: Long, val hash: String?, val deleted: Boolean, val content: String?, val by: RemoteUser?) : ServerFrame()
    data class Synced(val seq: Long) : ServerFrame()
    data class Ack(val id: FrameId, val path: String, val rev: Long, val hash: String?, val deleted: Boolean) : ServerFrame()
    data class Nack(val id: FrameId, val path: String, val current: DocumentVersion?) : ServerFrame()
    data class Presence(val peers: List<RemotePeer>) : ServerFrame()
    data class JoinFailed(val sid: String, val path: String, val reason: JoinFailure) : ServerFrame()
    data class Joined(val sid: String, val path: String, val epoch: String, val seeded: Boolean, val state: String?, val scribe: Boolean, val dirty: Boolean, val rev: Long) : ServerFrame()
    data class Seeded(val sid: String, val ok: Boolean, val state: String?) : ServerFrame()
    data class Update(val sid: String, val update: String) : ServerFrame()
    data class Awareness(val sid: String, val update: String) : ServerFrame()
    data class PeerJoined(val sid: String) : ServerFrame()
    data class PeerLeft(val sid: String, val clientId: Long) : ServerFrame()
    data class Scribe(val sid: String) : ServerFrame()
    data class Reset(val sid: String, val path: String, val reason: ResetReason) : ServerFrame()
    // Error echoes may be strings or integers even for sid/path. Absent differs from JSON null.
    data class Error(val message: String, val of: String?, val id: FrameId?, val sid: FrameId?, val path: FrameId?) : ServerFrame()
    final override fun toString() = "ServerFrame.${javaClass.simpleName}(redacted)"
}
sealed class ClientFrame {
    data class Sync(val since: Long) : ClientFrame()
    data class Put(val id: Long, val path: String, val content: String, val baseRev: Long, val live: String? = null) : ClientFrame()
    data class Delete(val id: Long, val path: String, val baseRev: Long) : ClientFrame()
    data class Join(val sid: String, val path: String, val clientId: Long, val baseRev: Long) : ClientFrame()
    data class Seed(val sid: String, val update: String) : ClientFrame()
    data class Update(val sid: String, val update: String) : ClientFrame()
    data class Awareness(val sid: String, val update: String) : ClientFrame()
    data class Leave(val sid: String) : ClientFrame()
    final override fun toString() = "ClientFrame.${javaClass.simpleName}(redacted)"
}
class FrameValidationException : IllegalArgumentException("Invalid Remote frame")

object FrameCodec {
    /** Legacy fixture/import version; production sockets use LIVE_PROTOCOL_VERSION only. */
    const val PROTOCOL_VERSION = 1
    const val LIVE_PROTOCOL_VERSION = 2
    const val MAX_DOC_BYTES = 1_000_000
    const val MAX_UPDATE_BYTES = 8 * 1024 * 1024
    const val MAX_FRAME_BYTES = 32 * 1024 * 1024
    const val MAX_ME_BYTES = 64 * 1024
    const val MAX_INTEGER = 9_007_199_254_740_991L

    private fun bad(): Nothing = throw FrameValidationException()
    private fun number(v: Any?): Long = when (v) {
        is Int -> v.toLong()
        is Long -> v
        else -> bad()
    }.also { if (it !in 0..MAX_INTEGER) bad() }
    private fun str(v: Any?): String = (v as? String ?: bad()).also(::unicode)
    private fun unicode(v: String) {
        var i = 0
        while (i < v.length) {
            val c = v[i++]
            if (c.isHighSurrogate()) {
                if (i == v.length || !v[i++].isLowSurrogate()) bad()
            } else if (c.isLowSurrogate()) bad()
        }
    }
    private fun bool(v: Any?): Boolean = v as? Boolean ?: bad()
    private fun obj(v: Any?): JSONObject = v as? JSONObject ?: bad()
    private fun field(o: JSONObject, k: String): Any = if (o.has(k)) o.get(k) else bad()
    private fun s(o: JSONObject, k: String) = str(field(o,k))
    private fun n(o: JSONObject, k: String) = number(field(o,k))
    private fun b(o: JSONObject, k: String) = bool(field(o,k))
    private fun nullable(o: JSONObject, k: String): Any? = field(o,k).let { if (it === JSONObject.NULL) null else it }
    private fun short(v: String, max: Int = 256): String = v.also { if (it.isEmpty() || it.length > max || it.any { c -> c < ' ' }) bad() }
    private fun sid(o: JSONObject) = short(s(o,"sid"),64)
    private fun path(v: String): String {
        val p = v.trim()
        unicode(p)
        if (p.isEmpty() || p.codePointCount(0,p.length) > 512 || p.startsWith('/') || '\\' in p || p.any { it < ' ' } || p.split('/').any { it.isEmpty() || it == "." || it == ".." } || p.substringAfterLast('/').lastIndexOf('.') <= 0 || p.substringAfterLast('.',"").lowercase(Locale.ROOT) !in listOf("md","markdown","txt")) bad()
        return p
    }
    private fun content(v: String): String = v.also { unicode(it); if (it.toByteArray(Charsets.UTF_8).size > MAX_DOC_BYTES) bad() }
    private fun hash(v: Any?): String? = v?.let { str(it).also { h -> if (!Regex("[0-9a-f]{64}").matches(h)) bad() } }
    private fun update(v: String): String {
        if (v.length > ((MAX_UPDATE_BYTES + 2) / 3) * 4 || v.length % 4 != 0) bad()
        val padding = v.takeLast(2).count { it == '=' }
        for (i in v.indices) {
            val c = v[i]
            if (i >= v.length - padding) { if(c != '=') bad() }
            else if (c !in 'A'..'Z' && c !in 'a'..'z' && c !in '0'..'9' && c != '+' && c != '/') bad()
        }
        val size = v.length / 4 * 3 - v.takeLast(2).count { it == '=' }
        if (size > MAX_UPDATE_BYTES) bad()
        return v
    }
    private fun user(v: Any?, live: Boolean = false): RemoteUser = obj(v).let {
        val id = short(s(it,"id")); val name = s(it,if(live) "display_name" else "name"); val color = s(it,"color")
        if(live && (id.any { c -> Character.isISOControl(c) } || name.isEmpty() || name.length > 1024 || !Regex("#[0-9a-fA-F]{6}").matches(color))) bad()
        RemoteUser(id,name,color)
    }
    private fun projectId(v: String) = v.also { if(!Regex("[a-z0-9][a-z0-9._-]{0,63}").matches(it)) bad() }
    private fun storageEpoch(v: String) = v.also { if(!Regex("[0-9a-f]{32}").matches(it)) bad() }
    private fun id(v: Any?): FrameId = when(v) {
        null, JSONObject.NULL -> FrameId.Null
        is String -> FrameId.Text(v)
        else -> FrameId.Integer(number(v))
    }
    private fun optionalId(o: JSONObject,k: String): FrameId? = if(o.has(k)) id(o.get(k)) else null
    private fun version(o: JSONObject): DocumentVersion {
        val d = b(o,"deleted"); val h = hash(nullable(o,"hash")); val c = nullable(o,"content")?.let { content(str(it)) }
        if ((d && (h != null || c != null)) || (!d && (h == null || c == null))) bad()
        return DocumentVersion(n(o,"rev"),h,d,c)
    }
    private fun array(v: Any?): List<Any> = (v as? JSONArray ?: bad()).let { a -> (0 until a.length()).map { a.get(it) } }

    /** /me uses the same strict grammar, integer range and user validation as hello.
     * Version mismatches are preserved for the connection layer to diagnose. */
    fun decodeMe(text: String): RemoteIdentity = decodeMeFor(text, false)
    fun decodeMeV2(text: String): RemoteIdentity = decodeMeFor(text, true)
    private fun decodeMeFor(text: String, live: Boolean): RemoteIdentity = try {
        if (text.length > MAX_ME_BYTES || text.toByteArray(Charsets.UTF_8).size > MAX_ME_BYTES) bad()
        StrictJson(text).check()
        val o = JSONObject(text)
        RemoteIdentity(n(o,"protocol"), user(field(o,"user"),live))
    } catch (_: Exception) { throw FrameValidationException() }

    /** Explicit legacy fixture decoder. Never used by production transport. */
    fun decode(text: String): ServerFrame = decodeFor(text, false)
    fun decodeV2(text: String): ServerFrame = decodeFor(text, true)
    private fun decodeFor(text: String, live: Boolean): ServerFrame = try {
        if (text.length > MAX_FRAME_BYTES || text.toByteArray(Charsets.UTF_8).size > MAX_FRAME_BYTES) bad()
        StrictJson(text).check()
        val o = JSONObject(text)
        when(s(o,"t")) {
            "hello" -> ServerFrame.Hello(n(o,"protocol"),user(field(o,"user"),live),obj(field(o,"space")).let { if(live) RemoteSpace(projectId(s(it,"id")),s(it,"display_name"),storageEpoch(s(it,"storage_epoch"))) else RemoteSpace(short(s(it,"id")),s(it,"name")) },n(o,"seq"))
            "change" -> version(o).let { ServerFrame.Change(path(s(o,"path")),it.rev,it.hash,it.deleted,it.content,nullable(o,"by")?.let { user(it,live) }) }
            "synced" -> ServerFrame.Synced(n(o,"seq"))
            "ack" -> {
                val d=b(o,"deleted"); val h=hash(nullable(o,"hash")); if (d != (h == null)) bad()
                ServerFrame.Ack(id(field(o,"id")),path(s(o,"path")),n(o,"rev"),h,d)
            }
            "nack" -> ServerFrame.Nack(id(field(o,"id")),path(s(o,"path")),nullable(o,"current")?.let { version(obj(it)) })
            "presence" -> ServerFrame.Presence(array(field(o,"peers")).map { obj(it).let { p -> RemotePeer(user(field(p,"user"),live),n(p,"connections"),array(field(p,"docs")).map { d -> path(str(d)) }) } })
            "join_failed" -> ServerFrame.JoinFailed(sid(o),path(s(o,"path")),when(s(o,"reason")){"missing"->JoinFailure.MISSING;"stale"->JoinFailure.STALE;else->bad()})
            "joined" -> {
                val seeded=b(o,"seeded"); val state=nullable(o,"state")?.let { update(str(it)) }; if(seeded != (state != null)) bad()
                ServerFrame.Joined(sid(o),path(s(o,"path")),short(s(o,"epoch")),seeded,state,b(o,"scribe"),b(o,"dirty"),n(o,"rev"))
            }
            "seeded" -> {
                val ok=b(o,"ok");val state=nullable(o,"state")?.let { update(str(it)) }; if(ok == (state != null)) bad()
                ServerFrame.Seeded(sid(o),ok,state)
            }
            "update" -> ServerFrame.Update(sid(o),update(s(o,"update")))
            "awareness" -> ServerFrame.Awareness(sid(o),update(s(o,"update")))
            "peer_joined" -> ServerFrame.PeerJoined(sid(o))
            "peer_left" -> ServerFrame.PeerLeft(sid(o),n(o,"client_id"))
            "scribe" -> ServerFrame.Scribe(sid(o))
            "reset" -> ServerFrame.Reset(sid(o),path(s(o,"path")),when(s(o,"reason")){"replaced"->ResetReason.REPLACED;"deleted"->ResetReason.DELETED;else->bad()})
            "error" -> ServerFrame.Error(s(o,"message"),if(o.has("of")) s(o,"of") else null,optionalId(o,"id"),optionalId(o,"sid"),optionalId(o,"path"))
            else -> bad()
        }
    } catch (_: Exception) { throw FrameValidationException() }

    fun encode(frame: ClientFrame): String {
        val o=JSONObject()
        fun set(k:String,v:Any) { o.put(k,v) }
        fun base(t:String) { set("t",t) }
        fun session(t:String,s:String) { base(t);set("sid",short(s,64)) }
        fun payload(t:String,s:String,u:String) { session(t,s);set("update",update(u)) }
        when(frame) {
            is ClientFrame.Sync -> {base("sync");set("since",number(frame.since))}
            is ClientFrame.Put -> {base("put");set("id",number(frame.id));set("path",path(frame.path));set("content",content(frame.content));set("base_rev",number(frame.baseRev));frame.live?.let {set("live",short(it,64))}}
            is ClientFrame.Delete -> {base("delete");set("id",number(frame.id));set("path",path(frame.path));set("base_rev",number(frame.baseRev))}
            is ClientFrame.Join -> {session("join",frame.sid);set("path",path(frame.path));set("client_id",number(frame.clientId));set("base_rev",number(frame.baseRev))}
            is ClientFrame.Seed -> payload("seed",frame.sid,frame.update)
            is ClientFrame.Update -> payload("update",frame.sid,frame.update)
            is ClientFrame.Awareness -> payload("awareness",frame.sid,frame.update)
            is ClientFrame.Leave -> session("leave",frame.sid)
        }
        return o.toString()
    }

    /** Android's JSONTokener accepts non-JSON extensions. Validate grammar before parsing. */
    private class StrictJson(val text: String) {
        var i=0
        fun ws() { while(i<text.length && text[i] in " \t\r\n") i++ }
        fun take(c:Char) { ws();if(i>=text.length || text[i++]!=c) bad() }
        fun string(): String {
            ws();val start=i;take('"')
            while(i<text.length) {
                val c=text[i++]
                if(c=='"') return JSONObject("{\"k\":"+text.substring(start,i)+"}").getString("k")
                if(c<' ') bad()
                if(c=='\\') {
                    if(i>=text.length) bad()
                    when(text[i++]) {
                        '"','\\','/','b','f','n','r','t' -> Unit
                        'u' -> repeat(4) { if(i>=text.length || text[i++] !in "0123456789abcdefABCDEF") bad() }
                        else -> bad()
                    }
                }
            };bad()
        }
        fun value(depth:Int) {
            if(depth>64) bad();ws();if(i>=text.length) bad()
            when(text[i]) {
                '{' -> {i++;ws();val keys=HashSet<String>();if(i<text.length && text[i]=='}') {i++;return};while(true){if(!keys.add(string())) bad();take(':');value(depth+1);ws();if(i<text.length && text[i]=='}'){i++;return};take(',')}}
                '[' -> {i++;ws();if(i<text.length && text[i]==']'){i++;return};while(true){value(depth+1);ws();if(i<text.length && text[i]==']'){i++;return};take(',')}}
                '"' -> {string()}
                else -> {val start=i;while(i<text.length && text[i] !in " \t\r\n,]}") i++;val token=text.substring(start,i);if(token !in listOf("true","false","null") && !Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?").matches(token)) bad()}
            }
        }
        fun check() {ws();if(i>=text.length || text[i]!='{') bad();value(0);ws();if(i!=text.length) bad()}
    }
}
