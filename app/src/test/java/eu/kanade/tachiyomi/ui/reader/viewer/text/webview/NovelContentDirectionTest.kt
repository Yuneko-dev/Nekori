package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NovelContentDirectionTest {

    @Test
    fun `rendered Arabic wins over English source language`() {
        assertEquals(
            NovelContentDirection.RTL,
            detectNovelContentDirection("<p>مرحبا بالعالم</p>", "en"),
        )
    }

    @Test
    fun `rendered English wins over Arabic source language`() {
        assertEquals(
            NovelContentDirection.LTR,
            detectNovelContentDirection("<p>Hello world</p>", "ar"),
        )
    }

    @Test
    fun `language decides when content has no strong character`() {
        assertEquals(NovelContentDirection.RTL, detectNovelContentDirection("<p>123…</p>", "ar"))
        assertEquals(NovelContentDirection.LTR, detectNovelContentDirection("<p>123…</p>", "auto"))
    }
}
