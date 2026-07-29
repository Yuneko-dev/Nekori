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
        return super.getEnabledSources()
            .filterIsInstance<CatalogueSource>()
            .filter { it.isNovelSource() }
            .filter { state.value.sourceFilter != SourceFilter.PinnedOnly || "${it.id}" in pinnedSources }
    }
}
