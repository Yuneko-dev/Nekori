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
    ) {
        val isEmpty = items.isEmpty()
    }

    companion object {
        const val PINNED_KEY = "pinned"
        const val LAST_USED_KEY = "last_used"
    }
}
