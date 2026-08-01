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
    fun `TTS item is unavailable only while master TTS is disabled`() {
        BottomBarItem.TTS.isAvailable(ttsEnabled = false) shouldBe false
        BottomBarItem.TTS.isAvailable(ttsEnabled = true) shouldBe true
        BottomBarItem.CHAPTER_LIST.isAvailable(ttsEnabled = false) shouldBe true
    }
}
