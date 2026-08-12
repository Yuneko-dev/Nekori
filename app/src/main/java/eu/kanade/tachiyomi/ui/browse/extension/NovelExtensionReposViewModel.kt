package eu.kanade.tachiyomi.ui.browse.extension

import androidx.compose.runtime.Immutable
import androidx.lifecycle.viewModelScope
import eu.kanade.tachiyomi.jsplugin.JsPluginManager
import eu.kanade.tachiyomi.jsplugin.model.JsPluginRepository
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import mihon.core.viewmodel.StateViewModel
import tachiyomi.core.common.util.lang.launchIO
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class NovelExtensionReposViewModel(
    private val jsPluginManager: JsPluginManager = Injekt.get(),
) : StateViewModel<NovelRepoScreenState>(NovelRepoScreenState.Loading) {

    init {
        viewModelScope.launchIO {
            jsPluginManager.repositories.collectLatest { repositories ->
                mutableState.update {
                    NovelRepoScreenState.Success(
                        repositories = repositories,
                        dialog = (it as? NovelRepoScreenState.Success)?.dialog,
                    )
                }
            }
        }
    }

    fun createRepo(url: String) {
        jsPluginManager.addRepository(url)
        dismissDialog()
    }

    fun deleteRepo(url: String) {
        jsPluginManager.removeRepository(url)
        dismissDialog()
    }

    fun setRepoEnabled(url: String, enabled: Boolean) {
        jsPluginManager.setRepositoryEnabled(url, enabled)
        viewModelScope.launchIO { jsPluginManager.refreshAvailablePlugins() }
    }

    fun refreshRepos() {
        viewModelScope.launchIO {
            jsPluginManager.refreshAvailablePlugins(forceRefresh = true)
        }
    }

    fun showDialog(dialog: NovelRepoDialog) {
        mutableState.update {
            when (it) {
                NovelRepoScreenState.Loading -> it
                is NovelRepoScreenState.Success -> it.copy(dialog = dialog)
            }
        }
    }

    fun dismissDialog() {
        mutableState.update {
            when (it) {
                NovelRepoScreenState.Loading -> it
                is NovelRepoScreenState.Success -> it.copy(dialog = null)
            }
        }
    }
}

sealed class NovelRepoDialog {
    data object Create : NovelRepoDialog()
    data class Delete(val repo: JsPluginRepository) : NovelRepoDialog()
}

sealed class NovelRepoScreenState {
    @Immutable
    data object Loading : NovelRepoScreenState()

    @Immutable
    data class Success(
        val repositories: List<JsPluginRepository> = emptyList(),
        val dialog: NovelRepoDialog? = null,
    ) : NovelRepoScreenState()
}
