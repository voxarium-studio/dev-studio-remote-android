package com.voxarium.remote.ui.editor.bridge

import com.voxarium.remote.data.room.*
import org.json.JSONArray
import org.json.JSONObject

/** VS-77 / protocol §9,13: credentials and network addresses never cross this seam.
 * Stateful, single-thread confined. Invalid input never consumes an outstanding reply. */
class RoomEditorCodec {
    private var context: EditorContext? = null
    private var lastRequest = -1L
    private var lastDocument = -1L
    private var lastConnection = -1L
    private val outstanding = linkedMapOf<Long, String>()
    private var blocked=false

    fun encode(command: EditorCommand): String {
        val c = command.context
        safe(c.documentGeneration); safe(c.connectionGeneration); safe(command.requestId)
        require(c.sid.isNotEmpty() && c.sid.length <= 256)
        unicode(c.sid)
        require(command.requestId > lastRequest) { "Stale editor request" }
        if (command is EditorCommand.Prepare) {
            require(context == null && c.documentGeneration > lastDocument && c.connectionGeneration >= lastConnection)
        } else require(context == c) { "Editor context mismatch" }
        require(outstanding.size < MAX_OUTSTANDING) { "Editor reply queue full" }
        val o = envelope(c).put("requestId", command.requestId)
        var reply: String? = null
        when (command) {
            is EditorCommand.Prepare -> { o.put("command", "prepare").put("markdown", markdown(command.markdown)); reply = "prepared" }
            is EditorCommand.Initialize -> { o.put("command", "initialize").put("markdown", markdown(command.markdown)).put("state", base64(command.state)).put("readOnly", command.readOnly); reply = "ready" }
            is EditorCommand.ApplyUpdate -> { o.put("command", "applyUpdate").put("update", base64(command.update)); reply = "applied" }
            is EditorCommand.Awareness -> o.put("command", "awareness").put("update", base64(command.update))
            is EditorCommand.PeerLeft -> o.put("command", "peerLeft").put("clientId", safe(command.clientId))
            is EditorCommand.PublishAwareness -> o.put("command", "publishAwareness")
            is EditorCommand.Flush -> { o.put("command", "flush"); reply = "flushed" }
            is EditorCommand.Dispose -> { o.put("command", "dispose"); reply = "disposed" }
        }
        val json = o.toString()
        if (command is EditorCommand.Prepare) {
            context = c; lastDocument = c.documentGeneration; lastConnection = c.connectionGeneration;blocked=false
        }
        lastRequest = command.requestId
        if (reply != null) outstanding[command.requestId] = reply
        return json
    }

    fun decode(json: String): EditorEvent {
        val o = StrictEditorJson.parse(json)
        keys(o, "version", "documentGeneration", "connectionGeneration", "sid", "requestId", "event", "payload")
        require(number(o, "version") == 1L) { "Editor schema version mismatch" }
        val c = EditorContext(number(o, "documentGeneration"), number(o, "connectionGeneration"), string(o, "sid"))
        require(c == context) { "Stale editor context" }
        val event = string(o, "event")
        require(!blocked || event == "disposed") { "Editor already blocked" }
        val id = if (o.isNull("requestId")) null else number(o, "requestId")
        val responses = setOf("prepared", "ready", "applied", "flushed", "disposed")
        if (event in responses) require(id != null && outstanding[id] == event) { "Unexpected editor reply" }
        else require(id == null) { "Unsolicited event must have null requestId" }
        val p = o.get("payload") as? JSONObject ?: error("Invalid editor payload")
        val result = when (event) {
            "prepared" -> { keys(p, "clientId", "seed"); EditorEvent.Prepared(c, id!!, number(p, "clientId"), base64(string(p, "seed"))) }
            "ready" -> EditorEvent.Ready(c, id!!, snapshot(p))
            "applied" -> EditorEvent.Applied(c, id!!, snapshot(p))
            "flushed" -> EditorEvent.Flushed(c, id!!, snapshot(p))
            "changed" -> EditorEvent.Changed(c, snapshot(p))
            "localUpdate" -> { keys(p, "update"); EditorEvent.LocalUpdate(c, base64(string(p, "update"))) }
            "awareness" -> { keys(p, "update"); EditorEvent.Awareness(c, base64(string(p, "update"))) }
            "disposed" -> { keys(p); EditorEvent.Disposed(c, id!!) }
            "blocked" -> {
                keys(p, "snapshot", "raw")
                val snap = if (p.isNull("snapshot")) null else snapshot(p.get("snapshot") as? JSONObject ?: error("Invalid snapshot"))
                val raw = p.get("raw") as? JSONArray ?: error("Invalid raw states")
                require(raw.length() <= 3)
                EditorEvent.Blocked(c, snap, (0 until raw.length()).map { base64(raw.get(it) as? String ?: error("Invalid raw state")) })
            }
            else -> error("Unknown editor event")
        }
        if (id != null) outstanding.remove(id)
        if(result is EditorEvent.Blocked) blocked=true
        if (result is EditorEvent.Disposed) { context = null; outstanding.clear() }
        return result
    }

    private fun snapshot(o: JSONObject): RoomSnapshot {
        keys(o, "markdown", "generation")
        return RoomSnapshot(markdown(string(o, "markdown")), number(o, "generation"))
    }

