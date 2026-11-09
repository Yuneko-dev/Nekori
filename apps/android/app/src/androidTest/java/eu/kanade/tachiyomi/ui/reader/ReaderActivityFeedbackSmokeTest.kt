package eu.kanade.tachiyomi.ui.reader

import android.content.Intent
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderActivityFeedbackSmokeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun previewShowsDismissableBoundaryAndReadableFatalErrorWithoutChangingSample() {
        var activity: ReaderActivity? = null
        try {
            activity = instrumentation.startActivitySync(
                ReaderActivity.newPreviewIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as ReaderActivity
            val webView = ReaderActivityFeedbackTestSupport.findReaderWebView(activity)
            val initial = ReaderActivityFeedbackTestSupport.awaitState(webView, "preview reader ready") {
                it["ready"] == "true" && it["chapterIds"] == "-2" &&
                    it["sampleText"].orEmpty().contains(ReaderActivityFeedbackTestSupport.SAMPLE_TEXT)
            }
            assertEquals("reader", initial["mode"])
            assertEquals("false", initial["readerPlayer"])
            assertEquals("false", initial["videoJsDefined"])
            assertEquals("false", initial["hlsEngineDefined"])
            assertEquals("0", initial["playerContainerCount"])

            val before = ReaderActivityFeedbackTestSupport.parseState(
                ReaderActivityFeedbackTestSupport.evaluate(webView, ReaderActivityFeedbackTestSupport.BASELINE_SCRIPT),
            )
            val boundaryFailure = IllegalStateException("Instrumented boundary feedback failure")
            ReaderActivityFeedbackTestSupport.invokeViewerFailure(activity, "showNextChapterError", boundaryFailure)
            val boundary = ReaderActivityFeedbackTestSupport.awaitState(
                webView,
                "boundary feedback card",
                ReaderActivityFeedbackTestSupport.BOUNDARY_SCRIPT,
            ) { it["host"] == "true" }
            assertEquals("error", boundary["severity"])
            assertEquals("boundary", boundary["presentation"])
            assertEquals("-2", boundary["chapterId"])
            assertEquals("true", boundary["openShadow"])
            assertEquals("alert", boundary["role"])
            assertEquals("static", boundary["hostPosition"])
            assertEquals("true", boundary["followsProse"])
            assertEquals("boundary card overlaps prose: $boundary", "true", boundary["noOverlap"])
            assertEquals(before["count"], boundary["paragraphCount"])
            assertEquals(before["paragraphs"], boundary["paragraphText"])
            assertEquals(before["bionicCount"], boundary["bionicCount"])
            assertFalse("error text blends into background", boundary["background"] == boundary["textColor"])
            assertEquals(boundary["expectedTextColor"], boundary["textColor"])
            assertEquals(boundary["expectedBorderColor"], boundary["borderColor"])
            assertTrue("boundary message missing", boundary["summary"].orEmpty().contains("boundary feedback failure"))
            assertTrue("dismiss action missing", boundary["dismissLabel"].orEmpty().isNotBlank())
            ReaderActivityFeedbackTestSupport.evaluate(webView, SCROLL_BOUNDARY_SCRIPT)
            ReaderActivityFeedbackTestSupport.awaitState(
                webView,
                "boundary card visible for screenshot",
                ReaderActivityFeedbackTestSupport.BOUNDARY_VISIBLE_SCRIPT,
            ) { it["visible"] == "true" }
            ReaderActivityFeedbackTestSupport.captureScreenshot(activity, webView, SCREENSHOT_BOUNDARY)
            ReaderActivityFeedbackTestSupport.evaluate(webView, DISMISS_SCRIPT)
            ReaderActivityFeedbackTestSupport.awaitState(
                webView,
                "boundary card dismissal",
                ReaderActivityFeedbackTestSupport.HOST_REMOVED_SCRIPT,
            ) { it["host"] == "false" }

            ReaderActivityFeedbackTestSupport.invokeViewerFailure(
                activity,
                "displayError",
                IllegalStateException("Instrumented fatal preview failure"),
            )
            val fatal = ReaderActivityFeedbackTestSupport.awaitState(
                webView,
                "full-page error feedback",
                ReaderActivityFeedbackTestSupport.FULLPAGE_SCRIPT,
            ) {
                it["host"] == "true" && it["ready"] == "true" && it["errorDocument"] == "true"
            }
            Log.i(
                "ReaderFeedbackTest",
                "fatal TTS diagnostic: ${fatal.filterKeys {
                    it.startsWith("tts") || it == "mode"
                }}",
            )
            assertEquals("error", fatal["severity"])
            assertEquals("fullpage", fatal["presentation"])
            assertEquals("error", fatal["mode"])
            assertEquals("false", fatal["ttsEnabled"])
            val ttsDiagnostic = fatal.filterKeys { it.startsWith("tts") }
            assertEquals(
                "TTS controller should be absent in error mode: $ttsDiagnostic",
                "none",
                fatal["ttsControllerDisplay"],
            )
            assertEquals(
                "TTS button should have no layout box in error mode: $ttsDiagnostic",
                "0",
                fatal["ttsButtonRectWidth"],
            )
            assertEquals(
                "TTS button should have no layout box in error mode: $ttsDiagnostic",
                "0",
                fatal["ttsButtonRectHeight"],
            )
            assertEquals("true", fatal["openShadow"])
            assertEquals("true", fatal["detailsOpen"])
            assertTrue(
                "visible trace lost the injected failure",
                fatal["traceText"].orEmpty().contains("Instrumented fatal preview failure"),
            )
            assertTrue(
                "visible trace lost its originating stack",
                fatal["traceText"].orEmpty().contains("ReaderActivityFeedbackSmokeTest"),
            )
            assertTrue("retry control missing", fatal["retryLabel"].orEmpty().isNotBlank())
            assertTrue("copy control missing", fatal["copyLabel"].orEmpty().isNotBlank())
            assertFalse("error text blends into background", fatal["background"] == fatal["textColor"])
            assertEquals(fatal["expectedTextColor"], fatal["textColor"])
            ReaderActivityFeedbackTestSupport.captureScreenshot(activity, webView, SCREENSHOT_FATAL)
            ReaderActivityFeedbackTestSupport.evaluate(webView, RETRY_SCRIPT)
            val restored = ReaderActivityFeedbackTestSupport.awaitState(webView, "sample retry") {
                it["ready"] == "true" && it["mode"] == "reader" && it["chapterIds"] == "-2" &&
                    it["sampleText"].orEmpty().contains(ReaderActivityFeedbackTestSupport.SAMPLE_TEXT)
            }
            assertFalse("retry left the full-page error document mounted", restored["errorDocument"] == "true")
            assertEquals("false", restored["readerPlayer"])
            assertEquals("0", restored["playerContainerCount"])
            Log.i("ReaderFeedbackTest", "preview reader feedback and retry acceptance passed")
        } finally {
            activity?.let { running -> instrumentation.runOnMainSync { running.finish() } }
        }
    }

    private companion object {
        const val SCREENSHOT_BOUNDARY = "reader-feedback-boundary.png"
        const val SCREENSHOT_FATAL = "reader-feedback-fatal.png"
        const val SCROLL_BOUNDARY_SCRIPT = """
            (function() {
              var card = document.querySelector('nekori-reader-feedback');
              if (card && window.nekori.layout.enabled) window.nekori.layout.revealElement(card);
              else card?.scrollIntoView({block: 'center'});
              return 'scrolled for screenshot';
            })()
        """
        const val DISMISS_SCRIPT = """
            (function() {
              document.querySelector('nekori-reader-feedback')?.shadowRoot?.querySelector('.action-dismiss')?.click();
              return 'dismissed';
            })()
        """
        const val RETRY_SCRIPT = """
            (function() {
              document.querySelector('#nekori-reader nekori-reader-feedback')?.shadowRoot?.querySelector('.action-retry')?.click();
              return 'retry';
            })()
        """
    }
}
