package com.voxarium.remote.editor.bridge

import android.os.Looper
import android.webkit.WebSettings
import androidx.test.core.app.ApplicationProvider
import com.voxarium.remote.ui.editor.bridge.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SecureEditorViewTest {
    @Test fun rendererFailureNotifiesOnceAndLeavesDestructionToCheckpointOwner() {
        val failures = mutableListOf<EditorBridgeFailure>()
        val host = SecureEditorView(ApplicationProvider.getApplicationContext(), {}, failures::add) { false }
        assertTrue(host.webView.webViewClient.onRenderProcessGone(host.webView, null))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(EditorBridgeFailure.RENDERER_GONE), failures)
        assertEquals(1, host.childCount)
        host.destroy()
        assertEquals(0, host.childCount)
    }

    @Test fun unsupportedListenerFailsClosedWithoutLoadingAndSettingsDenyExternalCapabilities() {
        val failures = mutableListOf<EditorBridgeFailure>()
        val host = SecureEditorView(ApplicationProvider.getApplicationContext(), {}, failures::add) { false }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(EditorBridgeFailure.UNSUPPORTED_WEBVIEW), failures)
        assertNull(host.webView.url)
        with(host.webView.settings) {
            assertTrue(javaScriptEnabled)
            assertFalse(allowFileAccess); assertFalse(allowContentAccess)
            @Suppress("DEPRECATION") assertFalse(allowFileAccessFromFileURLs)
            @Suppress("DEPRECATION") assertFalse(allowUniversalAccessFromFileURLs)
            assertTrue(blockNetworkLoads); assertTrue(blockNetworkImage)
            assertFalse(loadsImagesAutomatically); assertFalse(javaScriptCanOpenWindowsAutomatically)
            assertFalse(supportMultipleWindows()); assertFalse(domStorageEnabled)
            assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, mixedContentMode)
        }
        host.destroy(); host.destroy()
        assertEquals(1, failures.size)
    }
}
