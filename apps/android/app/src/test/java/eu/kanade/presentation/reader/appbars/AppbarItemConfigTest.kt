package eu.kanade.presentation.reader.appbars

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AppbarItemConfigTest {

    @Test
    fun `saved layout from an older version gains the chapter list enabled`() {
        val items = """
            [{"id":"prev_chapter","enabled":true}]
        """.trimIndent().deserializeBottomBarItems()

        items.first { it.item == BottomBarItem.CHAPTER_LIST }.enabled shouldBe true
    }

    @Test
    fun `WebView and share arrive switched off for a layout saved before they existed`() {
        val items = """
            [{"id":"prev_chapter","enabled":true},{"id":"next_chapter","enabled":true}]
        """.trimIndent().deserializeBottomBarItems()

        items.first { it.item == BottomBarItem.WEBVIEW }.enabled shouldBe false
        items.first { it.item == BottomBarItem.SHARE }.enabled shouldBe false
        // Appended, so a saved order survives.
        items.take(2).map { it.item } shouldBe listOf(BottomBarItem.PREV_CHAPTER, BottomBarItem.NEXT_CHAPTER)
    }

    @Test
    fun `a user who switched them on keeps them on`() {
        val items = """
            [{"id":"webview","enabled":true},{"id":"share","enabled":true}]
        """.trimIndent().deserializeBottomBarItems()

        items.first { it.item == BottomBarItem.WEBVIEW }.enabled shouldBe true
        items.first { it.item == BottomBarItem.SHARE }.enabled shouldBe true
    }

    @Test
    fun `TTS item is unavailable only while master TTS is disabled`() {
        BottomBarItem.TTS.isAvailable(ttsEnabled = false) shouldBe false
        BottomBarItem.TTS.isAvailable(ttsEnabled = true) shouldBe true
        BottomBarItem.CHAPTER_LIST.isAvailable(ttsEnabled = false) shouldBe true
    }
}
