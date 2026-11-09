package eu.kanade.tachiyomi.ui.webview

import android.accessibilityservice.AccessibilityServiceInfo
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import eu.kanade.tachiyomi.ui.reader.ReaderActivityFeedbackTestSupport
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.novel.TDMR

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class DevToolsKeyPanelTest {
    @Test
    fun extraKeysAndImeModifiersOnlyReachFrontend() {
        val ui = DevToolsLiteUi
        val activity = ui.startReader()
        val target = ReaderActivityFeedbackTestSupport.findReaderWebView(activity)
        val evaluate = ReaderActivityFeedbackTestSupport::evaluate
        try {
            ui.awaitJs(target, "Boolean(window.nekori?.config?.documentId)")
            evaluate(target, "window.receivedKeys = []; addEventListener('keydown', e => receivedKeys.push(e.key))")
            ui.openDevTools()
            val frontend = ui.frontend() as DevToolsKeyboardWebView
            fun awaitReleased() = ui.await("Modifiers and selected UI release together") {
                frontend.modifiers == 0 && listOf("Ctrl", "Alt").all { label ->
                    ui.semantics().any {
                        ui.matches(it, label) && it.config.getOrNull(SemanticsProperties.Selected) == false
                    }
                }
            }
            ui.awaitJs(frontend, "Boolean(${ui.frontendElement("#tab-console")})")
            ui.tap(frontend, ui.frontendElement("#tab-console"))
            ui.tap(frontend, ui.frontendElement(".cm-content"))
            evaluate(
                frontend,
                """
                window.receivedKeys = [];
                window.receivedKeyUps = [];
                addEventListener('keyup', e => receivedKeyUps.push([e.key, e.ctrlKey, e.altKey]), true);
                window.keyProbe = e => {
                  receivedKeys.push([e.key, e.ctrlKey, e.altKey]);
                  e.preventDefault(); e.stopImmediatePropagation();
                };
                addEventListener('keydown', keyProbe, true);
                """.trimIndent(),
            )
            val toggle = activity.stringResource(TDMR.strings.devtools_keyboard)
            ui.click(toggle)
            val labels = listOf(
                "ESC", "/", "-", "Home", "ArrowUp", "End", "PgUp",
                "Tab", "ArrowLeft", "ArrowDown", "ArrowRight", "PgDn",
            )
            labels.forEach(ui::click)
            ui.awaitJs(
                frontend,
                "JSON.stringify(receivedKeys.map(e => e[0])) === JSON.stringify(['Escape','/','-','Home','ArrowUp','End','PageUp','Tab','ArrowLeft','ArrowDown','ArrowRight','PageDown'])",
            )
            evaluate(frontend, "receivedKeys = []")
            ui.click("Ctrl")
            ui.click("Alt")
            ui.click("ArrowLeft")
            ui.awaitJs(frontend, "receivedKeys.some(e => e[0] === 'ArrowLeft' && e[1] && e[2])")
            awaitReleased()
            ui.click("Ctrl")
            ui.click("Alt")
            fun tapKeyboardKey(label: String) {
                val automation = ui.instrumentation.uiAutomation
                val originalFlags = automation.serviceInfo.flags
                try {
                    automation.serviceInfo = automation.serviceInfo.apply {
                        flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    }
                    fun findKey(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                        node ?: return null
                        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
                        return (0 until node.childCount).firstNotNullOfOrNull { findKey(node.getChild(it)) }
                    }
                    ui.await("Android keyboard window appears") {
                        automation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                    }
                    val bounds = android.graphics.Rect()
                    ui.await("Locate $label on the real Android keyboard") {
                        val keyboard = automation.windows.firstOrNull {
                            it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
                        }
                        val key = findKey(keyboard?.root) ?: return@await false
                        key.getBoundsInScreen(bounds)
                        !bounds.isEmpty
                    }
                    val time = android.os.SystemClock.uptimeMillis()
                    listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP).forEach { action ->
                        val event = android.view.MotionEvent.obtain(
                            time,
                            android.os.SystemClock.uptimeMillis(),
                            action,
                            bounds.exactCenterX(),
                            bounds.exactCenterY(),
                            0,
                        )
                        try {
                            org.junit.Assert.assertTrue(automation.injectInputEvent(event, true))
                        } finally {
                            event.recycle()
                        }
                    }
                } finally {
                    automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
                }
            }
            tapKeyboardKey("a")
            ui.awaitJs(frontend, "receivedKeys.some(e => e[0] === 'a' && e[1] && e[2])")
            awaitReleased()
            evaluate(frontend, "receivedKeys = []")
            lateinit var connection: InputConnection
            ui.instrumentation.runOnMainSync {
                connection = requireNotNull(frontend.onCreateInputConnection(EditorInfo()))
                frontend.modifiers = KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON
                connection.commitText("a", 1)
                org.junit.Assert.assertEquals(0, frontend.modifiers)
                frontend.modifiers = KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON
                connection.setComposingText("x", 1)
                connection.setComposingText("x", 1)
                connection.commitText("x", 1)
                org.junit.Assert.assertEquals(0, frontend.modifiers)
                frontend.modifiers = KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON
                connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Z))
                connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_Z))
                org.junit.Assert.assertEquals(0, frontend.modifiers)
                frontend.modifiers = KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON
                connection.deleteSurroundingText(1, 0)
                org.junit.Assert.assertEquals(0, frontend.modifiers)
                frontend.modifiers = KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON
                connection.deleteSurroundingTextInCodePoints(0, 1)
                org.junit.Assert.assertEquals(0, frontend.modifiers)
                frontend.modifiers = KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON
                connection.performEditorAction(EditorInfo.IME_ACTION_DONE)
                org.junit.Assert.assertEquals(0, frontend.modifiers)
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    frontend.modifiers = KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON
                    connection.setComposingText("b", 1, null)
                    connection.commitText("b", 1, null)
                }
            }
            ui.awaitJs(
                frontend,
                "['a','x','z'].every(k => receivedKeys.filter(e => e[0] === k && e[1] && e[2]).length === 1)",
            )
            ui.awaitJs(
                frontend,
                "['Backspace','Delete','Enter'].every(k => receivedKeys.some(e => e[0] === k && e[1] && e[2]))",
            )
            ui.awaitJs(frontend, "receivedKeyUps.some(e => e[0] === 'z' && e[1] && e[2])")
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                ui.awaitJs(frontend, "receivedKeys.filter(e => e[0] === 'b' && e[1] && e[2]).length === 1")
            }
            awaitReleased()
            ui.click("Ctrl")
            ui.instrumentation.runOnMainSync {
                val time = android.os.SystemClock.uptimeMillis()
                frontend.dispatchKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F8, 0))
                frontend.dispatchKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F8, 1))
                frontend.dispatchKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_F8, 0))
            }
            awaitReleased()
            ui.awaitJs(frontend, "receivedKeys.filter(e => e[0] === 'F8' && e[1] && !e[2]).length === 2")
            ui.awaitJs(frontend, "receivedKeyUps.some(e => e[0] === 'F8' && e[1] && !e[2])")
            evaluate(frontend, "receivedKeys = []")
            ui.click("ArrowRight")
            ui.awaitJs(frontend, "receivedKeys.length === 1 && !receivedKeys[0][1] && !receivedKeys[0][2]")
            ui.click("Ctrl")
            ui.click(toggle)
            ui.await("Hiding keys releases modifiers") { frontend.modifiers == 0 }
            ui.click(toggle)
            ReaderActivityFeedbackTestSupport.captureScreenshot(activity, frontend, "devtools-extra-keys.png")
            ui.awaitJs(target, "receivedKeys.length === 0")
            evaluate(frontend, "removeEventListener('keydown', keyProbe, true)")
            evaluate(
                frontend,
                "window.inputTrace = []; addEventListener('keydown', e => inputTrace.push([e.key,e.ctrlKey,e.altKey,e.composedPath()[0].className]), true)",
            )
            ui.tap(frontend, ui.frontendElement(".cm-content"))
            val clipboard = activity.getSystemService(android.content.ClipboardManager::class.java)
            var previousClip: android.content.ClipData? = null
            ui.instrumentation.runOnMainSync {
                previousClip = clipboard.primaryClip
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("test", "discard this text"))
            }
            try {
                ui.click("Ctrl")
                tapKeyboardKey("v")
                awaitReleased()
                ui.awaitJs(frontend, "${ui.frontendElement(".cm-content")}.textContent === 'discard this text'")
                ui.instrumentation.sendStringSync("!")
                ui.awaitJs(frontend, "${ui.frontendElement(".cm-content")}.textContent === 'discard this text!'")
            } finally {
                ui.instrumentation.runOnMainSync {
                    previousClip?.let(clipboard::setPrimaryClip) ?: clipboard.clearPrimaryClip()
                }
            }
            ui.click("Ctrl")
            ui.instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_A)
            awaitReleased()
            ui.instrumentation.sendStringSync("window.p = 8")
            ui.click("/")
            ui.instrumentation.sendStringSync("2")
            ui.click("-")
            ui.instrumentation.sendStringSync("1")
            ui.instrumentation.waitForIdleSync()
            ReaderActivityFeedbackTestSupport.captureScreenshot(activity, frontend, "devtools-key-console.png")
            org.junit.Assert.assertEquals(
                "Console receives replacement; events=" + evaluate(frontend, "JSON.stringify(inputTrace)") +
                    "; focus=" + frontend.hasFocus() + "; target=" + evaluate(target, "JSON.stringify(receivedKeys)"),
                "window.p = 8/2-1",
                org.json.JSONTokener(
                    evaluate(frontend, "${ui.frontendElement(".cm-content")}.textContent"),
                ).nextValue(),
            )
            ui.instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
            ui.awaitJs(target, "window.p === 3")
            ui.tap(frontend, ui.frontendElement(".close-devtools"))
            ui.await("DevTools closes") { !frontend.isAttachedToWindow }
        } finally {
            ui.instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
