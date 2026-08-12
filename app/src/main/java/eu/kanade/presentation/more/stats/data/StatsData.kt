package eu.kanade.presentation.more.stats.data

import tachiyomi.domain.history.model.MangaReadStats
import tachiyomi.domain.history.model.ReadingSessionWithRelations

sealed interface StatsData {

    data class Overview(
        val libraryMangaCount: Int,
        val completedMangaCount: Int,
        val totalReadDuration: Long,
    ) : StatsData

    data class Titles(
        val globalUpdateItemCount: Int,
        val startedMangaCount: Int,
        val localMangaCount: Int,
    ) : StatsData

    data class Chapters(
        val totalChapterCount: Int,
        val readChapterCount: Int,
        val downloadCount: Int,
    ) : StatsData

    data class Trackers(
        val trackedTitleCount: Int,
        val meanScore: Double,
        val trackerCount: Int,
    ) : StatsData

    data class Advanced(
        val selectedYear: Int,
        val availableYears: List<Int>,
        val recentSessions: List<ReadingSessionWithRelations>,
        val yearSessions: List<ReadingSessionWithRelations>,
        val mostReadManga: List<MangaReadStats>,
    ) : StatsData
}
