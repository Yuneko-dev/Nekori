package eu.kanade.tachiyomi.ui.webview

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import org.json.JSONTokener
import java.util.UUID

/** A document-scoped CDP connection; it never enables WebView's remote debugging port. */
@SuppressLint("SetJavaScriptEnabled")
internal class DevToolsLiteSession(
    private val target: WebView,
    private val onClose: () -> Unit,
    private val onError: (String) -> Unit,
) {
    val frontend = DevToolsKeyboardWebView(target.context)
    private val handler = Handler(Looper.getMainLooper())
    private val key = "__nekoriDevTools_${UUID.randomUUID().toString().replace("-", "")}"
    private var port: WebMessagePort? = null
    private var reply: JavaScriptReplyProxy? = null
    private val queued = ArrayDeque<String>()
    private var closed = false
    private val assets = target.context.assets
    private val loader = WebViewAssetLoader.Builder()
        .setDomain(HOST)
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(target.context))
        .build()

    init {
        frontend.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
        }
        frontend.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                if (isFrontendUrl(request.url)) {
                    loader.shouldInterceptRequest(request.url) ?: blocked()
                } else {
                    blocked()
                }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                !isFrontendUrl(request.url)
        }
    }

    fun start() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            onError("DevTools requires an up-to-date Android System WebView.")
            return
        }
        WebViewCompat.addWebMessageListener(frontend, "__chiiNativeTransport", setOf(ORIGIN)) {
                _,
                message,
                sourceOrigin,
                isMainFrame,
                replyProxy,
            ->
            if (closed || !isMainFrame || sourceOrigin.toString() != ORIGIN) return@addWebMessageListener
            val data = runCatching { JSONObject(message.data ?: "") }.getOrNull() ?: return@addWebMessageListener
            reply = replyProxy
            when (data.optString("kind")) {
                "closed" -> onClose()
                "cdp" -> {
                    val wire = data.opt("data") as? String ?: return@addWebMessageListener
                    if (port != null) {
                        port?.postMessage(WebMessage(wire))
                    } else if (queued.size < 1000) {
                        queued.add(wire)
                    }
                }
            }
        }
        attach()
        // DevTools computes its split panes at startup; wait for the real dock dimensions.
        frontend.doOnLayout {
            if (!closed) frontend.loadUrl("$ORIGIN/assets/devtools-lite/android.html#?embedded=${Uri.encode(ORIGIN)}")
        }
    }

    private fun attach() {
        // Retain one backend per document: reinjecting Chobitsu would wrap page APIs again.
        val backend = assets.open("devtools-lite/vendor/chobitsu.js").bufferedReader().use { it.readText() }
        val transport = assets.open("devtools-lite/target.js").bufferedReader().use { it.readText() }
        val script = """
            (() => {
              const backendKey = Symbol.for('nekori.devtools.backend');
              if (!window[backendKey]) {
                const previous = window.chobitsu;
                $backend
                window[backendKey] = window.chobitsu;
                if (previous === undefined) delete window.chobitsu; else window.chobitsu = previous;
              }
              return ($transport)(${JSONObject.quote(key)});
            })()
        """.trimIndent()
        target.evaluateJavascript(script) { result ->
            if (closed) return@evaluateJavascript
            val origin = runCatching { JSONTokener(result).nextValue() as? String }.getOrNull()
            if (origin == null || origin == "null") {
                onError("DevTools cannot attach to this document. Open a web page and try again.")
                return@evaluateJavascript
            }
            val channels = target.createWebMessageChannel()
            port = channels[0]
            channels[0].setWebMessageCallback(object : WebMessagePort.WebMessageCallback() {
                override fun onMessage(port: WebMessagePort, message: WebMessage) {
                    if (closed) return
                    val data = runCatching { JSONObject(message.data ?: "") }.getOrNull() ?: return
                    when (data.optString("kind")) {
                        "closed" -> onClose()
                        "cdp" -> reply?.postMessage(data.toString())
                    }
                }
            })
            target.postWebMessage(WebMessage(key, arrayOf(channels[1])), Uri.parse(origin))
            while (queued.isNotEmpty()) channels[0].postMessage(WebMessage(queued.removeFirst()))
            handler.postDelayed(::checkDocument, 1000)
        }
    }

    private fun checkDocument() {
        if (closed) return
        if (!target.isAttachedToWindow) {
            onClose()
            return
        }
        target.evaluateJavascript("Boolean(window[${JSONObject.quote(key)}])") { alive ->
            if (closed) return@evaluateJavascript
            if (alive != "true") onClose() else handler.postDelayed(::checkDocument, 1000)
        }
    }

    fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacksAndMessages(null)
        queued.clear()
        target.evaluateJavascript("window[${JSONObject.quote(key)}]?.close()", null)
        port?.close()
        port = null
        reply = null
        if (frontend.hasFocus()) ViewCompat.getWindowInsetsController(frontend)?.hide(WindowInsetsCompat.Type.ime())
        frontend.clearFocus()
        frontend.stopLoading()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.removeWebMessageListener(frontend, "__chiiNativeTransport")
        }
        (frontend.parent as? ViewGroup)?.removeView(frontend)
        frontend.destroy()
    }

    companion object {
        const val HOST = "nekori.devtools"
        const val ORIGIN = "https://$HOST"

        internal fun isFrontendUrl(uri: Uri): Boolean {
            val path = uri.path.orEmpty()
            return uri.scheme == "https" && uri.host == HOST && uri.port in listOf(-1, 443) &&
                uri.userInfo == null && path.startsWith("/assets/devtools-lite/") &&
                '\\' !in path && '\u0000' !in path && path.split('/').none { it == "." || it == ".." }
        }

        private fun blocked() = WebResourceResponse(
            "text/plain",
            "UTF-8",
            403,
            "Forbidden",
            emptyMap(),
            "Forbidden".byteInputStream(),
        )
    }
}
