package eu.kanade.presentation.more.stats

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.history.model.ReadingSessionWithRelations
import tachiyomi.domain.manga.model.MangaCover
import java.time.LocalDate

class ReadingHeatmapScaleTest {

    @Test
    fun `only active visible in-year days are eligible`() {
        val days = listOf(
            heatDay(10),
            heatDay(0),
            heatDay(20, future = true),
            heatDay(30, inYear = false),
        )

        assertEquals(listOf(10L), eligibleHeatDurations(days))
    }

    @Test
    fun `empty scale and zero duration use level zero`() {
        val empty = readingHeatmapScale(emptyList())

        assertEquals(0, heatLevel(0, empty))
        assertEquals(0, heatLevel(10, empty))
    }

    @Test
    fun `maximum single and all-equal activity use level four`() {
        assertEquals(4, heatLevel(40, readingHeatmapScale(listOf(10L, 20L, 30L, 40L))))
        assertEquals(4, heatLevel(10, readingHeatmapScale(listOf(10L))))
        assertEquals(4, heatLevel(10, readingHeatmapScale(listOf(10L, 10L, 10L))))
    }

    @Test
    fun `two distinct values use endpoint levels`() {
        val scale = readingHeatmapScale(listOf(10L, 40L))

        assertEquals(1, heatLevel(10, scale))
        assertEquals(4, heatLevel(40, scale))
    }

    @Test
    fun `quartile boundaries are interpolated and equal values stay together`() {
        val scale = readingHeatmapScale(listOf(50L, 20L, 40L, 10L, 30L, 20L))

        assertEquals(listOf(20.0, 25.0, 37.5), scale.quartiles)
        assertEquals(listOf(1, 1), listOf(20L, 20L).map { heatLevel(it, scale) })
        assertEquals(2, heatLevel(25, scale))
        assertEquals(3, heatLevel(30, scale))
        assertEquals(4, heatLevel(40, scale))
    }

    @Test
    fun `active intensities are ordered from point three to one`() {
        val intensities = (1..4).map(::heatIntensity)

        assertEquals(0.3f, intensities.first())
        assertEquals(1f, intensities.last())
        assertTrue(intensities.zipWithNext().all { (left, right) -> left < right })
    }

    private fun heatDay(duration: Long, inYear: Boolean = true, future: Boolean = false) = HeatDay(
        date = LocalDate.of(2026, 1, 1),
        inYear = inYear,
        future = future,
        sessions = if (duration == 0L) emptyList() else listOf(readingSession(duration)),
    )

    private fun readingSession(duration: Long) = ReadingSessionWithRelations(
        id = 1,
        chapterId = 1,
        mangaId = 1,
        mangaTitle = "Novel",
        coverData = MangaCover(1, 1, true, null, 0),
        chapterName = "Chapter",
        startedAt = 0,
        endedAt = duration,
        readDuration = duration,
    )
}
