package tachiyomi.data.history

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Database
import tachiyomi.domain.history.model.ReadingSessionWithRelations
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

    override suspend fun getBetween(
        fromInclusive: Long,
        untilExclusive: Long,
    ): List<ReadingSessionWithRelations> {
        return database.reading_sessionsQueries.getBetweenWithRelations(
            fromInclusive = fromInclusive,
            untilExclusive = untilExclusive,
        ) {
                id,
                chapterId,
                mangaId,
                title,
                chapterName,
                startedAt,
                endedAt,
                readDuration,
            ->
            ReadingSessionWithRelations(
                id = id,
                chapterId = chapterId,
                mangaId = mangaId,
                mangaTitle = title,
                chapterName = chapterName,
                startedAt = startedAt,
                endedAt = endedAt,
                readDuration = readDuration,
            )
        }.awaitAsList()
    }

    override suspend fun getOldestStartedAt(): Long? {
        return database.reading_sessionsQueries.getOldestStartedAt().awaitAsOne().min
    }
}
