package tachiyomi.domain.history.interactor

import tachiyomi.domain.history.repository.ReadingSessionRepository

class GetReadingSessions(
    private val repository: ReadingSessionRepository,
) {
    suspend fun await(fromInclusive: Long, untilExclusive: Long) =
        repository.getBetween(fromInclusive, untilExclusive)

    suspend fun awaitOldestStartedAt() = repository.getOldestStartedAt()
}
