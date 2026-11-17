package com.voxarium.remote.editor.bridge

import com.voxarium.remote.ui.editor.bridge.SecureEditorView
import com.voxarium.remote.ui.editor.bridge.RoomEditorCodec
import com.voxarium.remote.ui.editor.bridge.decodeEditorMessage
import com.voxarium.remote.data.room.*
import org.junit.Assert.*
import org.junit.Test

class SecureEditorPolicyTest {
    @Test fun listenerAdmissionRejectsWrongFrameOriginPageSchemaAndReplay() {
        val codec = RoomEditorCodec()
        codec.encode(EditorCommand.Prepare(EditorContext(1, 1, "sid"), 1, "text"))
        val json = """{"version":1,"documentGeneration":1,"connectionGeneration":1,"sid":"sid","requestId":1,"event":"prepared","payload":{"clientId":1,"seed":"AA=="}}"""
        fun reject(jsonValue: String = json, origin: String = SecureEditorView.ORIGIN, frame: Boolean = true, page: String? = SecureEditorView.PAGE_URL) {
            try { decodeEditorMessage(codec, jsonValue, origin, frame, page); fail("Must reject listener message") }
            catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {}
        }
        reject(origin = "https://remote.example")
        reject(frame = false)
        reject(page = "https://appassets.androidplatform.net/assets/editor/other.html")
        reject(page = null)
        reject(jsonValue = json.replace("\"version\":1", "\"version\":2"))
        reject(jsonValue = json.replace("\"documentGeneration\":1", "\"documentGeneration\":0"))
        assertTrue(decodeEditorMessage(codec, json, SecureEditorView.ORIGIN, true, SecureEditorView.PAGE_URL) is EditorEvent.Prepared)
        reject()
    }

    @Test fun onlyExactLocalMainFrameOriginIsTrusted() {
        assertTrue(SecureEditorView.trustedSource(SecureEditorView.ORIGIN, true))
        assertTrue(SecureEditorView.trustedSource(SecureEditorView.ORIGIN + "/", true))
        assertFalse(SecureEditorView.trustedSource(SecureEditorView.ORIGIN, false))
        listOf("http://appassets.androidplatform.net", "https://appassets.androidplatform.net.evil", "https://evil@appassets.androidplatform.net", "https://appassets.androidplatform.net:443", "https://appassets.androidplatform.net/assets/editor/room.html", "https://appassets.androidplatform.net#fragment", "null", "file:///android_asset/editor/room.html").forEach {
            assertFalse(it, SecureEditorView.trustedSource(it, true))
        }
    }
    @Test fun onlyEditorAssetFilesAreAllowed() {
        val root = SecureEditorView.ORIGIN
        listOf("/assets/editor/room.html", "/assets/editor/assets/room-123.js", "/assets/editor/theme.css").forEach { assertTrue(it, SecureEditorView.allowedAsset(root + it)) }
        listOf("/assets/editor/../secret", "/assets/editor/%2e%2e/secret", "/assets/editor/%252e%252e/secret", "/assets/editor/a%2fb", "/assets/editor//a", "/assets/editor/", "/assets/editor/a?x=1", "/assets/editor/a#x", "/assets/other/a", "/res/raw/a").forEach { assertFalse(it, SecureEditorView.allowedAsset(root + it)) }
        assertFalse(SecureEditorView.allowedAsset("https://remote.example/assets/editor/a.js"))
        assertFalse(SecureEditorView.allowedAsset("file:///android_asset/editor/a.js"))
    }
}
