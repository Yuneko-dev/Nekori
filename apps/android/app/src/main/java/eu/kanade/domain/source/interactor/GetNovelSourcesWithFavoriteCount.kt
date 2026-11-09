package eu.kanade.domain.source.interactor

import eu.kanade.domain.source.service.SourcePreferences
import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.source.model.Source
import tachiyomi.domain.source.repository.SourceRepository

class GetNovelSourcesWithFavoriteCount(
    private val repository: SourceRepository,
    private val preferences: SourcePreferences,
) {
    private val delegate = GetSourcesWithFavoriteCount(repository, preferences)

    fun subscribe(): Flow<List<Pair<Source, Long>>> = delegate.subscribe(novelOnly = true)
}
