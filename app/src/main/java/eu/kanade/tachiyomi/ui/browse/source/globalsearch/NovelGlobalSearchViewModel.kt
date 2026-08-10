package eu.kanade.tachiyomi.ui.browse.source.globalsearch

import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.isNovelSource

class NovelGlobalSearchViewModel(
    initialQuery: String,
    initialExtensionFilter: String?,
) : SearchViewModel(State(searchQuery = initialQuery)) {

    companion object {
        val INITIAL_QUERY_KEY = CreationExtras.Key<String>()
        val INITIAL_EXTENSION_FILTER_KEY = CreationExtras.Key<String?>()

        val Factory = viewModelFactory {
            initializer {
                NovelGlobalSearchViewModel(
                    initialQuery = get(INITIAL_QUERY_KEY)!!,
                    initialExtensionFilter = get(INITIAL_EXTENSION_FILTER_KEY),
                )
            }
        }
    }

    init {
        if (initialQuery.isNotBlank()) {
            search()
        }
    }

    override fun getEnabledSources(): List<CatalogueSource> {
        val filter = state.value.sourceFilter
        return super.getEnabledSources()
            .filterIsInstance<CatalogueSource>()
            .filter { it.isNovelSource() }
            .filter {
                when (filter) {
                    SourceFilter.All -> true
                    SourceFilter.PinnedOnly -> "${it.id}" in pinnedSources
                    is SourceFilter.Group -> "${it.id}" in sourceGroups.getOrElse(filter.name) { emptySet() }
                }
            }
    }
}
