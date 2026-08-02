package tachiyomi.data.chapter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChapterSanitizerTest {

    @Test
    fun `chapter name equal to Japanese novel title is preserved`() {
        val title = "ひとりぼっちの異世界攻略"

        assertEquals(title, with(ChapterSanitizer) { title.sanitize(title) })
    }
}
