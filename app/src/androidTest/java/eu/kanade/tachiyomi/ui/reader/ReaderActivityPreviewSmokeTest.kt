package eu.kanade.tachiyomi.ui.reader

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ReaderActivityPreviewSmokeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun infiniteScrollToggleReloadsRealReader() {
        val preference = Injekt.get<ReaderPreferences>().novelInfiniteScroll
        val original = preference.get()
        var activity: Activity? = null
        try {
            activity = instrumentation.startActivitySync(
                ReaderActivity.newPreviewIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            val webView = requireNotNull(findReaderWebView(activity))
            awaitReaderReady(webView, activity)
            repeat(4) { index ->
                val before = ReaderActivityFeedbackTestSupport.evaluate(webView, "window.nekori.config.documentId")
                val enabled = if (index % 2 == 0) !original else original
                instrumentation.runOnMainSync { preference.set(enabled) }
                ReaderActivityFeedbackTestSupport.awaitState(
                    webView,
                    "infinite scroll toggle $index",
                    """
                    JSON.stringify({
                      changed: String(window.nekori?.config.documentId !== $before),
                      infinite: String(window.nekori?.config.runtime.infinite),
                      chapter: window.nekori?.config.chapter.id
                    })
                    """.trimIndent(),
                ) { it["changed"] == "true" && it["infinite"] == enabled.toString() && it["chapter"] == "-2" }
                awaitReaderReady(webView, activity)
            }
            val beforeReload = ReaderActivityFeedbackTestSupport.evaluate(webView, "window.nekori.config.documentId")
            instrumentation.runOnMainSync { (activity as ReaderActivity).viewModel.reloadChapter(fromSource = false) }
            ReaderActivityFeedbackTestSupport.awaitState(
                webView,
                "reader reload through chapter loader",
                "JSON.stringify({changed: String(!!window.nekori && window.nekori.config.documentId !== $beforeReload)})",
            ) { it["changed"] == "true" }
            awaitReaderReady(webView, activity)
        } finally {
            instrumentation.runOnMainSync {
                activity?.finish()
                preference.set(original)
            }
        }
    }

    @Test
    fun previewIntentLoadsSampleInRealReaderWithoutStartupError() {
        var activity: Activity? = null
        try {
            activity = instrumentation.startActivitySync(
                ReaderActivity.newPreviewIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            val webView = findReaderWebView(activity)
            assertNotNull("preview did not create a reader WebView; ${describeWebViews(activity)}", webView)

            val state = awaitReaderReady(requireNotNull(webView), activity)
            assertEquals("reader", state["mode"])
            assertEquals("Lorem ipsum", state["novelName"])
            assertEquals("-2", state["chapterId"])
            assertEquals("-2", state["chapterIds"])
            assertEquals("text preview must not create a media player", "false", state["playerApi"])
            assertEquals("text preview must not load media scripts", "false", state["videoElements"])
            assertEquals("preview text must not start with an empty video surface", "false", state["playerContainer"])
            assertTrue(
                "layout spread was not resolved by native code",
                state["layoutSpread"] in setOf("single", "double"),
            )
            assertTrue("preview sample did not render", state["chapterText"].orEmpty().contains(SAMPLE_TEXT))
            assertTrue("reader chapter element is missing", state["chapterTag"] == "nekori-chapter")
            assertTrue("reader root is missing", state["readerRoot"] == "true")
            assertTrue("reader API readiness promise did not resolve", state["ready"] == "true")
            assertTrue("reader error was emitted during startup", state["readerErrors"].orEmpty().isBlank())
            assertTrue("reader readiness rejected", state["readyError"].orEmpty().isBlank())
            assertFalse("reader displayed an inline startup error", state["inlineError"] == "true")
        } finally {
            activity?.let { running ->
                instrumentation.runOnMainSync { running.finish() }
            }
        }
    }

    private fun findReaderWebView(activity: Activity): WebView? {
        val found = AtomicReference<List<WebView>>(emptyList())
        val deadline = SystemClock.uptimeMillis() + ACTIVITY_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline && found.get().isEmpty()) {
            instrumentation.runOnMainSync {
                found.set(collectWebViews(activity.window.decorView))
            }
            if (found.get().isEmpty()) SystemClock.sleep(POLL_INTERVAL_MS)
        }
        return found.get().firstOrNull()
    }

    private fun collectWebViews(view: View): List<WebView> = when (view) {
        is WebView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { collectWebViews(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun describeWebViews(activity: Activity): String {
        val descriptions = AtomicReference<List<String>>(emptyList())
        instrumentation.runOnMainSync {
            descriptions.set(
                collectWebViews(activity.window.decorView).map { webView ->
                    val bounds = Rect()
                    webView.getGlobalVisibleRect(bounds)
                    "url=${webView.url}, title=${webView.title}, " +
                        "visible=${webView.visibility == View.VISIBLE}, bounds=$bounds"
                },
            )
        }
        return "WebViews=${descriptions.get()}"
    }

    private fun awaitReaderReady(webView: WebView, activity: Activity): Map<String, String> {
        val script = """
            (function() {
              var api = window.nekori;
              if (!api) return JSON.stringify({
                api: 'missing',
                url: location.href,
                title: document.title,
                readyState: document.readyState,
                root: String(!!document.querySelector('[data-nekori-reader-root]')),
                bodyText: (document.body ? document.body.innerText : '').slice(0, 500),
                scripts: Array.from(document.scripts).map(function(script) { return script.src; }).join('|'),
                config: (document.getElementById('nekori-reader-config') || {}).textContent || '',
                transport: typeof window.__nekoriNativeTransport,
                legacyNative: typeof window.Android,
                readerApi: typeof window.reader,
                runtimeRoot: typeof window.Tsundoku
              });
              if (!window.__previewSmokeHooked) {
                window.__previewSmokeHooked = true;
                window.__previewSmokeErrors = [];
                api.events.subscribe('reader.error', function(event) {
                  window.__previewSmokeErrors.push(String(event.message || ''));
                });
                api.ready.then(function() { window.__previewSmokeReady = true; }, function(error) {
                  window.__previewSmokeReadyError = String(error);
                });
              }
              if (!window.__previewSmokeReady && !window.__previewSmokeReadyError) return null;
              var chapter = document.querySelector('nekori-chapter[data-nekori-chapter-id="-2"]');
              return JSON.stringify({
                mode: api.config.mode,
                api: 'ready',
                novelName: api.config.novel.name,
                chapterId: api.config.chapter.id,
                chapterIds: api.config.chapters.map(function(chapter) { return String(chapter.id); }).join(','),
                layoutSpread: api.config.layout ? api.config.layout.spread : '',
                chapterText: chapter ? chapter.textContent : '',
                chapterTag: chapter ? chapter.tagName.toLowerCase() : '',
                readerRoot: String(!!document.querySelector('[data-nekori-reader-root]')),
                playerApi: String(!!api.player),
                videoElements: String(!!customElements.get('hlsjs-video') && !!customElements.get('dash-video')),
                playerContainer: String(!!document.querySelector('#nekori-player-container')),
                ready: String(!!window.__previewSmokeReady),
                readerErrors: (window.__previewSmokeErrors || []).join('\n'),
                inlineError: String(!!document.querySelector('nekori-reader-feedback[data-severity="error"]')),
                readyError: window.__previewSmokeReadyError || ''
              });
            })()
        """.trimIndent()
        val deadline = SystemClock.uptimeMillis() + READER_TIMEOUT_MS
        var lastState: Map<String, String> = emptyMap()
        while (SystemClock.uptimeMillis() < deadline) {
            val result = evaluate(webView, script)
            if (result != "null") {
                val json = Json.parseToJsonElement(result).jsonPrimitive.content
                val values = Json.parseToJsonElement(json).let { element ->
                    element.jsonObject.mapValues { (_, value) -> value.jsonPrimitive.content }
                }
                lastState = values
                if (values["ready"] == "true") return values
                if (!values["readyError"].isNullOrBlank()) {
                    error("preview reader initialization failed: ${values["readyError"]}")
                }
            }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        error("reader preview did not become ready; last state=$lastState; ${describeWebViews(activity)}")
    }

    private fun evaluate(webView: WebView, script: String): String {
        val completed = CountDownLatch(1)
        val result = AtomicReference<String>()
        instrumentation.runOnMainSync {
            webView.evaluateJavascript(script) {
                result.set(it ?: "null")
                completed.countDown()
            }
        }
        assertTrue("reader WebView evaluation timed out", completed.await(EVALUATE_TIMEOUT_MS, TimeUnit.MILLISECONDS))
        return requireNotNull(result.get())
    }

    private companion object {
        const val SAMPLE_TEXT = "Lorem ipsum dolor sit amet, consectetuer adipiscing elit."
        const val ACTIVITY_TIMEOUT_MS = 15_000L
        const val READER_TIMEOUT_MS = 30_000L
        const val EVALUATE_TIMEOUT_MS = 3_000L
        const val POLL_INTERVAL_MS = 250L
    }
}
