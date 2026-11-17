package com.voxarium.remote.ui.editor.bridge

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.webkit.*
import android.widget.FrameLayout
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.voxarium.remote.data.room.*
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URI

enum class EditorBridgeFailure {
    UNSUPPORTED_WEBVIEW, INVALID_MESSAGE, COMMAND_REJECTED, QUEUE_OVERFLOW,
    PAGE_FAILED, RENDERER_GONE, DESTROYED,
}

/** Main-frame-only local editor. Callbacks run on main: enqueue them on the coordinator's
 * ordered IO worker (ROOM.md). Failure never destroys/replaces the editor before the
 * parent's checkpoint/recovery decision; only explicit destroy releases the WebView. */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor") // programmatic owner/callback constructor, never XML
class SecureEditorView(
    context: Context,
    private val onEvent: (EditorEvent) -> Unit,
    private val onFailure: (EditorBridgeFailure) -> Unit,
    private val supportsListener: () -> Boolean = { true }, // tests may disable, never override the real feature check
) : FrameLayout(context), RoomEditorPort {
    internal val webView = WebView(context)
    private val main = Handler(Looper.getMainLooper())
    private val codec = RoomEditorCodec()
    private val queue = ArrayDeque<String>()
    private var queueBytes = 0
    private var queuedCalls = 0
    private var overflowReported = false
    private val callLock = Any()
    private var ready = false
    @Volatile private var failed = false
    @Volatile private var destroyed = false
    private var loaded = false
    private var listenerInstalled = false
    private var configuration = JSONObject().put("dark", false).put("language", "en")
    private val startupTimeout = Runnable { if (!ready) fail(EditorBridgeFailure.PAGE_FAILED) }

    init {
        check(Looper.myLooper() == Looper.getMainLooper())
        addView(webView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        with(webView.settings) {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            blockNetworkLoads = true
            blockNetworkImage = true
            loadsImagesAutomatically = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            domStorageEnabled = false
            @Suppress("DEPRECATION")
            databaseEnabled = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true
            setGeolocationEnabled(false)
            cacheMode = WebSettings.LOAD_NO_CACHE
            if (Build.VERSION.SDK_INT >= 26) safeBrowsingEnabled = true
        }
        // There is no JavascriptInterface, URL-scheme, prompt or console-message fallback.
        val assets = WebViewAssetLoader.AssetsPathHandler(context)
        val loader = WebViewAssetLoader.Builder().setHttpAllowed(false)
            .addPathHandler(ASSET_PREFIX) { path ->
                if (safeRelativePath(path)) assets.handle("editor/$path") else denied()
            }.build()
        webView.setDownloadListener { _, _, _, _, _ -> /* intentionally never handled */ }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?) = false
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) { callback.invoke(origin, false, false) }
            override fun onShowFileChooser(view: WebView?, callback: android.webkit.ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean { callback?.onReceiveValue(null); return true }
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult): Boolean { result.cancel(); return true }
            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult): Boolean { result.cancel(); return true }
            override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean { result.cancel(); return true }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
            @Deprecated("Legacy callback still denies all navigation")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?) = true
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse {
                if (request.method != "GET" || !allowedAsset(request.url.toString())) return denied()
                if (request.isForMainFrame && request.url.toString() != PAGE_URL) return denied()
                return loader.shouldInterceptRequest(request.url) ?: denied()
            }
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (url != PAGE_URL || loaded) fail(EditorBridgeFailure.PAGE_FAILED) else loaded = true
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                if (failed || destroyed || ready) return
                if (url != PAGE_URL) { fail(EditorBridgeFailure.PAGE_FAILED); return }
                evaluateValue("typeof window.voxariumEditorReceive === 'function' && typeof window.voxariumEditorFormat === 'function' && typeof window.voxariumEditorConfigure === 'function'") { value ->
                    if (failed || destroyed) return@evaluateValue
                    if (value != "true") { fail(EditorBridgeFailure.PAGE_FAILED); return@evaluateValue }
                    ready = true; main.removeCallbacks(startupTimeout)
                    configurePage()
                    while (queue.isNotEmpty() && !failed) send(queue.removeFirst())
                    queueBytes = 0
                }
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest, error: WebResourceError?) { fail(EditorBridgeFailure.PAGE_FAILED) }
            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) { fail(EditorBridgeFailure.PAGE_FAILED) }
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler, error: android.net.http.SslError?) { handler.cancel(); fail(EditorBridgeFailure.PAGE_FAILED) }
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                fail(EditorBridgeFailure.RENDERER_GONE); return true
            }
        }
        if (!supportsListener() || !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) main.post { fail(EditorBridgeFailure.UNSUPPORTED_WEBVIEW) }
        else try {
            WebViewCompat.addWebMessageListener(webView, "voxariumBridge", setOf(ORIGIN)) { _, message, sourceOrigin, mainFrame, _ ->
                if (failed || destroyed) return@addWebMessageListener
                val event = try { decodeEditorMessage(codec, message.data ?: error("Empty message"), sourceOrigin.toString(), mainFrame, webView.url) }
                catch (_: Exception) { fail(EditorBridgeFailure.INVALID_MESSAGE); return@addWebMessageListener }
                onEvent(event)
            }
            listenerInstalled = true
            main.postDelayed(startupTimeout, 30_000)
            webView.loadUrl(PAGE_URL)
        } catch (_: RuntimeException) { main.post { fail(EditorBridgeFailure.PAGE_FAILED) } }
    }

    override fun submit(command: EditorCommand) = schedule {
        val json = try { codec.encode(command) } catch (_: Exception) { fail(EditorBridgeFailure.COMMAND_REJECTED); return@schedule }
        if (ready) send(json) else {
            val size = json.toByteArray(Charsets.UTF_8).size
            if (queue.size >= 256 || queueBytes + size > 16 * 1024 * 1024) fail(EditorBridgeFailure.QUEUE_OVERFLOW)
            else { queue.addLast(json); queueBytes += size }
        }
    }

    /** Closed enum, never arbitrary JavaScript, URLs or editor method names. */
    fun format(action: EditorFormat) = schedule {
        if (!ready) { fail(EditorBridgeFailure.COMMAND_REJECTED); return@schedule }
        evaluate("window.voxariumEditorFormat(${JSONObject.quote(action.wireName)})")
    }

    fun configure(dark: Boolean, language: String) = schedule {
        if (language != "en" && language != "ru") { fail(EditorBridgeFailure.COMMAND_REJECTED); return@schedule }
        configuration = JSONObject().put("dark", dark).put("language", language)
        if (ready) configurePage()
    }

    /** Invoke only after coordinator checkpoint/disposal or explicit renderer-loss decision. */
    fun destroy() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { destroy() }; return }
        if (destroyed) return
        destroyed = true; main.removeCallbacks(startupTimeout); queue.clear(); queueBytes = 0
        try {
            if (listenerInstalled && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) WebViewCompat.removeWebMessageListener(webView, "voxariumBridge")
        } catch (_: RuntimeException) { /* failed renderer/provider still must release the view */ }
        try { webView.stopLoading() } catch (_: RuntimeException) { /* renderer may already be gone */ }
        removeView(webView)
        try { webView.destroy() } catch (_: RuntimeException) { /* never reuse a failed provider */ }
    }

    private fun schedule(action: () -> Unit) {
        if (failed || destroyed) return
        synchronized(callLock) {
            if (overflowReported) return
            if (queuedCalls >= 256) {
                overflowReported = true
                main.post { fail(EditorBridgeFailure.QUEUE_OVERFLOW) }; return
            }
            queuedCalls++
        }
        main.post {
            synchronized(callLock) { queuedCalls-- }
            if (!failed && !destroyed) action()
        }
    }
    private fun configurePage() = evaluate("window.voxariumEditorConfigure($configuration)")
    private fun send(json: String) = evaluate("window.voxariumEditorReceive($json)")
    private fun evaluate(expression: String) {
        if (failed || destroyed) return
        // JSON data is never concatenated into a quoted JS string or treated as source.
        val safe = expression.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029").replace("<", "\\u003c")
        evaluateValue("(()=>{try{$safe;return true}catch(_){return false}})()") {
            if (it != "true") fail(EditorBridgeFailure.PAGE_FAILED)
        }
    }
    private fun evaluateValue(expression: String, callback: (String?) -> Unit) {
        try { webView.evaluateJavascript(expression, callback) }
        catch (_: RuntimeException) { fail(EditorBridgeFailure.PAGE_FAILED) }
    }
    private fun fail(reason: EditorBridgeFailure) {
        if (failed || destroyed) return
        failed = true; ready = false; main.removeCallbacks(startupTimeout)
        try { webView.stopLoading() } catch (_: RuntimeException) { /* renderer loss */ }
        webView.isEnabled = false; webView.visibility = View.INVISIBLE
        onFailure(reason)
    }

    companion object {
        const val ORIGIN = "https://appassets.androidplatform.net"
        const val ASSET_PREFIX = "/assets/editor/"
        const val PAGE_URL = "$ORIGIN${ASSET_PREFIX}room.html"
        fun trustedSource(origin: String, isMainFrame: Boolean): Boolean {
            if (!isMainFrame) return false
            return try {
                val uri = URI(origin)
                uri.scheme == "https" && uri.rawAuthority == "appassets.androidplatform.net" &&
                    (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") && uri.rawQuery == null && uri.rawFragment == null
            } catch (_: Exception) { false }
        }
        fun allowedAsset(url: String): Boolean = try {
            val uri = URI(url)
            uri.scheme == "https" && uri.rawAuthority == "appassets.androidplatform.net" && uri.rawQuery == null && uri.rawFragment == null &&
                uri.rawPath.startsWith(ASSET_PREFIX) && safeRelativePath(uri.rawPath.removePrefix(ASSET_PREFIX))
        } catch (_: Exception) { false }
        private fun safeRelativePath(path: String) = path.isNotEmpty() && path.length <= 256 &&
            path.all { it.isLetterOrDigit() && it.code < 128 || it in "/._-" } &&
            path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
        private fun denied() = WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(byteArrayOf()))
    }
}

enum class EditorFormat(val wireName: String) {
    BOLD("bold"), ITALIC("italic"), STRIKE("strike"), CODE("code"), BULLET_LIST("bulletList"),
    ORDERED_LIST("orderedList"), TASK_LIST("taskList"), UNDO("undo"), REDO("redo"),
}

/** The listener's complete admission boundary is pure and independently testable. */
internal fun decodeEditorMessage(codec: RoomEditorCodec, json: String, origin: String, mainFrame: Boolean, page: String?): EditorEvent {
    require(SecureEditorView.trustedSource(origin, mainFrame) && page == SecureEditorView.PAGE_URL) { "Untrusted editor frame" }
    return codec.decode(json)
}
