package eu.kanade.presentation.more.stats

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.domain.history.model.MangaReadStats
import tachiyomi.domain.manga.model.MangaCover

class MostReadRowsTest {

    @Test
    fun `removed novels are combined and sorted with library novels`() {
        val rows = buildMostReadRows(
            listOf(
                stats(1, 40, inLibrary = true),
                stats(2, 30, inLibrary = false),
                stats(3, 20, inLibrary = false),
            ),
        )

        assertEquals(listOf(50L, 40L), rows.map { it.readDuration })
        assertNull(rows.first().manga)
        assertEquals(1L, rows.last().manga?.mangaId)
    }

    private fun stats(id: Long, duration: Long, inLibrary: Boolean) = MangaReadStats(
        mangaId = id,
        title = "Novel $id",
        coverData = MangaCover(id, 1, inLibrary, null, 0),
        readDuration = duration,
        chapterCount = 1,
        lastRead = 0,
        readChapterCount = 0,
        totalChapterCount = 1,
        sessionCount = 0,
        sessionDuration = 0,
    )
}
