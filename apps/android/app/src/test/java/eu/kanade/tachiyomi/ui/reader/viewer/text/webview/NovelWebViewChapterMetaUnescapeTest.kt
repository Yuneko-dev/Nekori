package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.unescapeJsResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tests for [NovelWebViewChapterMeta.unescapeJsResult], the JSON-string decoder applied to
 * `evaluateJavascript` results (e.g. TTS text extraction).
 */
class NovelWebViewChapterMetaUnescapeTest {

    @Test
    fun `decodes standard escapes`() {
        val input = "\"line one\\nline two\\ttabbed\\\"quoted\\\"\""
        val expected = "line one\nline two\ttabbed\"quoted\""
        assertEquals(expected, unescapeJsResult(input))
    }

    @Test
    fun `literal backslash followed by n is not turned into a newline`() {
        // JS string containing a literal backslash then the letter n (page obfuscation,
        // e.g. "light\novel") is JSON-encoded as \\n (backslash, backslash, n).
        val input = "\"light\\\\novel\""
        assertEquals("light\\novel", unescapeJsResult(input))
    }

    @Test
    fun `literal backslash followed by other letters stays intact`() {
        val input = "\"c\\\\o/m\\\\world\""
        assertEquals("c\\o/m\\world", unescapeJsResult(input))
    }

    @Test
    fun `mixed literal backslash and real newline decode independently`() {
        // paragraph separator (real \n) next to obfuscation backslash+n in the same string.
        val input = "\"para one\\nlight\\\\novel\""
        assertEquals("para one\nlight\\novel", unescapeJsResult(input))
    }

    @Test
    fun `non quoted result is returned unchanged`() {
        assertEquals("null", unescapeJsResult("null"))
    }

    @Test
    fun `unicode escape decodes to character`() {
        assertEquals("café", unescapeJsResult("\"caf\\u00e9\""))
    }
}
