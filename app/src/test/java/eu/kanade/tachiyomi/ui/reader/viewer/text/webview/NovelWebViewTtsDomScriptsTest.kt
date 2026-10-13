package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.TtsTextUtils
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NovelWebViewTtsDomScriptsTest {

    @Test
    fun ttsCommandsUseStringChapterIdsAndStructuredPayloads() {
        val highlight = NovelWebViewTtsDomScripts.highlight(
            chapterId = 42L,
            paragraphIndex = 3,
            backgroundColor = "#808080",
            textColor = "#000000",
            style = "background",
            keepInView = true,
        )

        assertEquals("tts.highlight", highlight.method)
        assertEquals(JsonPrimitive("42"), highlight.payload["chapterId"])
        assertEquals(JsonPrimitive(3), highlight.payload["paragraphIndex"])
        assertEquals(JsonPrimitive(true), highlight.payload["keepInView"])
        assertEquals("tts.extractText", NovelWebViewTtsDomScripts.extractText(null).method)
        assertEquals(JsonNull, NovelWebViewTtsDomScripts.extractText(null).payload["chapterId"])
        assertEquals("tts.clearHighlight", NovelWebViewTtsDomScripts.CLEAR_HIGHLIGHT.method)
    }

    @Test
    fun chapterAppendCarriesPlainTextAsData() {
        val command = NovelWebViewChapterDomScripts.append(
            NovelWebViewChapterDomScripts.ScriptChapter(
                id = 8,
                name = "Chapter",
                number = 2f,
                path = "opaque//path",
                absoluteUrl = "https://source/chapter",
                direction = NovelContentDirection.LTR,
                language = "en",
            ),
            eu.kanade.tachiyomi.ui.reader.viewer.text.shared.ProcessedContent(
                text = "first <raw>\n\nsecond",
                isPlainText = true,
                chapterUrl = "opaque//path",
            ),
        )

        assertEquals("chapter.append", command.method)
        assertEquals(
            JsonPrimitive("8"),
            command.payload["chapter"]?.let {
                (it as kotlinx.serialization.json.JsonObject)["id"]
            },
        )
        val content = command.payload["content"] as kotlinx.serialization.json.JsonObject
        assertEquals(JsonPrimitive("plainText"), content["kind"])
        assertEquals(JsonPrimitive("first <raw>\n\nsecond"), content["text"])
        assertTrue(command.payload.toString().contains("opaque//path"))
    }

    @Test
    fun normalizationPreservesProseAndIsIdempotent() {
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
