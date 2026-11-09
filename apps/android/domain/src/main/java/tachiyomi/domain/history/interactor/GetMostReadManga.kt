package tachiyomi.domain.history.interactor

import tachiyomi.domain.history.repository.HistoryRepository

class GetMostReadManga(
    private val repository: HistoryRepository,
) {
    suspend fun await(limit: Long = Long.MAX_VALUE) = repository.getMostReadManga(limit)
}
