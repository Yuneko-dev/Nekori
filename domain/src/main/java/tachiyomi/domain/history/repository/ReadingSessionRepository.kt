package tachiyomi.domain.history.repository

import tachiyomi.domain.history.model.ReadingSessionWithRelations

interface ReadingSessionRepository {

    suspend fun insert(
        chapterId: Long,
        startedAt: Long,
        endedAt: Long,
        readDuration: Long,
    )

    suspend fun getBetween(fromInclusive: Long, untilExclusive: Long): List<ReadingSessionWithRelations>

    suspend fun getOldestStartedAt(): Long?
}
