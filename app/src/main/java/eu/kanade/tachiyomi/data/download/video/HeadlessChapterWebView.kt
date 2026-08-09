package eu.kanade.tachiyomi.data.download.video

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.Keep
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewAssetLoader
import kotlinx.coroutines.channels.SendChannel

internal sealed interface VideoDownloadEvent {
    data class Progress(val done: Int, val total: Int) : VideoDownloadEvent
    data class Error(val code: String, val message: String) : VideoDownloadEvent
}

@SuppressLint("SetJavaScriptEnabled")
internal class HeadlessChapterWebView(
    context: Context,
    events: SendChannel<VideoDownloadEvent>,
    onActivity: () -> Unit,
) : WebView(context.applicationContext) {

    private val assetLoader = NovelWebViewAssetLoader(context.assets)

    init {
        setRendererPriorityPolicy(RENDERER_PRIORITY_IMPORTANT, false)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        addJavascriptInterface(Bridge(events, onActivity), BRIDGE_NAME)
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                return assetLoader.intercept(url) ?: super.shouldInterceptRequest(view, request)
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                events.trySend(VideoDownloadEvent.Error("renderer", "Video download renderer stopped"))
                return true
            }
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(View.VISIBLE)
    }

    // Must stay public and kept: the WebView exposes @JavascriptInterface methods by reflection, and
    // on a non-public class it silently exposes nothing at all - the page just sees an object with
    // no methods. That failure is invisible, because the download still finishes on sink bytes alone
    // while every tick, progress and error report is dropped.
    @Keep
    @Suppress("unused")
    class Bridge(
        private val events: SendChannel<VideoDownloadEvent>,
        private val reportActivity: () -> Unit,
    ) {
        @JavascriptInterface
        fun onActivity() = reportActivity()

        @JavascriptInterface
        fun onProgress(done: Int, total: Int) {
            events.trySend(VideoDownloadEvent.Progress(done, total))
        }

        @JavascriptInterface
        fun onError(code: String?, message: String?) {
            events.trySend(
                VideoDownloadEvent.Error(
                    code = code.orEmpty().take(64),
                    message = message.orEmpty().take(1_000),
                ),
            )
        }
    }

    companion object {
        const val BRIDGE_NAME = "TsundokuVideoDownload"
    }
}
