package eu.kanade.tachiyomi.ui.browse.extension

import android.app.Application
import androidx.lifecycle.viewModelScope
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.components.SEARCH_DEBOUNCE_MILLIS
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.jsplugin.JsPluginManager
import eu.kanade.tachiyomi.jsplugin.model.InstalledJsPlugin
import eu.kanade.tachiyomi.jsplugin.model.JsPlugin
import eu.kanade.tachiyomi.jsplugin.source.JsSource
import eu.kanade.tachiyomi.util.system.LocaleHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import logcat.LogPriority
import mihon.core.viewmodel.StateViewModel
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class NovelExtensionsViewModel(
    private val preferences: SourcePreferences = Injekt.get(),
    private val jsPluginManager: JsPluginManager = Injekt.get(),
) : StateViewModel<ExtensionScreenState>(ExtensionScreenState()) {

    private val installSteps = MutableStateFlow<Map<String, InstallStep>>(emptyMap())

    init {
        val context = Injekt.get<Application>()
        viewModelScope.launchIO {
            combine(
                state.map { it.searchQuery }.distinctUntilChanged().debounce(SEARCH_DEBOUNCE_MILLIS),
                jsPluginManager.availablePlugins,
                jsPluginManager.installedPlugins,
                jsPluginManager.isLoading,
                installSteps,
            ) { query, available, installed, loading, steps ->
                val enabledLanguages = preferences.enabledLanguages.get()
                val extensions = available.map { plugin ->
                    plugin.toExtension(
                        installed = installed.find { it.plugin.id == plugin.id },
                        iconUrl = jsPluginManager.getIconUrl(plugin),
                    )
                } + installed
                    .filter { local -> available.none { it.id == local.plugin.id } }
                    .map {
                        it.plugin.toExtension(
                            installed = it,
                            iconUrl = jsPluginManager.getIconUrl(it.plugin),
                        )
                    }

                val filtered = extensions.filter { extension ->
                    val search = query?.trim().orEmpty()
                    search.isEmpty() ||
                        extension.name.contains(search, ignoreCase = true) ||
                        extension.lang.contains(search, ignoreCase = true) ||
                        extension.sources.any {
                            it.baseUrl.contains(search, ignoreCase = true) ||
                                it.id == search.toLongOrNull()
                        }
                }

                val updates = filtered.filter { it.hasUpdate }.map { it.toUiItem(steps) }
                val installedItems = filtered
                    .filter { it.isInstalled && !it.hasUpdate }
                    .map { it.toUiItem(steps) }
                val availableItems = filtered
                    .filter { !it.isInstalled && it.lang in enabledLanguages }
                    .groupBy { it.lang }
                    .toSortedMap(LocaleHelper.comparator)
                    .map { (lang, items) ->
                        ExtensionUiModel.Header.Text(LocaleHelper.getSourceDisplayName(lang, context)) to
                            items.map { it.toUiItem(steps) }
                    }

                buildMap {
                    if (updates.isNotEmpty()) {
                        put(ExtensionUiModel.Header.Resource(MR.strings.ext_updates_pending), updates)
                    }
                    if (installedItems.isNotEmpty()) {
                        put(ExtensionUiModel.Header.Resource(MR.strings.ext_installed), installedItems)
                    }
                    putAll(availableItems)
                } to Pair(updates.size, loading)
            }.collectLatest { (items, status) ->
                mutableState.update {
                    it.copy(
                        isLoading = status.second && items.isEmpty(),
                        items = items,
                        updates = status.first,
                    )
                }
            }
        }

        findAvailableExtensions()
    }

    fun search(query: String?) {
        mutableState.update { it.copy(searchQuery = query) }
    }

    fun updateAllExtensions() {
        viewModelScope.launchIO {
            state.value.items.values.flatten()
                .map { it.extension }
                .filterIsInstance<Extension.JsPlugin>()
                .filter { it.hasUpdate }
                .forEach(::installJsPlugin)
        }
    }

    fun installJsPlugin(extension: Extension.JsPlugin) {
        if (installSteps.value[extension.pkgName]?.isCompleted() == false) return
        installSteps.update { it + (extension.pkgName to InstallStep.Pending) }
        viewModelScope.launchIO {
            installSteps.update { it + (extension.pkgName to InstallStep.Downloading) }
            val pluginId = extension.pkgName.removePrefix(JsPlugin.PKG_PREFIX)
            val plugin = jsPluginManager.availablePlugins.value.find { it.id == pluginId }
            if (plugin == null || extension.repoUrl.isBlank()) {
                logcat(LogPriority.ERROR) { "Cannot install JS plugin $pluginId: plugin or repository is missing" }
                installSteps.update { it + (extension.pkgName to InstallStep.Error) }
                return@launchIO
            }
            if (jsPluginManager.installPlugin(plugin, extension.repoUrl)) {
                installSteps.update { it - extension.pkgName }
            } else {
                installSteps.update { it + (extension.pkgName to InstallStep.Error) }
            }
        }
    }

    fun uninstallExtension(extension: Extension.JsPlugin) {
        viewModelScope.launchIO {
            jsPluginManager.uninstallPlugin(extension.pkgName.removePrefix(JsPlugin.PKG_PREFIX))
        }
    }

    suspend fun resolveWebViewUrl(extension: Extension.JsPlugin): String {
        val source = jsPluginManager.getSource(extension.sources.first().id) as? JsSource
        return runCatching { source?.resolveUrl("").orEmpty() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: source?.baseUrl
            ?: extension.sources.first().baseUrl
    }

    fun findAvailableExtensions() {
        viewModelScope.launchIO {
            mutableState.update { it.copy(isRefreshing = true) }
            jsPluginManager.refreshAvailablePlugins(forceRefresh = true)
            mutableState.update { it.copy(isRefreshing = false) }
        }
    }

    private fun Extension.JsPlugin.toUiItem(steps: Map<String, InstallStep>): ExtensionUiModel.Item =
        ExtensionUiModel.Item(this, steps[pkgName] ?: InstallStep.Idle)
}

internal fun JsPlugin.toExtension(installed: InstalledJsPlugin?, iconUrl: String): Extension.JsPlugin {
    val availableVersion = version.toVersionCode()
    val installedVersion = installed?.plugin?.version?.toVersionCode() ?: 0L
    val langCode = langCode()
    return Extension.JsPlugin(
        name = displayName(),
        pkgName = pkgName(),
        versionName = version,
        versionCode = availableVersion,
        libVersion = 0.0,
        lang = langCode,
        isNsfw = hasAdultContentWarning(),
        isNovel = true,
        sources = listOf(
            Extension.Available.Source(
                id = sourceId(),
                lang = langCode,
                name = name,
                baseUrl = site,
            ),
        ),
        iconUrl = iconUrl,
        repoUrl = repositoryUrl ?: installed?.repositoryUrl.orEmpty(),
        isInstalled = installed != null,
        hasUpdate = installed != null && availableVersion > installedVersion,
    )
}

private fun String.toVersionCode(): Long =
    split('.')
        .take(4)
        .fold(0L) { result, part -> result * 1000 + (part.toLongOrNull() ?: 0L) }
