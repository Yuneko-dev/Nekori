package tachiyomi.domain.history.repository

interface ReadingSessionRepository {

    suspend fun insert(
        chapterId: Long,
        startedAt: Long,
        endedAt: Long,
        readDuration: Long,
    )
}
