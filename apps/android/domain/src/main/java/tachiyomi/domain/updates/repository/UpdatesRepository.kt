package tachiyomi.domain.updates.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.updates.model.UpdatesWithRelations

interface UpdatesRepository {

    suspend fun awaitWithRead(read: Boolean, after: Long, limit: Long, offset: Long = 0): List<UpdatesWithRelations>

    fun subscribeAll(
        after: Long,
        limit: Long,
        offset: Long = 0,
        unread: Boolean?,
        started: Boolean?,
        bookmarked: Boolean?,
        hideExcludedScanlators: Boolean,
        includedCategories: List<Long>,
        excludedCategories: List<Long>,
    ): Flow<List<UpdatesWithRelations>>

    fun subscribeWithRead(read: Boolean, after: Long, limit: Long, offset: Long = 0): Flow<List<UpdatesWithRelations>>
}
