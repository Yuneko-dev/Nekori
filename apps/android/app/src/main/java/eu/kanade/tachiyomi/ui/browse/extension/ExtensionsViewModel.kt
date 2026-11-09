package eu.kanade.tachiyomi.ui.browse.extension

import androidx.compose.runtime.Immutable
import dev.icerock.moko.resources.StringResource
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep

typealias ItemGroups = Map<ExtensionUiModel.Header, List<ExtensionUiModel.Item>>

@Immutable
data class ExtensionScreenState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val items: ItemGroups = mutableMapOf(),
    val updates: Int = 0,
    val installer: BasePreferences.ExtensionInstaller? = null,
    val searchQuery: String? = null,
) {
    val isEmpty = items.isEmpty()
}

object ExtensionUiModel {
    sealed interface Header {
        data class Resource(val textRes: StringResource) : Header
        data class Text(val text: String) : Header
    }

    data class Item(
        val extension: Extension,
        val installStep: InstallStep,
    )
}
