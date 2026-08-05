package eu.kanade.tachiyomi.ui.browse.source.globalsearch

import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.isNovelSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class GlobalSearchScreenModel(
    initialQuery: String = "",
    private val initialExtensionFilter: String? = null,
) : SearchScreenModel(State(searchQuery = initialQuery)) {

    private val extensionManager by lazy { Injekt.get<ExtensionManager>() }

    init {
        if (initialQuery.isNotBlank() || !initialExtensionFilter.isNullOrBlank()) {
            if (initialExtensionFilter != null) {
                // we're going to use custom extension filter instead
                setSourceFilter(SourceFilter.All)
            }
            search()
        }
    }

    override fun getEnabledSources(): List<Source> {
        val filter = state.value.sourceFilter
        return super.getEnabledSources()
            .filterNot { it.isNovelSource() } // Exclude novel sources from manga global search
            .filter {
                when (filter) {
                    SourceFilter.All -> true
                    SourceFilter.PinnedOnly -> "${it.id}" in pinnedSources
                    is SourceFilter.Group -> "${it.id}" in sourceGroups.getOrElse(filter.name) { emptySet() }
                }
            }
    }

    override fun getSelectedSources(): List<Source> {
        val enabledSources = getEnabledSources()
        val filter = initialExtensionFilter ?: return enabledSources
        return extensionManager.installedExtensionsFlow.value
            .filter { it.pkgName == filter }
            .flatMap { it.sources }
            .filter { it in enabledSources }
    }
}
