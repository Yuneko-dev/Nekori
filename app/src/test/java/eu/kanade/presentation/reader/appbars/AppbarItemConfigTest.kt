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
}
