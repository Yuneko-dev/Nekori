package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class NovelReaderRuntimeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun packagedRuntimeUsesTypedBridgeAndRejectsStaleUnknownAndFramedMessages() {
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
        assumeTrue(NovelReaderProtocol.supportsVersion(WebViewCompat.getCurrentWebViewPackage(context)?.versionName))

        ready = CountDownLatch(1)
        pageFinished = CountDownLatch(1)
        events.clear()
        observedEvents.clear()
        webViewRef.set(null)
        bridgeRef.set(null)
        documentIdRef.set(null)
        val assetLoader = NovelWebViewAssetLoader(context.assets)

        try {
            instrumentation.runOnMainSync {
                val webView = WebView(context)
                webViewRef.set(webView)
                webView.settings.javaScriptEnabled = true
                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? =
                        assetLoader.intercept(request.url.toString())

                    override fun onPageFinished(view: WebView, url: String) {
                        pageFinished.countDown()
                    }
                }
                val bridge = NovelReaderBridge(
                    webView = webView,
                    onReady = ready::countDown,
                    onEvent = { method, payload ->
                        observedEvents += method to payload
                        events.offer(method to payload)
                    },
                    onRequest = { _, _, reply -> reply(null) },
                    onError = { error -> events.offer("bridge.error" to buildJsonObject { put("message", error) }) },
                )
                bridgeRef.set(bridge)
                val chapterIds = listOf(CHAPTER_ID.toLong(), NEXT_CHAPTER_ID.toLong())
                val documentId = bridge.beginDocument(SOURCE_BASE, chapterIds)
                documentIdRef.set(documentId)
                webView.loadDataWithBaseURL(
                    SOURCE_BASE,
                    NovelReaderRuntimeHtmlFixture.build(documentId, CHAPTER_ID, NEXT_CHAPTER_ID, PLAIN_TEXT_SOURCE),
                    "text/html",
                    "UTF-8",
                    null,
                )
            }
            assertTrue("fixture page did not finish", pageFinished.await(15, TimeUnit.SECONDS))
            assertTrue("packaged reader runtime did not become ready", ready.await(15, TimeUnit.SECONDS))

            assertEquals(
                PLAIN_TEXT,
                decodeString(command("tts.extractText", chapterPayload(CHAPTER_ID))),
            )
            assertEquals(
                "Select this phrase\nNext chapter paragraph",
                decodeString(command("tts.extractText", chapterPayload(NEXT_CHAPTER_ID))),
            )
            assertEquals("true", evaluate("window.__sourceSawRuntime === true"))
            assertEquals("true", evaluate("window.__sourcePluginRan === true"))
            assertFalse("plain text was interpreted as markup", evaluate("window.__unsafe === true") == "true")

            val beforeSummary = command("summary.focus", chapterPayload(CHAPTER_ID))
            assertEquals("false", beforeSummary)
            command(
                "summary.render",
                buildJsonObject {
                    put("chapterId", CHAPTER_ID)
                    put("state", "ready")
                    put("text", "<img src=x onerror=window.__summaryInjected=true>\n\nSecond summary paragraph")
                    putJsonObject("labels") {
                        put("title", "Summary")
                        put("loading", "Loading")
                        put("cancel", "Cancel")
                        put("regenerate", "Regenerate")
                        put("close", "Close")
                    }
                },
            )
            assertEquals("true", command("summary.focus", chapterPayload(CHAPTER_ID)))
            assertEquals(
                "true",
                evaluate("document.querySelector('nekori-chapter-summary').shadowRoot.mode === 'open'"),
            )
            assertEquals("false", evaluate("window.__summaryInjected === true"))

            evaluate("document.querySelector('nekori-chapter-summary').shadowRoot.querySelector('button').click()")
            val summaryAction = awaitEvent("summary.action")
            assertEquals(CHAPTER_ID, summaryAction["chapterId"]?.toString()?.trim('"'))
            assertEquals("regenerate", summaryAction["action"]?.toString()?.trim('"'))

            evaluate(
                "const range=document.createRange();" +
                    "range.selectNodeContents(document.querySelector('#selection-text'));" +
                    "const s=getSelection();s.removeAllRanges();s.addRange(range)",
            )
            assertEquals("Select this phrase", decodeString(command("selection.read")))

            NovelReaderTransportAttackFixture.assertBound(
                requireNotNull(documentIdRefValue()),
                CHAPTER_ID,
                ::evaluate,
                ::awaitEvent,
                observedEvents,
            )
        } finally {
            instrumentation.runOnMainSync {
                bridgeRef.get()?.endDocument()
                webViewRef.get()?.apply {
                    stopLoading()
                    loadUrl("about:blank")
                    destroy()
                }
            }
        }
    }

    private fun command(method: String, payload: JsonObject = JsonObject(emptyMap())): String {
        val completed = CountDownLatch(1)
        val result = AtomicReference<String>()
        instrumentation.runOnMainSync {
            bridgeRefValue().execute(ReaderCommand(method, payload)) {
                result.set(it)
                completed.countDown()
            }
        }
        assertTrue("reader command timed out: $method", completed.await(10, TimeUnit.SECONDS))
        return requireNotNull(result.get()) { "reader command failed: $method" }
    }

    private fun evaluate(script: String): String {
        val completed = CountDownLatch(1)
        val result = AtomicReference<String>()
        instrumentation.runOnMainSync {
            webViewRefValue().evaluateJavascript(script) {
                result.set(it)
                completed.countDown()
            }
        }
        assertTrue("JavaScript evaluation timed out", completed.await(5, TimeUnit.SECONDS))
        return requireNotNull(result.get())
    }

    private fun bridgeRefValue() = requireNotNull(currentBridge)
    private fun webViewRefValue() = requireNotNull(currentWebView)

    private fun awaitEvent(method: String): JsonObject {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val item = events.poll(200, TimeUnit.MILLISECONDS) ?: continue
            if (item.first == method) return item.second
        }
        error("no native $method event")
    }

    private val currentBridge get() = bridgeRef.get()
    private val currentWebView get() = webViewRef.get()
    private val currentDocumentId get() = documentIdRef.get()
    private fun documentIdRefValue() = currentDocumentId
    private val bridgeRef = AtomicReference<NovelReaderBridge>()
    private val webViewRef = AtomicReference<WebView>()
    private val documentIdRef = AtomicReference<String>()
    private lateinit var ready: CountDownLatch
    private lateinit var pageFinished: CountDownLatch
    private val events = LinkedBlockingQueue<Pair<String, JsonObject>>()
    private val observedEvents = CopyOnWriteArrayList<Pair<String, JsonObject>>()

    private fun decodeString(wire: String) = Json.parseToJsonElement(wire).jsonPrimitive.content

    private fun chapterPayload(id: String) = buildJsonObject { put("chapterId", id) }

    companion object {
        private const val SOURCE_BASE = "https://source.example/reader/chapter"
        private const val CHAPTER_ID = "9223372036854775807"
        private const val NEXT_CHAPTER_ID = "9223372036854775806"
        private const val PLAIN_TEXT_SOURCE = "First <script>window.__unsafe=true</script> & paragraph\n\nSecond text"
        private const val PLAIN_TEXT = "First <script>window.__unsafe=true</script> & paragraph\nSecond text"
    }
}
