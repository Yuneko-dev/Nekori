package eu.kanade.tachiyomi.ui.browse.source.globalsearch

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.isNovelSource

class NovelGlobalSearchScreenModel(
    initialQuery: String = "",
) : SearchScreenModel(State(searchQuery = initialQuery)) {

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
