package eu.kanade.tachiyomi.ui.browse.source

import androidx.compose.runtime.Immutable
import eu.kanade.presentation.browse.SourceUiModel
import tachiyomi.domain.source.model.Source

class SourcesViewModel private constructor() {

    sealed interface Dialog {
        data class SourceOptions(val source: Source) : Dialog
        data class PinGroups(val source: Source) : Dialog
    }

    @Immutable
    data class State(
        val dialog: Dialog? = null,
        val isLoading: Boolean = true,
        val items: List<SourceUiModel> = listOf(),
        val searchQuery: String? = null,
    ) {
        val filteredItems = filterSourceItems(items, searchQuery)
        val isEmpty = filteredItems.isEmpty()
    }

    companion object {
        const val PINNED_KEY = "pinned"
        const val LAST_USED_KEY = "last_used"
    }
}

internal fun filterSourceItems(items: List<SourceUiModel>, query: String?): List<SourceUiModel> {
    val search = query?.trim().orEmpty()
    if (search.isEmpty()) return items

    return buildList {
        var header: SourceUiModel.Header? = null
        items.forEach { model ->
            when (model) {
                is SourceUiModel.Header -> header = model
                is SourceUiModel.Item -> {
                    val source = model.source
                    if (
                        source.name.contains(search, ignoreCase = true) ||
                        source.lang.contains(search, ignoreCase = true)
                    ) {
                        header?.let(::add)
                        header = null
                        add(model)
                    }
                }
            }
        }
    }
}
