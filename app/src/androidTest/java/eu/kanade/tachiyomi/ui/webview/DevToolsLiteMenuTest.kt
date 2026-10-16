package eu.kanade.tachiyomi.ui.webview

import android.content.Intent
import android.content.pm.ActivityInfo
import android.view.View
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.ReaderActivityFeedbackTestSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import java.lang.ref.WeakReference
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class DevToolsLiteMenuTest {
    private val ui = DevToolsLiteUi
    private val instrumentation = ui.instrumentation
    private val context = instrumentation.targetContext
    private val evaluate = ReaderActivityFeedbackTestSupport::evaluate

    @Test
    fun ordinaryWebViewHasNoDevToolsMenu() {
        val activity = instrumentation.startActivitySync(
            WebViewActivity.newIntent(context, "https://nekori.devtools-test/").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        try {
            ui.click(context.stringResource(MR.strings.action_menu_overflow_description))
            assertTrue(ui.texts().none { it == context.stringResource(TDMR.strings.action_debug_devtools_lite) })
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test
    fun readerSplitOverlayResizeSwapAndRotationKeepOneFrontend() {
        val activity = ui.startReader()
        val target = ReaderActivityFeedbackTestSupport.findReaderWebView(activity)
        val pane = activity.findViewById<View>(R.id.reader_pane)
        var debugger: WebView? = null
        try {
            ui.awaitJs(target, "Boolean(window.nekori?.config?.documentId)")
            instrumentation.runOnMainSync { WebView.setWebContentsDebuggingEnabled(false) }
            ui.click(context.stringResource(MR.strings.action_menu_overflow_description))
            val texts = ui.texts()
            val debugLabel = context.stringResource(TDMR.strings.action_debug_devtools_lite)
            assertTrue("Debugger must be the final menu item", texts.last() == debugLabel)
            ui.click(debugLabel)
            ui.await("Compact resize bar remains draggable") {
                val handle = ui.semantics().firstOrNull {
                    ui.matches(it, context.stringResource(TDMR.strings.devtools_resize))
                }
                handle != null && abs(handle.boundsInWindow.height - 28 * context.resources.displayMetrics.density) < 2
            }
            val frontend = ui.frontend()
            debugger = frontend
            ui.awaitJs(frontend, "Boolean(window.runtime) && !document.querySelector('iframe')")
            ui.awaitJs(frontend, "Boolean(${ui.frontendElement("#tab-elements")})")
            ui.tap(frontend, ui.frontendElement("#tab-elements"))
            ui.awaitJs(frontend, "Boolean((${ui.frontendElement(".webkit-html-tag")})?.getBoundingClientRect().height)")
            evaluate(frontend, "window.__frontendProbe = 42")
            evaluate(target, "window.__backendProbe = window[Symbol.for('nekori.devtools.backend')]")
            expectReader(pane, 0.5f)
            val menuVisible = activity.viewModel.state.value.menuVisible
            ui.tapCenter(target)
            ui.await("The uncovered Reader receives touch") {
                activity.viewModel.state.value.menuVisible != menuVisible
            }
            ui.tapCenter(target)
            ui.await("Reader chrome remains usable in split mode") {
                activity.viewModel.state.value.menuVisible ==
                    menuVisible
            }
            val initialHeight = pane.height
            ui.drag(-150f)
            ui.await("Dragging resizes the native Reader") { pane.height < initialHeight - 50 }
            ui.progress(-1f)
            expectReader(pane, 0.9f)
            ui.progress(2f)
            expectReader(pane, 0.1f)
            ui.progress(0.5f)
            ui.click(context.stringResource(TDMR.strings.devtools_overlay))
            expectReader(pane, 1f)
            val overlayHeight = frontend.height
            ui.drag(100f)
            ui.await("Overlay drag resizes only DevTools") { frontend.height < overlayHeight - 30 }
            expectReader(pane, 1f)
            ReaderActivityFeedbackTestSupport.captureScreenshot(activity, frontend, "devtools-reader-overlay.png")
            ui.click(context.stringResource(TDMR.strings.devtools_swap))
            expectReader(pane, 1f)
            ui.click(context.stringResource(TDMR.strings.devtools_split))
            ui.progress(0.5f)
            expectReader(pane, 0.5f, top = true)
            ReaderActivityFeedbackTestSupport.captureScreenshot(activity, frontend, "devtools-reader-split-top.png")
            instrumentation.runOnMainSync { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            ui.await("Landscape") { pane.rootView.width > pane.rootView.height }
            expectReader(pane, 0.5f, top = true)
            assertTrue(ui.frontend() === frontend)
            assertEquals("42", evaluate(frontend, "window.__frontendProbe"))
            ReaderActivityFeedbackTestSupport.captureScreenshot(activity, frontend, "devtools-reader-landscape.png")
            instrumentation.runOnMainSync { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            ui.await("Portrait") { pane.rootView.height > pane.rootView.width }
            ui.click(context.stringResource(TDMR.strings.devtools_swap))
            ui.openDevTools()
            assertTrue(ui.frontend() === frontend)
            assertEquals("42", evaluate(frontend, "window.__frontendProbe"))
            ui.awaitJs(target, "Object.keys(window).filter(k => k.startsWith('__nekoriDevTools_')).length === 1")
            ReaderActivityFeedbackTestSupport.captureScreenshot(activity, frontend, "devtools-reader-split.png")
            ui.tap(frontend, ui.frontendElement(".close-devtools"))
            ui.await("Closing restores the full Reader") {
                !frontend.isAttachedToWindow &&
                    pane.height == pane.rootView.height
            }
            ui.awaitJs(target, "Object.keys(window).filter(k => k.startsWith('__nekoriDevTools_')).length === 0")
            ui.openDevTools()
            val reopened = ui.frontend()
            assertTrue(reopened !== frontend)
            ui.awaitJs(target, "window[Symbol.for('nekori.devtools.backend')] === window.__backendProbe")
            instrumentation.runOnMainSync { activity.onBackPressedDispatcher.onBackPressed() }
            ui.await("Back closes DevTools without leaving Reader") {
                !reopened.isAttachedToWindow &&
                    !activity.isFinishing
            }
            expectReader(pane, 1f)
        } catch (failure: Throwable) {
            debugger?.let { ReaderActivityFeedbackTestSupport.captureScreenshot(activity, it, "devtools-failure.png") }
            throw failure
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test
    fun closingAndLeavingReaderReleaseFrontends() {
        val released = releaseFrontends()
        ui.await("Destroyed Reader and frontend WebViews must be collectable") {
            Runtime.getRuntime().gc()
            released.all { it.get() == null }
        }
    }

    private fun releaseFrontends(): List<WeakReference<out Any>> {
        val activity = ui.startReader()
        val target = ReaderActivityFeedbackTestSupport.findReaderWebView(activity)
        val released = mutableListOf<WeakReference<out Any>>(WeakReference(activity))
        try {
            ui.awaitJs(target, "Boolean(window.nekori?.config?.documentId)")
            repeat(4) { released += openAndClose(activity) }
            released += openAndLeave(activity)
            return released
        } finally {
            instrumentation.runOnMainSync { if (!activity.isFinishing) activity.finish() }
        }
    }

    private fun openAndClose(activity: ReaderActivity): WeakReference<WebView> {
        ui.openDevTools()
        val frontend = ui.frontend()
        ui.awaitJs(frontend, "Boolean(window.runtime)")
        val weak = WeakReference(frontend)
        instrumentation.runOnMainSync { activity.onBackPressedDispatcher.onBackPressed() }
        ui.await("Closed frontend detached") { !frontend.isAttachedToWindow }
        return weak
    }

    private fun openAndLeave(activity: ReaderActivity): WeakReference<WebView> {
        ui.openDevTools()
        val frontend = ui.frontend()
        val weak = WeakReference(frontend)
        instrumentation.runOnMainSync { activity.finish() }
        ui.await("Reader destroyed") { activity.isDestroyed && !frontend.isAttachedToWindow }
        return weak
    }

    private fun expectReader(pane: View, fraction: Float, top: Boolean = false) = ui.await(
        "Reader ratio $fraction, top=$top",
    ) {
        val height = (pane.parent as View).height
        abs(pane.height - height * fraction) <= 3 && abs(pane.top - if (top) height - pane.height else 0) <= 3
    }
}
