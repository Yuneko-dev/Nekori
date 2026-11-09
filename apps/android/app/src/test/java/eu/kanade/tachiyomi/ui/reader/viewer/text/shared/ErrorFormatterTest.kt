package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

import eu.kanade.tachiyomi.jsruntime.JsRuntimeException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeoutException

class ErrorFormatterTest {

    @Test
    fun chapterLoadTimeoutHasNetworkCategoryAndKeepsItsCause() {
        val error = TimeoutException("Timed out loading next chapter").apply {
            initCause(IllegalStateException("Page never became ready"))
        }

        val formatted = ErrorFormatter.format(error)

        assertEquals(ErrorFormatter.Category.NetworkTimeout, formatted.category)
        assertEquals("Timed out loading next chapter", formatted.summary)
        assertTrue(formatted.stackTrace.contains("Page never became ready"))
    }

    @Test
    fun javaScriptStackIsKeptOutOfTheSummaryAndIncludedInDiagnostics() {
        val error = JsRuntimeException(
            message = "Network request failed",
            jsStack = "TypeError: Network request failed\n    at plugin.parseChapter (plugin.js:42:10)",
        )

        val formatted = ErrorFormatter.format(error)

        assertEquals("Network request failed", formatted.summary)
        assertTrue(formatted.stackTrace.contains("JavaScript stack:"))
        assertTrue(formatted.stackTrace.contains("plugin.parseChapter (plugin.js:42:10)"))
    }
}
