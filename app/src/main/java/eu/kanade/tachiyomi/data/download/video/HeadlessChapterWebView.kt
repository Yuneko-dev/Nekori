package eu.kanade.tachiyomi.data.download.video

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelReaderBridge
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewAssetLoader
import eu.kanade.tachiyomi.util.system.setUserAgent
import kotlinx.coroutines.channels.SendChannel
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

internal sealed interface VideoDownloadEvent {
    data class Progress(val done: Int, val total: Int) : VideoDownloadEvent
    data class Error(val code: String, val message: String) : VideoDownloadEvent
}

@SuppressLint("SetJavaScriptEnabled")
internal class HeadlessChapterWebView(
    context: Context,
    userAgent: String,
    private val events: SendChannel<VideoDownloadEvent>,
    private val onActivity: () -> Unit,
) : WebView(context.applicationContext) {

    private val assetLoader = NovelWebViewAssetLoader(context.assets)
    private val readerBridge = NovelReaderBridge(
        webView = this,
        onReady = onActivity,
        onEvent = { method, payload ->
            when (method) {
                "media.download.activity", "media.download.ready", "media.download.complete" -> onActivity()
                "media.download.progress" -> {
                    val done = (payload["current"] as? JsonPrimitive)?.intOrNull
                    val total = (payload["total"] as? JsonPrimitive)?.intOrNull
                    if (done != null && total != null && done >= 0 && total >= done) {
                        onActivity()
                        events.trySend(VideoDownloadEvent.Progress(done, total))
                    }
                }
                "media.download.error", "reader.error" -> {
                    val code = (payload["code"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "runtime"
                    val message = (payload["message"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    if (message != null) events.trySend(VideoDownloadEvent.Error(code.take(64), message.take(1_000)))
                }
            }
        },
        onRequest = { method, _, reply ->
            reply(if (method == "media.requestProtectedPlayback") JsonPrimitive(false) else null)
        },
        onError = { events.trySend(VideoDownloadEvent.Error("runtime", it)) },
    )

    fun prepareDocument(baseUrl: String): String = readerBridge.beginDocument(baseUrl, listOf(0L))

    init {
        setRendererPriorityPolicy(RENDERER_PRIORITY_IMPORTANT, false)
        setUserAgent(userAgent)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                if (request?.isForMainFrame != true) return false
                readerBridge.endDocument()
                events.trySend(
                    VideoDownloadEvent.Error(
                        "navigation",
                        "Video resolver attempted to navigate away from its document",
                    ),
                )
                return true
            }
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                return assetLoader.intercept(url) ?: super.shouldInterceptRequest(view, request)
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                readerBridge.endDocument()
                events.trySend(VideoDownloadEvent.Error("renderer", "Video download renderer stopped"))
                return true
            }
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(View.VISIBLE)
    }

    override fun destroy() {
        readerBridge.endDocument()
        super.destroy()
    }
}
