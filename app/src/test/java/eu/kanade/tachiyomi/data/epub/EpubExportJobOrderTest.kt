package eu.kanade.tachiyomi.data.epub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class EpubExportJobOrderTest {

    @Test
    fun `exports descending source order with id tie break`() {
        val chapters = listOf(
            chapter(id = 9, sourceOrder = 10),
            chapter(id = 4, sourceOrder = 20),
            chapter(id = 3, sourceOrder = 20),
            chapter(id = 1, sourceOrder = 5),
        )

        assertEquals(
            listOf(3L, 4L, 9L, 1L),
            EpubExportJob.sortChaptersForEpubExport(chapters).map(Chapter::id),
        )
    }

    private fun chapter(id: Long, sourceOrder: Long): Chapter = Chapter.create().copy(
        id = id,
        sourceOrder = sourceOrder,
    )
}
