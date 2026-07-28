package tachiyomi.data.history

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Database
import tachiyomi.domain.history.repository.ReadingSessionRepository

class ReadingSessionRepositoryImpl(
    private val database: Database,
) : ReadingSessionRepository {

    override suspend fun insert(
        chapterId: Long,
        startedAt: Long,
        endedAt: Long,
        readDuration: Long,
    ) {
        try {
            database.reading_sessionsQueries.insert(
                chapterId = chapterId,
                startedAt = startedAt,
                endedAt = endedAt,
                readDuration = readDuration,
            )
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
        }
    }
}
