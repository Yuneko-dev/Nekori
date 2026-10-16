package eu.kanade.tachiyomi.ui.webview

import android.content.Context
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.TextAttribute
import android.webkit.WebView
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/** Extra keys belong to the frontend, never to the Activity or inspected WebView. */
internal class DevToolsKeyboardWebView(context: Context) : WebView(context) {
    private val pendingModifiers = MutableStateFlow(0)
    val modifierState = pendingModifiers.asStateFlow()
    private val pressedModifiers = mutableMapOf<Pair<Int, Int>, Int>()

    var modifiers: Int
        get() = pendingModifiers.value
        set(value) {
            if (modifiers == value) return
            pendingModifiers.value = value
            if (value != 0 && !hasFocus()) requestFocus()
            context.getSystemService(InputMethodManager::class.java).restartInput(this)
        }

    fun pressKey(code: Int) {
        if (!hasFocus()) requestFocus()
        val now = SystemClock.uptimeMillis()
        dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = dispatchWithModifiers(event, modifiersFor(event))

    private fun modifiersFor(event: KeyEvent): Int = synchronized(pressedModifiers) {
        if (KeyEvent.isModifierKey(event.keyCode)) return@synchronized 0
        val key = event.deviceId to event.keyCode
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount > 0) {
                pressedModifiers[key] ?: 0
            } else {
                pendingModifiers.getAndUpdate { 0 }.also { held ->
                    if (held == 0) pressedModifiers.remove(key) else pressedModifiers[key] = held
                }
            }
            KeyEvent.ACTION_UP -> pressedModifiers.remove(key) ?: 0
            else -> 0
        }
    }

    private fun dispatchWithModifiers(event: KeyEvent, held: Int): Boolean {
        if (held == 0) return super.dispatchKeyEvent(event)
        val modified = KeyEvent(
            event.downTime, event.eventTime, event.action, event.keyCode, event.repeatCount,
            KeyEvent.normalizeMetaState(event.metaState or held), event.deviceId, event.scanCode,
            event.flags, event.source,
        )
        return super.dispatchKeyEvent(modified)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val connection = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(connection, false) {
            private var composing = ""

            private fun send(events: Array<KeyEvent>) {
                val prepared = events.map { it to modifiersFor(it) }
                // Keep Chromium's IME handler; only key dispatch belongs on the UI thread.
                post {
                    if (isAttachedToWindow) prepared.forEach { (event, held) -> dispatchWithModifiers(event, held) }
                }
            }

            private fun shortcutKey(code: Int): Boolean {
                if (modifiers == 0) return false
                val now = SystemClock.uptimeMillis()
                send(
                    arrayOf(
                        KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0),
                        KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0),
                    ),
                )
                return true
            }

            override fun performEditorAction(actionCode: Int): Boolean =
                shortcutKey(KeyEvent.KEYCODE_ENTER) || super.performEditorAction(actionCode)

            private fun deleteKey(beforeLength: Int, afterLength: Int): Boolean = when {
                beforeLength == 1 && afterLength == 0 -> shortcutKey(KeyEvent.KEYCODE_DEL)
                beforeLength == 0 && afterLength == 1 -> shortcutKey(KeyEvent.KEYCODE_FORWARD_DEL)
                else -> false
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
                deleteKey(beforeLength, afterLength) || super.deleteSurroundingText(beforeLength, afterLength)

            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
                deleteKey(beforeLength, afterLength) ||
                    super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)

            private fun textInput(
                text: CharSequence?,
                composingText: Boolean,
                insert: (CharSequence) -> Boolean,
            ): Boolean {
                if (modifiers == 0 && composing.isEmpty()) return insert(text ?: "")
                val value = text?.toString().orEmpty()
                if (!value.startsWith(composing)) composing = ""
                // A consumed shortcut may be resent as composing/committed text by the IME.
                val added = if (value.startsWith(composing)) value.drop(composing.length) else value
                if (modifiers == 0) {
                    if (!composingText) composing = ""
                    return if (added.isEmpty() && value.isNotEmpty()) true else insert(added)
                }
                val events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(added.toCharArray())
                if (added.isEmpty()) return true
                if (events == null) {
                    pendingModifiers.value = 0
                    composing = ""
                    return insert(added)
                }
                composing = if (composingText) value else ""
                send(events)
                return true
            }

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean =
                textInput(text, false) { super.commitText(it, newCursorPosition) }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean =
                textInput(text, true) { super.setComposingText(it, newCursorPosition) }

            @RequiresApi(33)
            override fun commitText(
                text: CharSequence,
                newCursorPosition: Int,
                textAttribute: TextAttribute?,
            ): Boolean =
                textInput(text, false) { super.commitText(it, newCursorPosition, textAttribute) }

            @RequiresApi(33)
            override fun setComposingText(
                text: CharSequence,
                newCursorPosition: Int,
                textAttribute: TextAttribute?,
            ): Boolean = textInput(text, true) { super.setComposingText(it, newCursorPosition, textAttribute) }

            override fun finishComposingText(): Boolean {
                composing = ""
                return super.finishComposingText()
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                val held = modifiersFor(event)
                if (held == 0) return super.sendKeyEvent(event)
                post {
                    if (isAttachedToWindow) dispatchWithModifiers(event, held)
                }
                return true
            }
        }
    }
}
