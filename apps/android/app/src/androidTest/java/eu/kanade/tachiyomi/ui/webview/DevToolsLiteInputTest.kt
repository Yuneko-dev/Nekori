package eu.kanade.tachiyomi.ui.webview

import android.view.KeyEvent
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.reader.ReaderActivityFeedbackTestSupport
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class DevToolsLiteInputTest {
    @Test
    fun consoleTypingAndImeStayInsideDevTools() {
        val ui = DevToolsLiteUi
        val activity = ui.startReader()
        val target = ReaderActivityFeedbackTestSupport.findReaderWebView(activity)
        val pane = activity.findViewById<View>(R.id.reader_pane)
        try {
            ui.awaitJs(target, "Boolean(window.nekori?.config?.documentId)")
            ui.openDevTools()
            val frontend = ui.frontend()
            ui.awaitJs(frontend, "Boolean(${ui.frontendElement("#tab-console")})")
            ui.tap(frontend, ui.frontendElement("#tab-console"))
            val editor = ui.frontendElement(".cm-content")
            ui.awaitJs(frontend, "Boolean($editor)")
            ui.tap(frontend, editor)
            ui.await("IME shows for Console") {
                ViewCompat.getRootWindowInsets(frontend)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
            ui.await("Both split panes stay visible with IME") {
                pane.height > 0 && frontend.height > 0 && pane.height < (pane.parent as View).height
            }
            ui.instrumentation.sendStringSync("window.inputProbe = 17")
            ui.instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
            ui.awaitJs(target, "window.inputProbe === 17")
            assertTrue("Frontend owns keyboard focus", frontend.hasFocus())
            ReaderActivityFeedbackTestSupport.captureScreenshot(activity, frontend, "devtools-reader-ime.png")
            ui.tap(frontend, ui.frontendElement(".close-devtools"))
            ui.await("Closing hides IME and restores Reader") {
                !frontend.isAttachedToWindow && pane.height == (pane.parent as View).height &&
                    ViewCompat.getRootWindowInsets(pane)?.isVisible(WindowInsetsCompat.Type.ime()) != true
            }
        } finally {
            ui.instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
