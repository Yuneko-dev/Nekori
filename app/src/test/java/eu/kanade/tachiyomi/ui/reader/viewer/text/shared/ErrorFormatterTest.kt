package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

import eu.kanade.tachiyomi.jsruntime.JsRuntimeException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ErrorFormatterTest {

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
