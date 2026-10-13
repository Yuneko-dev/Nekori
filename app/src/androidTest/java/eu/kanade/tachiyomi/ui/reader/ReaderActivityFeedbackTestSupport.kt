package eu.kanade.tachiyomi.ui.reader

import android.app.Activity
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewViewer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal object ReaderActivityFeedbackTestSupport {
    const val SAMPLE_TEXT = "Lorem ipsum dolor sit amet, consectetuer adipiscing elit."
    const val READER_TIMEOUT_MS = 20_000L
    const val EVALUATE_TIMEOUT_MS = 3_000L
    const val POLL_INTERVAL_MS = 100L

    fun findReaderWebView(activity: Activity): WebView {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val found = AtomicReference<List<WebView>>(emptyList())
        val deadline = SystemClock.uptimeMillis() + READER_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline && found.get().isEmpty()) {
            instrumentation.runOnMainSync { found.set(collectWebViews(activity.window.decorView)) }
            if (found.get().isEmpty()) SystemClock.sleep(POLL_INTERVAL_MS)
        }
        return requireNotNull(found.get().firstOrNull()) { "ReaderActivity preview did not create its WebView" }
    }

    fun collectWebViews(view: View): List<WebView> = when (view) {
        is WebView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { collectWebViews(view.getChildAt(it)) }
        else -> emptyList()
    }

    fun awaitState(
        webView: WebView,
        label: String,
        script: String = STATE_SCRIPT,
        condition: (Map<String, String>) -> Boolean,
    ): Map<String, String> {
        val deadline = SystemClock.uptimeMillis() + READER_TIMEOUT_MS
        var last = emptyMap<String, String>()
        while (SystemClock.uptimeMillis() < deadline) {
            val encoded = evaluate(webView, script)
            if (encoded != "null") {
                last = parseState(encoded)
                if (condition(last)) return last
            }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        error("$label was not reached; last state=$last")
    }

    fun evaluate(webView: WebView, script: String): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
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

    fun parseState(encoded: String): Map<String, String> = Json.parseToJsonElement(
        Json.parseToJsonElement(encoded).jsonPrimitive.content,
    ).jsonObject.mapValues { (_, value) -> value.jsonPrimitive.content }

    fun invokeViewerFailure(activity: ReaderActivity, methodName: String, failure: Throwable) {
        val viewer = requireNotNull(activity.viewModel.state.value.viewer) as? NovelWebViewViewer
            ?: error("ReaderActivity preview is not using NovelWebViewViewer")
        val method = viewer.javaClass.getDeclaredMethod(methodName, Throwable::class.java).apply { isAccessible = true }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { method.invoke(viewer, failure) }
    }

    fun captureScreenshot(activity: Activity, webView: WebView, name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val visualStateReady = CountDownLatch(1)
        instrumentation.runOnMainSync {
            webView.postVisualStateCallback(
                SystemClock.uptimeMillis(),
                object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        visualStateReady.countDown()
                    }
                },
            )
        }
        check(visualStateReady.await(EVALUATE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            "reader WebView did not present the requested screenshot state"
        }
        // The WebView callback precedes the compositor presenting that frame to a device screenshot.
        instrumentation.waitForIdleSync()
        SystemClock.sleep(250)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val file = File(requireNotNull(activity.externalCacheDir), name)
        file.outputStream().use { output -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) }
        Log.i("ReaderFeedbackTest", "screenshot=${file.absolutePath}")
    }

    private const val STATE_SCRIPT = """
        (function() {
          var api = window.nekori;
          if (!api || !api.config) return JSON.stringify({ready:'false'});
          if (!window.__feedbackSmokeReadyHooked) {
            window.__feedbackSmokeReadyHooked = true;
            window.__feedbackSmokeReady = false;
            api.ready.then(function() { window.__feedbackSmokeReady = true; });
          }
          return JSON.stringify({
            ready: String(window.__feedbackSmokeReady),
            mode: api.config.mode,
            chapterId: String(api.config.chapter && api.config.chapter.id),
            chapterIds: (api.config.chapters || []).map(function(chapter) { return String(chapter.id); }).join(','),
            sampleText: document.querySelector('nekori-chapter')?.textContent || '',
            readerPlayer: String(!!api.player),
            videoJsDefined: String(!!customElements.get('video-player')),
            hlsEngineDefined: String(!!window.Hls),
            playerContainerCount: String(document.querySelectorAll('#nekori-player-container').length),
            errorDocument: String(document.body?.hasAttribute('data-nekori-error-document'))
          });
        })()
    """

    const val BASELINE_SCRIPT = """
        (function() {
          var chapter = document.querySelector('nekori-chapter[data-nekori-chapter-id="-2"]');
          var selector = 'p,li,blockquote,h1,h2,h3,h4,h5,h6,pre';
          return JSON.stringify({
            chapterId: String(window.nekori?.config?.chapter?.id),
            chapterIds: (window.nekori?.config?.chapters || []).map(function(chapter) { return String(chapter.id); }).join(','),
            paragraphs: chapter ? Array.from(chapter.querySelectorAll(selector)).map(function(node) { return node.innerText; }).join('\n') : '',
            count: String(chapter ? chapter.querySelectorAll(selector).length : -1),
            bionicCount: String(document.querySelectorAll('[data-nekori-bionic]').length)
          });
        })()
    """

    const val HOST_REMOVED_SCRIPT = """
        (function() {
          return JSON.stringify({host: String(!!document.querySelector('nekori-reader-feedback'))});
        })()
    """

    const val BOUNDARY_VISIBLE_SCRIPT = """
        (function() {
          var host = document.querySelector('nekori-reader-feedback');
          var rect = host?.shadowRoot?.querySelector('.card')?.getBoundingClientRect();
          return JSON.stringify({visible: String(!!rect && rect.width > 0 && rect.height > 0 &&
            rect.left >= 0 && rect.right <= innerWidth && rect.top >= 0 && rect.bottom <= innerHeight)});
        })()
    """

    const val BOUNDARY_SCRIPT = """
        (function() {
          var chapter = document.querySelector('nekori-chapter[data-nekori-chapter-id="-2"]');
          var host = chapter && chapter.querySelector('nekori-reader-feedback');
          var shadow = host && host.shadowRoot;
          var card = shadow && shadow.querySelector('.card');
          var paragraphs = chapter && Array.from(chapter.querySelectorAll('p,li,blockquote,h1,h2,h3,h4,h5,h6,pre'));
          var last = paragraphs && paragraphs[paragraphs.length - 1];
          var style = card && getComputedStyle(card);
          var rootStyle = getComputedStyle(document.documentElement);
          var tokenColor = function(name, fallback) {
            var probe = document.createElement('span');
            probe.style.color = rootStyle.getPropertyValue(name).trim() || fallback;
            document.body.append(probe);
            var result = getComputedStyle(probe).color;
            probe.remove();
            return result;
          };
          return JSON.stringify({
            host: String(!!host), severity: host?.dataset.severity || '', presentation: host?.dataset.presentation || '',
            chapterId: chapter?.getAttribute('data-nekori-chapter-id') || '',
            openShadow: String(!!shadow), role: card?.getAttribute('role') || '',
            hostPosition: host ? getComputedStyle(host).position : '',
            followsProse: String(!!last && !!host && !!(last.compareDocumentPosition(host) & Node.DOCUMENT_POSITION_FOLLOWING)),
            noOverlap: String(!!card && paragraphs.every(function(paragraph) {
              return Array.from(paragraph.getClientRects()).every(function(prose) {
                return Array.from(card.getClientRects()).every(function(box) {
                  return box.right <= prose.left + 1 || box.left >= prose.right - 1 ||
                    box.bottom <= prose.top + 1 || box.top >= prose.bottom - 1;
                });
              });
            })),
            paragraphCount: String(chapter ? chapter.querySelectorAll('p,li,blockquote,h1,h2,h3,h4,h5,h6,pre').length : -1),
            paragraphText: chapter ? Array.from(chapter.querySelectorAll('p,li,blockquote,h1,h2,h3,h4,h5,h6,pre')).map(function(node) { return node.innerText; }).join('\n') : '',
            bionicCount: String(document.querySelectorAll('[data-nekori-bionic]').length),
            background: style?.backgroundColor || '', textColor: style?.color || '', borderColor: style?.borderInlineStartColor || '',
            expectedTextColor: tokenColor('--reader-text-color', 'CanvasText'),
            expectedBorderColor: tokenColor('--md-sys-color-error', '#b3261e'),
            summary: shadow?.querySelector('.summary')?.textContent || '',
            dismissLabel: shadow?.querySelector('.action-dismiss')?.textContent || ''
          });
        })()
    """

    const val FULLPAGE_SCRIPT = """
        (function() {
          var api = window.nekori;
          var host = document.querySelector('#nekori-reader nekori-reader-feedback');
          var shadow = host && host.shadowRoot;
          var card = shadow && shadow.querySelector('.card');
          var details = shadow && shadow.querySelector('details.details');
          var pre = shadow && shadow.querySelector('pre.trace');
          var ttsController = document.getElementById('nekori-tts-controller');
          var ttsButton = ttsController && ttsController.querySelector('button');
          var ttsStyle = ttsController && getComputedStyle(ttsController);
          var ttsButtonStyle = ttsButton && getComputedStyle(ttsButton);
          var ttsButtonRect = ttsButton && ttsButton.getBoundingClientRect();
          var ttsVisibilityRules = [];
          if (ttsButton) Array.from(document.styleSheets).forEach(function(sheet) {
            try {
              Array.from(sheet.cssRules).forEach(function(rule) {
                if (rule.selectorText && rule.style?.visibility && ttsButton.matches(rule.selectorText)) {
                  ttsVisibilityRules.push(rule.selectorText + ': ' + rule.style.visibility);
                }
              });
            } catch (_) {}
          });
          var style = card && getComputedStyle(card);
          var rootStyle = getComputedStyle(document.documentElement);
          var resolveToken = function(name, fallback) {
            var probe = document.createElement('span');
            probe.style.color = rootStyle.getPropertyValue(name).trim() || fallback;
            document.body.append(probe);
            var result = getComputedStyle(probe).color;
            probe.remove();
            return result;
          };
          return JSON.stringify({
            ready: String(!!api?.ready), mode: api?.config?.mode || '',
            errorDocument: String(document.body?.hasAttribute('data-nekori-error-document')),
            host: String(!!host), severity: host?.dataset.severity || '', presentation: host?.dataset.presentation || '',
            openShadow: String(!!shadow), background: style?.backgroundColor || '', textColor: style?.color || '',
            expectedTextColor: resolveToken('--reader-text-color', 'CanvasText'),
            detailsOpen: String(!!details?.open), traceText: pre?.textContent || '',
            ttsEnabled: String(api?.config?.ui?.ttsEnabled),
            ttsControllerClass: ttsController?.className || '',
            ttsControllerVisibility: ttsStyle?.visibility || '',
            ttsControllerDisplay: ttsStyle?.display || '',
            ttsControllerOpacity: ttsStyle?.opacity || '',
            ttsButtonVisibility: ttsButtonStyle?.visibility || '',
            ttsButtonDisplay: ttsButtonStyle?.display || '',
            ttsButtonOpacity: ttsButtonStyle?.opacity || '',
            ttsButtonRectWidth: String(ttsButtonRect?.width || 0),
            ttsButtonRectHeight: String(ttsButtonRect?.height || 0),
            ttsVisibilityRules: ttsVisibilityRules.join(';'),
            retryLabel: shadow?.querySelector('.action-retry')?.textContent || '',
            copyLabel: shadow?.querySelector('.action-copy')?.textContent || ''
          });
        })()
    """
}
