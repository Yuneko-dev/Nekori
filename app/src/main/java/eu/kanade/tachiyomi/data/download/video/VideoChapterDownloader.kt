package eu.kanade.tachiyomi.data.download.video

import android.content.Context
import android.os.SystemClock
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.LOCAL_VIDEO_BUTTON_ID
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterDirectives
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewDocumentBuilder.escapeForScriptTag
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.proxy.NovelReaderProxyServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.novel.TDMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong

internal class VideoChapterDownloader(
    context: Context,
    private val client: OkHttpClient = Injekt.get<NetworkHelper>().client,
    private val userAgent: String = Injekt.get<NetworkHelper>().defaultUserAgentProvider(),
) {
    private val context = context.applicationContext

    suspend fun download(
        html: String,
        directives: NovelWebViewChapterDirectives,
        pluginJavaScript: String,
        directory: UniFile,
        // Must match the reader URL because media hosts commonly validate Referer.
        baseUrl: String,
        novelTitle: String,
        chapterTitle: String,
        onProgress: (done: Int, total: Int) -> Unit,
    ) {
        val video = requireNotNull(directives.video)
        if (video.disableProgress) {
            throw Exception(context.stringResource(TDMR.strings.video_download_error_progress_disabled))
        }
        if (video.directIframe) {
            throw Exception(context.stringResource(TDMR.strings.video_download_error_iframe))
        }

        val documentOrigin = originOf(baseUrl)
        val events = Channel<VideoDownloadEvent>(Channel.CONFLATED)
        val lastActivity = AtomicLong(SystemClock.elapsedRealtime())
        val touch = {
            lastActivity.set(SystemClock.elapsedRealtime())
        }
        val sink = NovelReaderProxyServer.Sink(directory, documentOrigin)
        val server = NovelReaderProxyServer(client = client, sink = sink, onNetworkActivity = touch)
        var webView: HeadlessChapterWebView? = null

        try {
            server.start()
            withContext(Dispatchers.Main.immediate) {
                HeadlessChapterWebView(context, userAgent, events, touch).also {
                    webView = it
                    // ponytail: direct files share the WebView path for now; use OkHttp directly only
                    // when resumable downloads become a measured requirement.
                    it.loadDataWithBaseURL(
                        baseUrl,
                        buildDocument(html, directives, pluginJavaScript, server.endpoint, server.sinkEndpoint),
                        "text/html",
                        Charsets.UTF_8.name(),
                        null,
                    )
                }
            }
            while (true) {
                sink.committedFile?.let { videoFile ->
                    writeStub(directory, requireNotNull(videoFile.name), novelTitle, chapterTitle)
                    return
                }

                val idleFor = SystemClock.elapsedRealtime() - lastActivity.get()
                if (
                    idleFor >= WATCHDOG_TIMEOUT_MS &&
                    SystemClock.elapsedRealtime() - lastActivity.get() >= WATCHDOG_TIMEOUT_MS
                ) {
                    throw Exception(context.stringResource(TDMR.strings.video_download_error_timeout))
                }

                when (
                    val event = withTimeoutOrNull(minOf(WATCHDOG_POLL_MS, WATCHDOG_TIMEOUT_MS - idleFor)) {
                        events.receive()
                    }
                ) {
                    is VideoDownloadEvent.Progress -> onProgress(event.done, event.total)
                    is VideoDownloadEvent.Error -> {
                        // A commit the server finished but whose response never made it back looks
                        // like a failure to the page while the file is already on disk. The sink is
                        // the authority on that, so it is consulted before believing the error.
                        if (sink.committedFile != null) continue
                        val message = when (event.code) {
                            "iframe" -> context.stringResource(TDMR.strings.video_download_error_iframe)
                            else -> event.message
                        }
                        throw Exception(message.ifBlank { event.code })
                    }
                    null -> Unit
                }
            }
        } finally {
            withContext(NonCancellable) {
                runCatching {
                    withContext(Dispatchers.Main.immediate) {
                        webView?.run {
                            runCatching { stopLoading() }
                            runCatching { removeJavascriptInterface(HeadlessChapterWebView.BRIDGE_NAME) }
                            runCatching { destroy() }
                        }
                    }
                }.onFailure { logcat(LogPriority.WARN, it) { "Unable to destroy video download WebView" } }
                runCatching { server.close() }.onFailure {
                    logcat(LogPriority.WARN, it) { "Unable to close video download proxy" }
                }
                events.close()
            }
        }
    }

    private fun buildDocument(
        html: String,
        directives: NovelWebViewChapterDirectives,
        pluginJavaScript: String,
        proxyEndpoint: String,
        sinkEndpoint: String,
    ): String {
        val compatConfig = """{"proxyEndpoint":"$proxyEndpoint"}"""
        val downloadMeta = "<meta name=\"$DOWNLOAD_META\" content=\"$sinkEndpoint\">"
        val body = Jsoup.parse(html).body().html()
        val pluginScript = pluginJavaScript.escapeForScriptTag()

        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                ${directives.metadataHtml}
                $downloadMeta
            </head>
            <body>
                <div id="LNReader-chapter">$body</div>
                <div id="reader-ui"></div>
                <script id="lnreader-compat-config" type="application/json">$compatConfig</script>
                <script src="$ASSET_ROOT/lnreader-compat.js"></script>
                <script src="$ASSET_ROOT/hls.min.js"></script>
                <script src="$ASSET_ROOT/core-player.js"></script>
                ${if (pluginScript.isBlank()) "" else "<script>$pluginScript</script>"}
            </body>
            </html>
        """.trimIndent()
    }

    private fun writeStub(
        directory: UniFile,
        fileName: String,
        novelTitle: String,
        chapterTitle: String,
    ) {
        val content = createVideoStub(
            localVideoFileName = fileName,
            playLabel = context.stringResource(TDMR.strings.novel_video_play_downloaded),
            novelTitle = novelTitle,
            chapterTitle = chapterTitle,
        )
        val bytes = content.toByteArray(Charsets.UTF_8)

        // Some SAF providers report the right size before persisting data. Sync and verify this
        // small file before the caller renames the chapter directory.
        repeat(STUB_WRITE_ATTEMPTS) { attempt ->
            directory.findFile(STUB_FILE)?.delete()
            val stub = directory.createFile(STUB_FILE)
                ?: throw Exception("Unable to create video chapter stub")

            val descriptor = context.contentResolver.openFileDescriptor(stub.uri, "rwt")
            if (descriptor != null) {
                descriptor.use {
                    FileOutputStream(it.fileDescriptor).use { output ->
                        output.write(bytes)
                        output.flush()
                        runCatching { it.fileDescriptor.sync() }
                    }
                }
            } else {
                stub.openOutputStream().use { it.write(bytes) }
            }

            val written = runCatching {
                context.contentResolver.openInputStream(stub.uri)?.use { it.readBytes() }
            }.getOrNull()
            if (written != null && written.contentEquals(bytes)) return

            logcat(LogPriority.WARN) {
                "Video chapter stub did not persist (attempt ${attempt + 1}), rewriting"
            }
        }
        throw Exception("Video chapter stub did not persist")
    }

    /** Exact Origin reported by a WebView loaded with [baseUrl]. */
    private fun originOf(baseUrl: String): String =
        baseUrl.toHttpUrlOrNull()
            ?.let { url ->
                val port = if (url.port == HttpUrl.defaultPort(url.scheme)) "" else ":${url.port}"
                "${url.scheme}://${url.host}$port"
            }
            ?: NovelReaderProxyServer.DEFAULT_SINK_ORIGIN

    companion object {
        private const val ASSET_ROOT = "https://tsundoku.reader/assets"
        private const val DOWNLOAD_META = "lnreader-video-download"
        private const val STUB_FILE = "index.html"
        private const val STUB_WRITE_ATTEMPTS = 3
        private const val WATCHDOG_TIMEOUT_MS = 30_000L
        private const val WATCHDOG_POLL_MS = 500L
    }
}

internal fun createVideoStub(
    localVideoFileName: String,
    playLabel: String,
    novelTitle: String,
    chapterTitle: String,
): String {
    val document = Document.createShell("")
    document.head().apply {
        appendElement("meta").attr("charset", "UTF-8")
        appendElement("title").text(playLabel)
        appendElement("meta").attr("name", "lnreader-chapter-type").attr("content", "video")
        appendElement("meta").attr("name", "lnreader-video-local").attr("content", localVideoFileName)
    }
    document.body().appendElement("h2").text(novelTitle)
    document.body().appendElement("h4").text(chapterTitle)
    document.body().appendElement("button")
        .addClass("next-button")
        .addClass("local-video-button")
        .attr("type", "button")
        .attr("id", LOCAL_VIDEO_BUTTON_ID)
        .text(playLabel)
    return "<!doctype html>\n${document.outerHtml()}"
}
