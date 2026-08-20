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

    private var pendingDeeplinkUrl: String? = null

    init {
        viewModelScope.launchIO {
            jsPluginManager.repositories.collectLatest { repositories ->
                val deeplink = pendingDeeplinkUrl
                pendingDeeplinkUrl = null
                mutableState.update {
                    NovelRepoScreenState.Success(
                        repositories = repositories,
                        dialog = deeplink?.let { url ->
                            NovelRepoDialog.Confirm(
                                url = url,
                                alreadyExists = repositories.any {
                                    JsPluginManager.normalizeRepositoryUrl(it.url) == url
                                },
                            )
                        } ?: (it as? NovelRepoScreenState.Success)?.dialog,
                    )
                }
            }
        }
    }

    fun createRepo(url: String) {
        addRepository(url)
    }

    fun addFromDeeplink(url: String) {
        val normalizedUrl = JsPluginManager.normalizeRepositoryUrl(url)
        pendingDeeplinkUrl = normalizedUrl
        val repositories = (state.value as? NovelRepoScreenState.Success)?.repositories.orEmpty()
        if (state.value is NovelRepoScreenState.Loading) return
        pendingDeeplinkUrl = null
        showDialog(
            NovelRepoDialog.Confirm(
                url = normalizedUrl,
                alreadyExists = repositories.any {
                    JsPluginManager.normalizeRepositoryUrl(it.url) == normalizedUrl
                },
            ),
        )
    }

    fun confirmDeeplink(url: String) {
        addRepository(url)
    }

    private fun addRepository(url: String) {
        viewModelScope.launchIO {
            updateDialog { dialog ->
                when (dialog) {
                    is NovelRepoDialog.Create -> dialog.copy(processing = true, errorMessage = null)
                    is NovelRepoDialog.Confirm -> dialog.copy(processing = true, errorMessage = null)
                    else -> dialog
                }
            }
            jsPluginManager.addRepository(url)
                .onSuccess { dismissDialog() }
                .onFailure { error ->
                    updateDialog { dialog ->
                        when (dialog) {
                            is NovelRepoDialog.Create -> dialog.copy(
                                processing = false,
                                errorMessage = error.message ?: "Failed to add repository",
                            )
                            is NovelRepoDialog.Confirm -> dialog.copy(
                                processing = false,
                                errorMessage = error.message ?: "Failed to add repository",
                            )
                            else -> dialog
                        }
                    }
                }
        }
    }

    fun deleteRepo(url: String) {
        jsPluginManager.removeRepository(url)
        dismissDialog()
    }

    fun setRepoEnabled(url: String, enabled: Boolean) {
        jsPluginManager.setRepositoryEnabled(url, enabled)
        // Force it: the plugin list is only empty on a cold start, and a cached non-empty list makes
        // an unforced refresh return immediately, which is exactly the toggle that has to invalidate it.
        viewModelScope.launchIO { jsPluginManager.refreshAvailablePlugins(forceRefresh = true) }
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

    private inline fun updateDialog(
        transform: (NovelRepoDialog) -> NovelRepoDialog,
    ) {
        mutableState.update { state ->
            when (state) {
                NovelRepoScreenState.Loading -> state
                is NovelRepoScreenState.Success -> state.copy(dialog = state.dialog?.let(transform))
            }
        }
    }
}

sealed class NovelRepoDialog {
    data class Create(
        val processing: Boolean = false,
        val errorMessage: String? = null,
    ) : NovelRepoDialog()
    data class Confirm(
        val url: String,
        val alreadyExists: Boolean = false,
        val processing: Boolean = false,
        val errorMessage: String? = null,
    ) : NovelRepoDialog()
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
