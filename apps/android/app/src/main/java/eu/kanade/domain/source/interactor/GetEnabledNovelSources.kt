package eu.kanade.domain.source.interactor

import eu.kanade.domain.source.service.SourcePreferences
import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.source.model.Source
import tachiyomi.domain.source.repository.SourceRepository
import tachiyomi.domain.source.service.SourceManager

class GetEnabledNovelSources(
    private val repository: SourceRepository,
    private val preferences: SourcePreferences,
    private val sourceManager: SourceManager,
) {
    private val delegate = GetEnabledSources(repository, preferences, sourceManager)

    fun subscribe(): Flow<List<Source>> = delegate.subscribe(novelOnly = true)
}
