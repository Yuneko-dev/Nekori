package eu.kanade.tachiyomi.ui.reader

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.domain.source.interactor.GetIncognitoState
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
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
class ReaderActivityPreviewMediaSmokeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun previewPlaysClearDashInRealReaderActivity() {
        var activity: Activity? = null
        try {
            activity = instrumentation.startActivitySync(
                ReaderActivity.newPreviewIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            val webView = requireNotNull(findReaderWebView(activity)) { "preview did not create a reader WebView" }
            awaitState(webView) { it["ready"] == "true" && it["chapterIds"] == "-2" }

            loadEditedChapter(activity, "mpd", DASH_MANIFEST)
            val state = awaitState(webView) {
                it["ready"] == "true" && it["videoType"] == "mpd" && it["player"] == "true" &&
                    it["chapterText"].orEmpty().contains("Preview media fixture: mpd")
            }
            assertEquals("reader layout should be disabled for video chapters", "false", state["layoutEnabled"])
            assertEquals("video chapters must not use infinite scroll", "false", state["infinite"])
            awaitPlayback(webView, "mpd")
        } finally {
            activity?.let { running -> instrumentation.runOnMainSync { running.finish() } }
        }
    }

    @Test
    fun previewPlaysDirectVideoTypesAndRestoresSampleWithoutSavingReaderState() {
        var activity: Activity? = null
        try {
            activity = instrumentation.startActivitySync(
                ReaderActivity.newPreviewIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            val webView = findReaderWebView(activity)
            assertNotNull("preview did not create a reader WebView", webView)
            val reader = requireNotNull(webView)
            awaitState(reader) { it["ready"] == "true" && it["chapterIds"] == "-2" }

            val mediaCases = listOf(
                "m3u8" to HLS_MANIFEST,
                "mpd" to DASH_MANIFEST,
            )
            for ((type, url) in mediaCases) {
                loadEditedChapter(activity, type, url)
                val state = awaitState(reader) {
                    it["ready"] == "true" && it["videoType"] == type && it["player"] == "true" &&
                        it["chapterText"].orEmpty().contains("Preview media fixture: $type")
                }
                assertEquals("reader layout should be disabled for video chapters", "false", state["layoutEnabled"])
                assertEquals("video chapters must not use infinite scroll", "false", state["infinite"])
                awaitPlayback(reader, type)
            }

            val original = context.assets.open(NovelReaderPreview.ASSET).bufferedReader().use { it.readText() }
            savePreviewHtml(activity, original)
            reloadCurrentChapter(activity)
            val restored = awaitState(reader) {
                it["ready"] == "true" && it["chapterIds"] == "-2" && it["videoType"].isNullOrEmpty()
            }
            assertEquals("reader", restored["mode"])
            assertTrue("preview sample was not restored", restored["chapterText"].orEmpty().contains(SAMPLE_TEXT))
            assertEquals("text preview must not retain a player", "false", restored["player"])
            assertEquals("returning to prose must remove the old video surface", "false", restored["playerContainer"])
        } finally {
            activity?.let { running -> instrumentation.runOnMainSync { running.finish() } }
        }
    }

    @Test
    fun previewWidevineRespectsIncognitoAndUsesRealProtectedMediaPath() {
        var activity: Activity? = null
        try {
            activity = instrumentation.startActivitySync(
                ReaderActivity.newPreviewIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            val webView = findReaderWebView(activity)
            assertNotNull("preview did not create a reader WebView", webView)
            val reader = requireNotNull(webView)
            awaitState(reader) { it["ready"] == "true" && it["chapterIds"] == "-2" }

            val readerActivity = activity as ReaderActivity
            val incognito = Injekt.get<GetIncognitoState>().await(readerActivity.viewModel.getSource()?.id)
            loadEditedChapter(readerActivity, "mpd", WIDEVINE_MANIFEST, mode = "manual")
            val videoState = awaitState(reader) {
                it["ready"] == "true" && it["videoType"] == "mpd" && it["player"] == "true"
            }
            assertEquals("false", videoState["layoutEnabled"])
            assertEquals("false", videoState["infinite"])
            evaluate(reader, WIDEVINE_START_SCRIPT)
            val result = awaitProtectedPlayback(reader)
            Log.i("ReaderMediaTest", "Widevine incognito=$incognito result=$result")
            if (incognito) {
                assertTrue(
                    "native protected-media request did not honor configured incognito: $result",
                    result.startsWith(INCOGNITO_REFUSAL),
                )
            } else {
                assertEquals(
                    "Widevine playback should advance when the existing policy grants access",
                    "playing",
                    result,
                )
            }
        } finally {
            activity?.let { running -> instrumentation.runOnMainSync { running.finish() } }
        }
    }

    private fun loadEditedChapter(
        activity: Activity,
        type: String,
        url: String,
        mode: String = "direct",
        disableProgress: Boolean = false,
    ) {
        val disableProgressMeta = if (disableProgress) "<meta id=\"nekori-video-disable-progress\">" else ""
        val html = """
            <!doctype html><html><head>
            <meta name="nekori-chapter-type" content="video">
            <meta name="nekori-video-mode" content="$mode">
            <meta name="nekori-video-type" content="$type">
            <meta name="nekori-video-url" content="$url">
            $disableProgressMeta
            </head><body><p>Preview media fixture: $type</p></body></html>
        """.trimIndent()
        savePreviewHtml(activity, html)
        reloadCurrentChapter(activity)
    }

    private fun savePreviewHtml(activity: Activity, html: String) {
        val edit = buildJsonObject {
            put("id", "-2")
            put("content", html)
        }
        val payload = buildJsonArray { add(edit) }.toString()
        instrumentation.runOnMainSync { (activity as ReaderActivity).viewModel.saveEditedChapterContent(payload) }
        val deadline = SystemClock.uptimeMillis() + EDIT_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val editedText = previewEditedText(activity)
            if (editedText == html) return
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        error("preview editor did not apply its in-memory edit")
    }

    /** Reads only the transient NovelReaderPreview instance to synchronize its existing async save API. */
    private fun previewEditedText(activity: Activity): String? {
        val viewModel = (activity as ReaderActivity).viewModel
        val previewGetter = viewModel.javaClass.getDeclaredMethod("getPreview").apply { isAccessible = true }
        val preview = previewGetter.invoke(viewModel) ?: return null
        val editedTextGetter = preview.javaClass.getMethod("getEditedText")
        return editedTextGetter.invoke(preview) as? String
    }

    private fun reloadCurrentChapter(activity: Activity) {
        instrumentation.runOnMainSync { (activity as ReaderActivity).viewModel.reloadChapter(fromSource = false) }
    }

    private fun findReaderWebView(activity: Activity): WebView? {
        val found = AtomicReference<List<WebView>>(emptyList())
        val deadline = SystemClock.uptimeMillis() + ACTIVITY_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline && found.get().isEmpty()) {
            instrumentation.runOnMainSync { found.set(collectWebViews(activity.window.decorView)) }
            if (found.get().isEmpty()) SystemClock.sleep(POLL_INTERVAL_MS)
        }
        return found.get().firstOrNull()
    }

    private fun collectWebViews(view: View): List<WebView> = when (view) {
        is WebView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { collectWebViews(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun awaitState(webView: WebView, condition: (Map<String, String>) -> Boolean): Map<String, String> {
        val deadline = SystemClock.uptimeMillis() + STATE_TIMEOUT_MS
        var lastState = emptyMap<String, String>()
        while (SystemClock.uptimeMillis() < deadline) {
            val encoded = evaluate(webView, STATE_SCRIPT)
            if (encoded != "null") {
                val values = kotlinx.serialization.json.Json.parseToJsonElement(
                    kotlinx.serialization.json.Json.parseToJsonElement(encoded).jsonPrimitive.content,
                )
                    .jsonObject.mapValues { (_, value) -> value.jsonPrimitive.content }
                lastState = values
                if (condition(values)) return values
                if (!values["readyError"].isNullOrBlank()) {
                    error("reader initialization failed: ${values["readyError"]}; state=$values")
                }
            }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        error("reader state did not reach expected condition; state=$lastState")
    }

    private fun awaitPlayback(webView: WebView, type: String) {
        val deadline = SystemClock.uptimeMillis() + PLAYBACK_TIMEOUT_MS
        var last = ""
        var priorTime: Double? = null
        while (SystemClock.uptimeMillis() < deadline) {
            val state = evaluate(webView, PLAYBACK_SCRIPT)
            if (state != "null") {
                val values = kotlinx.serialization.json.Json.parseToJsonElement(
                    kotlinx.serialization.json.Json.parseToJsonElement(state).jsonPrimitive.content,
                ).jsonObject
                val current = values["currentTime"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
                last = values.toString()
                if (priorTime?.let { current > it + 0.05 } == true && current > 0.15) return
                priorTime = current
            }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        error("$type media did not advance playback time within ${PLAYBACK_TIMEOUT_MS}ms; last=$last")
    }

    private fun awaitProtectedPlayback(webView: WebView): String {
        val deadline = SystemClock.uptimeMillis() + DRM_TIMEOUT_MS
        var last = ""
        var priorTime: Double? = null
        while (SystemClock.uptimeMillis() < deadline) {
            val encoded = evaluate(webView, WIDEVINE_STATE_SCRIPT)
            if (encoded != "null") {
                val values = kotlinx.serialization.json.Json.parseToJsonElement(
                    kotlinx.serialization.json.Json.parseToJsonElement(encoded).jsonPrimitive.content,
                ).jsonObject
                val error = values["error"]?.jsonPrimitive?.content.orEmpty()
                val current = values["currentTime"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
                last = values.toString()
                if (error.contains(INCOGNITO_REFUSAL)) return error
                if (priorTime?.let { current > it + 0.05 } == true && current > 0.15) return "playing"
                priorTime = current
            }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        error("Widevine playback neither advanced nor reported the expected incognito refusal; last=$last")
    }

    private fun evaluate(webView: WebView, script: String): String {
        val completed = CountDownLatch(1)
        val result = AtomicReference<String>()
        instrumentation.runOnMainSync {
            webView.evaluateJavascript(script) { value ->
                result.set(value ?: "null")
                completed.countDown()
            }
        }
        assertTrue("reader WebView JavaScript timed out", completed.await(EVALUATE_TIMEOUT_MS, TimeUnit.MILLISECONDS))
        return requireNotNull(result.get())
    }

    private companion object {
        const val SAMPLE_TEXT = "Lorem ipsum dolor sit amet, consectetuer adipiscing elit."
        const val HLS_MANIFEST = "https://storage.googleapis.com/shaka-demo-assets/angel-one-hls/hls.m3u8"
        const val DASH_MANIFEST = "https://storage.googleapis.com/shaka-demo-assets/angel-one/dash.mpd"
        const val WIDEVINE_MANIFEST = "https://storage.googleapis.com/shaka-demo-assets/sintel-widevine/dash.mpd"
        const val WIDEVINE_LICENSE = "https://cwip-shaka-proxy.appspot.com/no_auth"
        const val INCOGNITO_REFUSAL =
            "DRM video needs the device media identifier, which is not shared while incognito is on"
        const val ACTIVITY_TIMEOUT_MS = 15_000L
        const val STATE_TIMEOUT_MS = 30_000L
        const val PLAYBACK_TIMEOUT_MS = 15_000L
        const val EDIT_TIMEOUT_MS = 5_000L
        const val DRM_TIMEOUT_MS = 20_000L
        const val EVALUATE_TIMEOUT_MS = 3_000L
        const val POLL_INTERVAL_MS = 250L
        const val STATE_SCRIPT = """
            (function() {
              var api = window.nekori;
              if (!api || !api.config || !api.config.chapter) return null;
              return JSON.stringify({
                ready: String(api.ready && api.config.mode === 'reader'),
                mode: api.config.mode,
                chapterIds: (api.config.chapters || []).map(function(chapter) { return String(chapter.id); }).join(','),
                chapterText: document.querySelector('nekori-chapter')?.textContent || '',
                videoType: api.config.player?.video?.type || '',
                videoConfig: JSON.stringify(api.config.player?.video || {}),
                player: String(!!api.player),
                playerContainer: String(!!document.querySelector('#nekori-player-container')),
                layoutEnabled: String(api.config.layout?.enabled),
                infinite: String(api.config.runtime?.infinite),
                readyError: window.__readerMediaReadyError || ''
              });
            })()
        """
        const val PLAYBACK_SCRIPT = """
            (function() {
              var video = document.querySelector(
                '#nekori-player-container video, #nekori-player-container hlsjs-video, #nekori-player-container dash-video'
              );
              return JSON.stringify({
                player: String(!!window.nekori?.player),
                currentTime: video ? video.currentTime : 0,
                readyState: video ? video.readyState : -1,
                error: video && video.error ? video.error.code : 0,
                src: video ? video.currentSrc || video.src : '',
                element: video ? video.outerHTML.slice(0, 300) :
                  document.querySelector('#nekori-player-container')?.innerHTML.slice(0, 600) || ''
              });
            })()
        """
        val WIDEVINE_START_SCRIPT = """
            (function() {
              var api = window.nekori;
              window.__readerMediaDrmErrors = [];
              api.events.subscribe('reader.error', function(event) {
                window.__readerMediaDrmErrors.push(String(event.message || ''));
              });
              api.player.playDash('$WIDEVINE_MANIFEST', {
                protectionData: { 'com.widevine.alpha': { serverURL: '$WIDEVINE_LICENSE' } }
              }).then(function() { window.__readerMediaDrmCallDone = true; }, function(error) {
                window.__readerMediaDrmErrors.push(String(error));
              });
              return 'started';
            })()
        """.trimIndent()
        const val WIDEVINE_STATE_SCRIPT = """
            (function() {
              var media = document.querySelector('#nekori-player-container dash-video');
              return JSON.stringify({
                currentTime: media ? media.currentTime : 0,
                error: (window.__readerMediaDrmErrors || []).join('|'),
                mediaError: media && media.error ? media.error.message || media.error.code : ''
              });
            })()
        """
    }
}
