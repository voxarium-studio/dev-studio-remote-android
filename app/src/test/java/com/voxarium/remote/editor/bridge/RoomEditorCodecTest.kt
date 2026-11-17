package com.voxarium.remote.editor.bridge

import com.voxarium.remote.data.room.*
import com.voxarium.remote.ui.editor.bridge.RoomEditorCodec
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RoomEditorCodecTest {
    private val context = EditorContext(1, 2, "test-session")
    private fun prepared(codec: RoomEditorCodec, request: Long = 1): String {
        codec.encode(EditorCommand.Prepare(context, request, "hello\n"))
        return event("prepared", request, "{\"clientId\":42,\"seed\":\"AA==\"}")
    }
    private fun event(name: String, id: Long? = null, payload: String = "{}") =
        "{\"version\":1,\"documentGeneration\":1,\"connectionGeneration\":2,\"sid\":\"test-session\",\"requestId\":$id,\"event\":\"$name\",\"payload\":$payload}"
    private fun rejects(block: () -> Unit) { try { block(); fail("Should reject") } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {} }

    @Test fun typedCommandNamesAndFields() {
        val codec = RoomEditorCodec()
        val first = JSONObject(codec.encode(EditorCommand.Prepare(context, 1, "hi")))
        assertEquals("prepare", first.getString("command")); assertFalse(first.has("token")); assertEquals(7, first.length())
        val init = JSONObject(codec.encode(EditorCommand.Initialize(context, 2, "hi", "AA==", true)))
        assertEquals("initialize", init.getString("command")); assertTrue(init.getBoolean("readOnly"))
        val commands = listOf(
            EditorCommand.ApplyUpdate(context, 3, "AA==") to "applyUpdate",
            EditorCommand.Awareness(context, 4, "AA==") to "awareness",
            EditorCommand.PeerLeft(context, 5, 9) to "peerLeft",
            EditorCommand.PublishAwareness(context, 6) to "publishAwareness",
            EditorCommand.Flush(context, 7) to "flush",
            EditorCommand.Dispose(context, 8) to "dispose",
        )
        commands.forEach { (command, name) -> assertEquals(name, JSONObject(codec.encode(command)).getString("command")) }
    }

    @Test fun repliesMustMatchOutstandingAndMayOnlyBeConsumedOnce() {
        val codec = RoomEditorCodec(); val reply = prepared(codec)
        rejects { codec.decode(event("ready", 1, "{\"markdown\":\"hi\",\"generation\":0}")) }
        val p = codec.decode(reply) as EditorEvent.Prepared
        assertEquals(42, p.clientId); assertEquals("AA==", p.seed)
        rejects { codec.decode(reply) }
        rejects { codec.decode(event("prepared", 99, "{\"clientId\":42,\"seed\":\"AA==\"}")) }
    }

    @Test fun invalidRepliesDoNotConsumePendingRequest() {
        val codec = RoomEditorCodec(); val reply = prepared(codec)
        listOf(
            reply.replace("\"version\":1", "\"version\":2"),
            reply.replace("\"version\":1", "\"version\":1.0"),
            reply.replace("\"version\":1", "\"version\":1e0"),
            reply.replace("\"version\":1", "\"version\":1,\"version\":1"),
            reply.replace("\"sid\":\"test-session\"", "\"sid\":\"other\""),
            reply.replace("\"documentGeneration\":1", "\"documentGeneration\":0"),
            reply.replace("\"connectionGeneration\":2", "\"connectionGeneration\":1"),
            reply.replace("\"clientId\":42", "\"clientId\":9007199254740992"),
            reply.replace("\"clientId\":42", "\"clientId\":-1"),
            reply.replace("\"clientId\":42", "\"clientId\":42,\"url\":\"https://bad\""),
            reply.replace("AA==", "AB=="),
            reply.replace("\"event\"", "'event'"),
            reply + " null",
        ).forEach { input -> rejects { codec.decode(input) } }
        assertTrue(codec.decode(reply) is EditorEvent.Prepared)
    }

    @Test fun staleCommandsAndReplacementContextsAreFenced() {
        val codec = RoomEditorCodec(); codec.decode(prepared(codec))
        rejects { codec.encode(EditorCommand.Flush(context, 1)) }
        rejects { codec.encode(EditorCommand.Prepare(context.copy(documentGeneration = 2), 2, "hi")) }
        rejects { codec.encode(EditorCommand.Flush(context.copy(sid = "other"), 2)) }
        codec.encode(EditorCommand.Dispose(context, 2)); codec.decode(event("disposed", 2))
        rejects { codec.encode(EditorCommand.Prepare(context, 3, "hi")) }
        val next = context.copy(documentGeneration = 2, sid = "next")
        assertEquals("prepare", JSONObject(codec.encode(EditorCommand.Prepare(next, 3, "hi"))).getString("command"))
        rejects { codec.decode(event("changed", null, "{\"markdown\":\"old\",\"generation\":2}")) }
    }

    @Test fun snapshotsAndUnsolicitedEventsAreTyped() {
        val codec = RoomEditorCodec(); codec.decode(prepared(codec))
        codec.encode(EditorCommand.Initialize(context, 2, "hi", "AA==", true))
        assertEquals(RoomSnapshot("hello", 0), (codec.decode(event("ready", 2, "{\"markdown\":\"hello\",\"generation\":0}")) as EditorEvent.Ready).snapshot)
        assertTrue(codec.decode(event("localUpdate", null, "{\"update\":\"AA==\"}")) is EditorEvent.LocalUpdate)
        assertTrue(codec.decode(event("awareness", null, "{\"update\":\"\"}")) is EditorEvent.Awareness)
        assertTrue(codec.decode(event("changed", null, "{\"markdown\":\"hello 😀\",\"generation\":1}")) is EditorEvent.Changed)
        val blocked = codec.decode(event("blocked", null, "{\"snapshot\":null,\"raw\":[\"AA==\"]}")) as EditorEvent.Blocked
        assertNull(blocked.snapshot); assertEquals(listOf("AA=="), blocked.raw)
        rejects { codec.decode(event("blocked", null, "{\"snapshot\":null,\"raw\":[\"AA==\"]}")) }
        rejects { codec.decode(event("changed", 9, "{\"markdown\":\"hi\",\"generation\":1}")) }
        rejects { codec.decode(event("changed", null, "{\"markdown\":\"\\ud800\",\"generation\":1}")) }
        rejects { codec.decode(event("error", null)) }
    }

    @Test fun canonicalBase64AndBounds() {
        listOf("", "AA==", "AAA=", "AAAA").forEach { assertEquals(it, RoomEditorCodec.base64(it)) }
        listOf("a", "AB==", "AAB=", "AAAA\n", "=AAA", "====", "A===", "AA-_", "AA== ").forEach { rejects { RoomEditorCodec.base64(it) } }
        rejects { RoomEditorCodec.markdown("я".repeat(500_001)) }
        rejects { RoomEditorCodec.markdown("\ud800") }
        rejects { RoomEditorCodec.safe(9_007_199_254_740_992L) }
        assertEquals(9_007_199_254_740_991L, RoomEditorCodec.safe(9_007_199_254_740_991L))
        // Encoded size is identical to the exact limit; decoded bound must still reject +1.
        val exact = "AAAA".repeat(RoomEditorCodec.MAX_BINARY / 3) + "AAA="
        assertEquals(exact, RoomEditorCodec.base64(exact))
        rejects { RoomEditorCodec.base64(exact.dropLast(1) + "A") }
    }
}
