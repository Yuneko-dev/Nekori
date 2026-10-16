package eu.kanade.presentation.reader

import android.view.KeyEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.KeyboardTab
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val keys = listOf(
    listOf(
        "ESC" to KeyEvent.KEYCODE_ESCAPE,
        "/" to KeyEvent.KEYCODE_SLASH,
        "-" to KeyEvent.KEYCODE_MINUS,
        "Home" to KeyEvent.KEYCODE_MOVE_HOME,
        "ArrowUp" to KeyEvent.KEYCODE_DPAD_UP,
        "End" to KeyEvent.KEYCODE_MOVE_END,
        "PgUp" to KeyEvent.KEYCODE_PAGE_UP,
    ),
    listOf(
        "Tab" to KeyEvent.KEYCODE_TAB,
        "Ctrl" to KeyEvent.KEYCODE_CTRL_LEFT,
        "Alt" to KeyEvent.KEYCODE_ALT_LEFT,
        "ArrowLeft" to KeyEvent.KEYCODE_DPAD_LEFT,
        "ArrowDown" to KeyEvent.KEYCODE_DPAD_DOWN,
        "ArrowRight" to KeyEvent.KEYCODE_DPAD_RIGHT,
        "PgDn" to KeyEvent.KEYCODE_PAGE_DOWN,
    ),
)

@Composable
internal fun DevToolsKeyPanel(modifiers: Int, onModifier: (Int) -> Unit, onKey: (Int) -> Unit) {
    Column {
        keys.forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { (label, code) ->
                    val mask = when (code) {
                        KeyEvent.KEYCODE_CTRL_LEFT -> KeyEvent.META_CTRL_ON
                        KeyEvent.KEYCODE_ALT_LEFT -> KeyEvent.META_ALT_ON
                        else -> 0
                    }
                    val held = mask != 0 && modifiers and mask != 0
                    TextButton(
                        onClick = { if (mask == 0) onKey(code) else onModifier(mask) },
                        modifier = Modifier.weight(1f).height(36.dp).focusProperties { canFocus = false }.semantics {
                            selected = held
                            contentDescription = label
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                        colors = ButtonDefaults.textButtonColors(
                            containerColor = if (held) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                Color.Transparent
                            },
                        ),
                    ) {
                        val icon = when (code) {
                            KeyEvent.KEYCODE_TAB -> Icons.Default.KeyboardTab
                            KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_SLASH -> Icons.Default.Remove
                            KeyEvent.KEYCODE_DPAD_UP -> Icons.Default.ArrowUpward
                            KeyEvent.KEYCODE_DPAD_DOWN -> Icons.Default.ArrowDownward
                            KeyEvent.KEYCODE_DPAD_LEFT -> Icons.Default.ArrowBack
                            KeyEvent.KEYCODE_DPAD_RIGHT -> Icons.Default.ArrowForward
                            else -> null
                        }
                        if (icon != null) {
                            val rotation = if (code == KeyEvent.KEYCODE_SLASH) -60f else 0f
                            Icon(
                                icon,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp).rotate(rotation),
                            )
                        } else {
                            Text(label, fontSize = 12.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
