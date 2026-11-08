package eu.kanade.tachiyomi.ui.webview

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.ReaderActivityFeedbackTestSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class DevToolsLiteTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val evaluate = ReaderActivityFeedbackTestSupport::evaluate

    @Test
    fun frontendCannotReachOtherOriginsOrAssets() {
        assertTrue(
            DevToolsLiteSession.isFrontendUrl(
                Uri.parse(
                    "https://nekori.devtools/assets/devtools-lite/front_end/chii_app.html",
                ),
            ),
        )
        listOf(
            "http://nekori.devtools/assets/devtools-lite/android.html",
            "https://example.org/assets/devtools-lite/android.html",
            "https://nekori.devtools:8443/assets/devtools-lite/android.html",
            "https://user@nekori.devtools/assets/devtools-lite/android.html",
            "https://nekori.devtools/assets/novel-reader/reader.js",
            "https://nekori.devtools/assets/devtools-lite/../private",
            "https://nekori.devtools/assets/devtools-lite/%2e%2e/private",
            "https://nekori.devtools/assets/devtools-lite/a%5cb.js",
        ).forEach { assertFalse(it, DevToolsLiteSession.isFrontendUrl(Uri.parse(it))) }
    }

    @Test
    fun readerUsesOfflineDevToolsWithoutRemoteDebugging() {
        verifyReader()
    }

    private fun verifyReader() {
        val activity = instrumentation.startActivitySync(
            ReaderActivity.newPreviewIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        var session: DevToolsLiteSession? = null
        val error = AtomicReference<String?>(null)
        val closed = AtomicReference(false)
        try {
            val target = ReaderActivityFeedbackTestSupport.findReaderWebView(activity)
            await(target, "Boolean(window.nekori?.config?.documentId)")
            instrumentation.runOnMainSync {
                WebView.setWebContentsDebuggingEnabled(false)
                session = DevToolsLiteSession(target, { closed.set(true) }, { error.set(it) }).also {
                    (activity.window.decorView as ViewGroup).addView(it.frontend, ViewGroup.LayoutParams(900, 900))
                    it.start()
                }
            }
            val frontend = requireNotNull(session).frontend
            await(frontend, "Boolean(window.runtime)", error)
            probe(
                frontend,
                """
                const UI = await import('./ui/legacy/legacy.js');
                const SDK = await import('./core/sdk/sdk.js');
                const context = UI.Context.Context.instance().flavor(SDK.RuntimeModel.ExecutionContext);
                if (!context) throw new Error('No target context');
                const result = await context.evaluate({expression:'globalThis.androidDevToolsProbe = 42',
                  returnByValue:true, objectGroup:'console', replMode:true}, false, false);
                return result.object?.value === 42 &&
                  UI.InspectorView.InspectorView.instance().tabbedPane.tabIds().sort().join(',') ===
                    'console,elements,resources,sources' &&
                  !UI.ViewManager.getRegisteredViewExtensions().some(view => view.viewId() === 'elements.layout');
                """.trimIndent(),
                error,
            )
            assertEquals("42", evaluate(target, "globalThis.androidDevToolsProbe"))
            probe(
                frontend,
                """
                const SDK = await import('./core/sdk/sdk.js');
                const target = SDK.TargetManager.TargetManager.instance().primaryPageTarget();
                const {root} = await target.domAgent().invoke_getDocument({depth:2});
                return root.nodeName === '#document';
                """.trimIndent(),
                error,
            )
            assertFalse(closed.get())
            instrumentation.runOnMainSync { requireNotNull(session).close() }
            session = null
            // Closing retains the inspected page and its original reader bridge.
            assertEquals("42", evaluate(target, "globalThis.androidDevToolsProbe"))
            assertEquals("true", evaluate(target, "Boolean(window.nekori?.config?.documentId)"))
            instrumentation.runOnMainSync {
                session = DevToolsLiteSession(target, { closed.set(true) }, { error.set(it) }).also {
                    (activity.window.decorView as ViewGroup).addView(it.frontend, ViewGroup.LayoutParams(900, 900))
                    it.start()
                }
            }
            val reopened = requireNotNull(session).frontend
            await(reopened, "Boolean(window.runtime)", error)
            probe(
                reopened,
                """
                const SDK = await import('./core/sdk/sdk.js');
                return Boolean(SDK.TargetManager.TargetManager.instance().primaryPageTarget());
                """.trimIndent(),
                error,
            )
            instrumentation.runOnMainSync { target.loadUrl("about:blank") }
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (!closed.get() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
            assertTrue("Navigation must end the document-scoped debugger", closed.get())
            assertEquals(null, error.get())
        } finally {
            instrumentation.runOnMainSync {
                session?.close()
                activity.finish()
            }
        }
    }

    private fun probe(frontend: WebView, body: String, error: AtomicReference<String?>) {
        val absoluteBody = body.replace("import('./", "import('https://nekori.devtools/assets/devtools-lite/front_end/")
        val childScript = "(async () => { $absoluteBody })()"
        val script = """
            window.__probeResult = null;
            $childScript
              .then(value => window.__probeResult = String(value), failure => window.__probeResult = String(failure));
        """.trimIndent()
        val deadline = SystemClock.uptimeMillis() + 30_000
        var last = ""
        while (SystemClock.uptimeMillis() < deadline) {
            evaluate(frontend, script)
            SystemClock.sleep(250)
            last = evaluate(frontend, "window.__probeResult")
            if (last == "\"true\"") return
            assertEquals(null, error.get())
        }
        error("DevTools probe failed: $last")
    }

    private fun await(webView: WebView, expression: String, error: AtomicReference<String?> = AtomicReference(null)) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        while (SystemClock.uptimeMillis() < deadline) {
            assertEquals(null, error.get())
            if (evaluate(webView, expression) == "true") return
            SystemClock.sleep(100)
        }
        error("WebView did not become ready: $expression")
    }
}
