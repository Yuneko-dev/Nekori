package eu.kanade.tachiyomi.ui.webview

import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.webkit.WebView
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.ReaderActivityFeedbackTestSupport
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertTrue
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import java.util.concurrent.atomic.AtomicReference

@OptIn(InternalComposeUiApi::class)
internal object DevToolsLiteUi {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val evaluate = ReaderActivityFeedbackTestSupport::evaluate

    fun startReader(): ReaderActivity {
        val activity = instrumentation.startActivitySync(
            ReaderActivity.newPreviewIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as ReaderActivity
        await("Reader menu available") { activity.viewModel.state.value.viewer != null }
        instrumentation.runOnMainSync {
            if (!activity.viewModel.state.value.menuVisible) activity.toggleMenu()
            WebView.setWebContentsDebuggingEnabled(false)
        }
        return activity
    }
    fun openDevTools() {
        click(context.stringResource(MR.strings.action_menu_overflow_description))
        click(context.stringResource(TDMR.strings.action_debug_devtools_lite))
    }

    fun frontend(): WebView {
        val result = AtomicReference<WebView>()
        await("Docked frontend attached to the app window") {
            val view = WindowInspector.getGlobalWindowViews().flatMap(::webViews)
                .find { it.url?.startsWith(DevToolsLiteSession.ORIGIN) == true }
            if (view != null) result.set(view)
            view != null && view.height > 0 && view.width > 0
        }
        return requireNotNull(result.get())
    }

    fun frontendElement(selector: String) = """
        (() => {
          const find = root => root.querySelector('$selector') ||
            [...root.querySelectorAll('*')].map(e => e.shadowRoot && find(e.shadowRoot)).find(Boolean);
          return find(document);
        })()
    """.trimIndent()

    fun tap(webView: WebView, element: String) {
        val result =
            evaluate(
                webView,
                """
            (() => {
              const el = ($element), r = el.getBoundingClientRect();
              const frame = el.ownerDocument.defaultView.frameElement?.getBoundingClientRect();
              return JSON.stringify({x:((frame?.left || 0)+r.left+r.width/2)/innerWidth,
                y:((frame?.top || 0)+r.top+r.height/2)/innerHeight});
            })()
                """.trimIndent(),
            )
        val point = JSONObject(JSONTokener(result).nextValue() as String)
        val location = IntArray(2)
        instrumentation.runOnMainSync { webView.getLocationOnScreen(location) }
        val x = location[0] + (point.getDouble("x") * webView.width).toFloat()
        val y = location[1] + (point.getDouble("y") * webView.height).toFloat()
        touch(x, y)
    }

    fun awaitJs(webView: WebView, expression: String) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (evaluate(webView, expression) == "true") return
            SystemClock.sleep(100)
        }
        val layout =
            evaluate(
                webView,
                "JSON.stringify({viewport:[innerWidth,innerHeight],body:document.body.getBoundingClientRect().toJSON()})",
            )
        error("WebView did not render: $expression; layout=$layout")
    }

    // Query this process's real Compose roots, so the test does not depend on the phone being unlocked.
    fun click(label: String) = await("Clickable menu item: $label") {
        val node = semantics().firstOrNull { matches(it, label) } ?: return@await false
        var clickable: SemanticsNode? = node
        while (clickable != null) {
            val action = clickable.config.getOrNull(SemanticsActions.OnClick)?.action
            if (action != null) return@await action()
            clickable = clickable.parent
        }
        false
    }

    fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        val done = AtomicReference(false)
        while (!done.get() && SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync { done.set(condition()) }
            if (!done.get()) SystemClock.sleep(100)
        }
        assertTrue(label, done.get())
    }

    fun semantics(): List<SemanticsNode> {
        fun children(view: View): List<SemanticsNode> = when (view) {
            is ViewRootForTest -> listOf(view.semanticsOwner.rootSemanticsNode)
            is ViewGroup -> (0 until view.childCount).flatMap { children(view.getChildAt(it)) }
            else -> emptyList()
        }
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        return WindowInspector.getGlobalWindowViews().asReversed().flatMap(::children).flatMap(::flatten)
    }

    fun matches(node: SemanticsNode, label: String) =
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true ||
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true

    fun webViews(view: View): List<WebView> = when (view) {
        is WebView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { webViews(view.getChildAt(it)) }
        else -> emptyList()
    }

    fun texts(): List<String> {
        val result = AtomicReference<List<String>>()
        instrumentation.runOnMainSync {
            val popup = semantics().firstOrNull { it.config.contains(SemanticsProperties.IsPopup) }
            fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
            val nodes = popup?.let(::flatten) ?: semantics()
            result.set(
                nodes.flatMap {
                    it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text }
                },
            )
        }
        return result.get()
    }

    fun progress(value: Float) = await("Accessible resize action") {
        semantics().firstOrNull { matches(it, context.stringResource(TDMR.strings.devtools_resize)) }
            ?.config?.getOrNull(SemanticsActions.SetProgress)?.action?.invoke(value) == true
    }

    fun tapCenter(view: View) {
        val location = IntArray(2)
        instrumentation.runOnMainSync { view.getLocationOnScreen(location) }
        touch(location[0] + view.width / 2f, location[1] + view.height / 2f)
    }

    fun drag(delta: Float) {
        val point = AtomicReference<Pair<Float, Float>>()
        await("Resize handle") {
            val bounds = semantics().firstOrNull { matches(it, context.stringResource(TDMR.strings.devtools_resize)) }
                ?.boundsInWindow ?: return@await false
            point.set(bounds.center.x to bounds.center.y)
            true
        }
        val (x, y) = point.get()
        touch(x, y, delta)
    }

    private fun touch(x: Float, y: Float, delta: Float = 0f) {
        val time = SystemClock.uptimeMillis()
        fun send(action: Int, dy: Float) {
            MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, x, y + dy, 0).also {
                instrumentation.sendPointerSync(it)
                it.recycle()
            }
        }
        send(MotionEvent.ACTION_DOWN, 0f)
        if (delta != 0f) {
            repeat(12) { step ->
                SystemClock.sleep(16)
                send(MotionEvent.ACTION_MOVE, delta * (step + 1) / 12)
            }
        }
        send(MotionEvent.ACTION_UP, delta)
    }
}
