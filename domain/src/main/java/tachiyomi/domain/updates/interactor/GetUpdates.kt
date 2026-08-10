package tachiyomi.domain.updates.interactor

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.updates.model.UpdatesWithRelations
import tachiyomi.domain.updates.repository.UpdatesRepository
import kotlin.time.Instant

class GetUpdates(
    private val repository: UpdatesRepository,
) {

    suspend fun await(read: Boolean, after: Long, limit: Long = 500, offset: Long = 0): List<UpdatesWithRelations> {
        return repository.awaitWithRead(read, after, limit = limit, offset = offset)
    }

    fun subscribe(
        instant: Instant,
        limit: Long = PAGE_SIZE,
        unread: Boolean?,
        started: Boolean?,
        bookmarked: Boolean?,
        hideExcludedScanlators: Boolean,
        includedCategories: List<Long>,
        excludedCategories: List<Long>,
    ): Flow<List<UpdatesWithRelations>> {
        return repository.subscribeAll(
            instant.toEpochMilliseconds(),
            limit = limit,
            unread = unread,
            started = started,
            bookmarked = bookmarked,
            hideExcludedScanlators = hideExcludedScanlators,
            includedCategories = includedCategories,
            excludedCategories = excludedCategories,
        )
    }

    fun subscribe(read: Boolean, after: Long, limit: Long = PAGE_SIZE): Flow<List<UpdatesWithRelations>> {
        return repository.subscribeWithRead(read, after, limit = limit)
    }

    companion object {
        const val PAGE_SIZE = 50L
    }
}
