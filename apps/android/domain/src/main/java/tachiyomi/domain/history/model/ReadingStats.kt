package tachiyomi.domain.history.model

import tachiyomi.domain.manga.model.MangaCover

data class ReadingSessionWithRelations(
    val id: Long,
    val chapterId: Long,
    val mangaId: Long,
    val mangaTitle: String,
    val coverData: MangaCover,
    val chapterName: String,
    val startedAt: Long,
    val endedAt: Long,
    val readDuration: Long,
)

data class MangaReadStats(
    val mangaId: Long,
    val title: String,
    val coverData: MangaCover,
    val readDuration: Long,
    val chapterCount: Long,
    val lastRead: Long,
    val readChapterCount: Long,
    val totalChapterCount: Long,
    val sessionCount: Long,
    val sessionDuration: Long,
)
