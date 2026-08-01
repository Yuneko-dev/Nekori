package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LnReaderCompatConfigTest {

    @Test
    fun `inline JSON cannot close its script element`() {
        val config = LnReaderCompatConfig(
            novel = LnReaderCompatConfig.Novel(1, "</script><script>alert(1)</script>", "/novel"),
            chapter = LnReaderCompatConfig.Chapter(2, "Chapter", "/chapter", 0),
            nextChapter = null,
        )

        val encoded = config.encode()

        assertFalse(encoded.contains("</script>", ignoreCase = true))
        assertTrue(encoded.contains("<\\/script>"))
    }
}
