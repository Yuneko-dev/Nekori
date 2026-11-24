package eu.kanade.tachiyomi.jsruntime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class JsRuntimeExceptionTest {

    @Test
    fun `parses structured plugin error metadata and strips header from stack`() {
        val meta = """{"code":"Too many requests (429). Please try again later.",""" +
            """"status":429,"url":"https://example.com/ch/1?a=\"b\""}"""
        val rawStack = "[NekoriPluginError:$meta]\n" +
            "PluginError: Too many requests\n" +
            "    at parseChapter (lnreader-plugin://demo.js:12:5)"

        val error = JsRuntimeException.fromRejection("Slow down", rawStack)

        assertEquals("Slow down", error.message)
        assertEquals("Too many requests (429). Please try again later.", error.pluginErrorCode)
        assertEquals(429, error.httpStatus)
        assertEquals("""https://example.com/ch/1?a="b"""", error.errorUrl)
        assertEquals(
            "PluginError: Too many requests\n    at parseChapter (lnreader-plugin://demo.js:12:5)",
            error.jsStack,
        )
    }

    @Test
    fun `leaves plain JavaScript rejections untouched`() {
        val rawStack = "Error: boom\n    at parseNovel (lnreader-plugin://demo.js:4:1)"

        val error = JsRuntimeException.fromRejection("boom", rawStack)

        assertEquals("boom", error.message)
        assertEquals(rawStack, error.jsStack)
        assertNull(error.pluginErrorCode)
        assertNull(error.httpStatus)
        assertNull(error.errorUrl)
    }
}