    companion object {
        const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
        const val MAX_BINARY = 8 * 1024 * 1024
        const val MAX_MARKDOWN = 1_000_000
        // Three recovery states plus worst-case JSON escapes for a 1 MB snapshot.
        const val MAX_MESSAGE = 40 * 1024 * 1024
        const val MAX_OUTSTANDING = 256
        fun safe(value: Long): Long { require(value in 0..MAX_SAFE_INTEGER); return value }
        fun markdown(value: String): String {
            require(value.length <= MAX_MARKDOWN); unicode(value)
            require(value.toByteArray(Charsets.UTF_8).size <= MAX_MARKDOWN); return value
        }
        internal fun unicode(value: String) {
            var i = 0
            while (i < value.length) {
                val c = value[i++]
                if (c.isHighSurrogate()) require(i < value.length && value[i++].isLowSurrogate())
                else require(!c.isLowSurrogate())
            }
        }
        fun base64(value: String): String {
            require(value.length <= 4 * ((MAX_BINARY + 2) / 3) && value.length % 4 == 0)
            val padding = when { value.endsWith("==") -> 2; value.endsWith("=") -> 1; else -> 0 }
            require(value.length / 4 * 3 - padding <= MAX_BINARY)
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
            for (i in 0 until value.length - padding) {
                val c = value[i]
                require(c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '+' || c == '/')
            }
            if (padding > 0) {
                val n = alphabet.indexOf(value[value.length - padding - 1])
                require(n >= 0 && (n and if (padding == 2) 15 else 3) == 0)
            }
            return value
        }
        private fun number(o: JSONObject, key: String): Long = safe(o.get(key) as? Long ?: error("Expected integer"))
        private fun string(o: JSONObject, key: String): String = o.get(key) as? String ?: error("Expected string")
        private fun keys(o: JSONObject, vararg expected: String) { require(o.keys().asSequence().toSet() == expected.toSet()) { "Invalid editor fields" } }
        private fun envelope(c: EditorContext) = JSONObject().put("version", 1).put("documentGeneration", c.documentGeneration).put("connectionGeneration", c.connectionGeneration).put("sid", c.sid)
    }
}

/** Android's JSONTokener accepts comments, single quotes and duplicate keys. Do not use it
 * for untrusted messages. This bounded parser admits only RFC 8259 strings and integers. */
internal class StrictEditorJson(private val input: String) {
    private var pos = 0
    private fun whitespace() { while (pos < input.length && input[pos] in " \n\r\t") pos++ }
    private fun take(c: Char) { whitespace(); require(pos < input.length && input[pos++] == c) }
    private fun value(depth: Int): Any {
        require(depth <= 6); whitespace(); require(pos < input.length)
        return when (input[pos]) {
            '{' -> {
                pos++; val o = JSONObject(); whitespace()
                if (pos < input.length && input[pos] == '}') { pos++; o } else {
                    while (true) {
                        whitespace(); val key = string(); require(!o.has(key)); take(':'); o.put(key, value(depth + 1)); whitespace()
                        require(pos < input.length)
                        if (input[pos++] == '}') break
                        require(input[pos - 1] == ',')
                    }; o
                }
            }
            '[' -> {
                pos++; val a = JSONArray(); whitespace()
                if (pos < input.length && input[pos] == ']') { pos++; a } else {
                    while (true) {
                        require(a.length() < 3); a.put(value(depth + 1)); whitespace(); require(pos < input.length)
                        if (input[pos++] == ']') break
                        require(input[pos - 1] == ',')
                    }; a
                }
            }
            '"' -> string()
            'n' -> { require(input.startsWith("null", pos)); pos += 4; JSONObject.NULL }
            else -> {
                val start = pos
                if (input[pos] == '-') pos++
                require(pos < input.length && input[pos] in '0'..'9')
                if (input[pos++] != '0') while (pos < input.length && input[pos] in '0'..'9') pos++
                input.substring(start, pos).toLong()
            }
        }
    }
    private fun string(): String {
        require(pos < input.length && input[pos++] == '"'); val result = StringBuilder()
        while (true) {
            require(pos < input.length)
            val c = input[pos++]
            if (c == '"') break
            require(c >= ' ')
            if (c != '\\') result.append(c) else {
                require(pos < input.length)
                result.append(when (val e = input[pos++]) {
                    '"', '\\', '/' -> e
                    'b' -> '\b'; 'f' -> '\u000c'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                    'u' -> { require(pos + 4 <= input.length); val hex = input.substring(pos, pos + 4); require(hex.all { it in "0123456789abcdefABCDEF" }); pos += 4; hex.toInt(16).toChar() }
                    else -> error("Invalid JSON escape")
                })
            }
        }
        val s = result.toString(); RoomEditorCodec.unicode(s)
        return s
    }
    companion object {
        fun parse(input: String): JSONObject {
            require(input.length <= RoomEditorCodec.MAX_MESSAGE)
            val parser = StrictEditorJson(input)
            val o = parser.value(0) as? JSONObject ?: error("Expected editor object")
            parser.whitespace(); require(parser.pos == input.length); return o
        }
    }
}
