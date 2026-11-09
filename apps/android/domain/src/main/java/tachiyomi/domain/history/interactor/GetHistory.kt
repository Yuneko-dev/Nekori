package tachiyomi.domain.history.interactor

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.history.model.History
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.history.repository.HistoryRepository

class GetHistory(
    private val repository: HistoryRepository,
) {

    suspend fun await(mangaId: Long): List<History> {
        return repository.getHistoryByMangaId(mangaId)
    }

    fun subscribe(query: String, limit: Long = Long.MAX_VALUE): Flow<List<HistoryWithRelations>> {
        return repository.getHistory(query, limit)
    }

    fun subscribeGrouped(query: String, limit: Long = Long.MAX_VALUE): Flow<List<HistoryWithRelations>> {
        return repository.getHistoryGrouped(query, limit)
    }
}
