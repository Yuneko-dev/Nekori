package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

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
}
