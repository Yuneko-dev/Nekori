package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.TtsTextUtils
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NovelWebViewTtsDomScriptsTest {

    @Test
    fun `highlight styles paint without changing paragraph geometry`() {
        val script = NovelWebViewTtsDomScripts.highlight(
            chapterId = 1L,
            paragraphIndex = 0,
            backgroundColor = "#808080",
            textColor = "#000000",
            style = "background",
            keepInView = true,
        )

        assertTrue(script.contains("background-color:var(--td-tts-highlight-bg)"))
        assertFalse(script.contains("padding:"))
        assertFalse(script.contains("contain:layout"))
    }

    @Test
    fun `normalization preserves prose and is idempotent`() {
        val cases = listOf(
            "Hello world" to "Hello world",
            "  “Hello   world”  " to "Hello world",
            "  \"\"\"\"\"  " to "",
            "[\"Status\"]" to "Status",
            "[?]" to "?",
            "*** • ━━━ ###" to "",
            "— — —" to "",
            "---" to "",
            "--" to "--",
            "— Wait! —" to "— Wait! —",
            "Wait.... what?" to "Wait… what?",
            "..." to "…",
            "3.14 at 12:30, 1,000.50" to "3.14 at 12:30, 1,000.50",
            "100% + $5 = €10 / 2 @home" to "100% + $5 = €10 / 2 @home",
            "「你好！」（世界）【注】" to "「你好！」（世界）【注】",
            "Tôi nói: ‘được’." to "Tôi nói: ‘được’.",
            "a*b_c" to "a b c",
            "A\u00a0\u202fB\uFEFF" to "A B",
            "a\u0000b" to "a b",
            "می\u200cروم" to "می\u200cروم",
            "क्\u200dष" to "क्\u200dष",
            "👨‍👩‍👧‍👦" to "",
            "❤️" to "",
            "\u200B" to "",
            "" to "",
        )
        cases.forEach { (input, expected) ->
            assertEquals(expected, TtsTextUtils.normalizeText(input), input)
            assertEquals(expected, TtsTextUtils.normalizeText(expected), "Normalization must be idempotent: $input")
        }
    }
}
