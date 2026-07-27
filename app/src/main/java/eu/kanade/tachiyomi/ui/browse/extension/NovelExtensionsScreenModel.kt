package eu.kanade.tachiyomi.ui.browse.extension

import android.app.Application
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.components.SEARCH_DEBOUNCE_MILLIS
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.jsplugin.JsPluginManager
import eu.kanade.tachiyomi.jsplugin.model.InstalledJsPlugin
import eu.kanade.tachiyomi.jsplugin.model.JsPlugin
import eu.kanade.tachiyomi.jsplugin.source.JsSource
import eu.kanade.tachiyomi.util.system.LocaleHelper
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class NovelExtensionsScreenModel(
    private val preferences: SourcePreferences = Injekt.get(),
    private val jsPluginManager: JsPluginManager = Injekt.get(),
) : StateScreenModel<ExtensionsScreenModel.State>(ExtensionsScreenModel.State()) {

    init {
        val context = Injekt.get<Application>()
        screenModelScope.launchIO {
            combine(
                state.map { it.searchQuery }.distinctUntilChanged().debounce(SEARCH_DEBOUNCE_MILLIS),
                jsPluginManager.availablePlugins,
                jsPluginManager.installedPlugins,
                jsPluginManager.isLoading,
            ) { query, available, installed, loading ->
                val enabledLanguages = preferences.enabledLanguages.get()
                val extensions = available.map { plugin ->
                    plugin.toExtension(installed.find { it.plugin.id == plugin.id })
                } + installed
                    .filter { local -> available.none { it.id == local.plugin.id } }
                    .map { it.plugin.toExtension(it) }

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

                val updates = filtered.filter { it.hasUpdate }.map(::toUiItem)
                val installedItems = filtered
                    .filter { it.isInstalled && !it.hasUpdate }
                    .map(::toUiItem)
                val availableItems = filtered
                    .filter { !it.isInstalled && it.lang in enabledLanguages }
                    .groupBy { it.lang }
                    .toSortedMap(LocaleHelper.comparator)
                    .map { (lang, items) ->
                        ExtensionUiModel.Header.Text(LocaleHelper.getSourceDisplayName(lang, context)) to
                            items.map(::toUiItem)
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
                        isLoading = status.second,
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
        screenModelScope.launchIO {
            state.value.items.values.flatten()
                .map { it.extension }
                .filterIsInstance<Extension.JsPlugin>()
                .filter { it.hasUpdate }
                .forEach(::installJsPlugin)
        }
    }

    fun installJsPlugin(extension: Extension.JsPlugin) {
        screenModelScope.launchIO {
            val pluginId = extension.pkgName.removePrefix(JsPlugin.PKG_PREFIX)
            val plugin = jsPluginManager.availablePlugins.value.find { it.id == pluginId }
            if (plugin == null || extension.repoUrl.isBlank()) {
                logcat(LogPriority.ERROR) { "Cannot install JS plugin $pluginId: plugin or repository is missing" }
                return@launchIO
            }
            jsPluginManager.installPlugin(plugin, extension.repoUrl)
        }
    }

    fun uninstallExtension(extension: Extension.JsPlugin) {
        screenModelScope.launchIO {
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
        screenModelScope.launchIO {
            mutableState.update { it.copy(isRefreshing = true) }
            jsPluginManager.refreshAvailablePlugins(forceRefresh = true)
            mutableState.update { it.copy(isRefreshing = false) }
        }
    }

    private fun JsPlugin.toExtension(installed: InstalledJsPlugin?): Extension.JsPlugin {
        val availableVersion = versionCode(version)
        val installedVersion = installed?.let { versionCode(it.plugin.version) } ?: 0L
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
            iconUrl = jsPluginManager.getIconUrl(this),
            repoUrl = repositoryUrl ?: installed?.repositoryUrl.orEmpty(),
            isInstalled = installed != null,
            hasUpdate = installed != null && availableVersion > installedVersion,
        )
    }

    private fun versionCode(version: String): Long =
        version.split('.')
            .take(4)
            .fold(0L) { result, part -> result * 1000 + (part.toLongOrNull() ?: 0L) }

    private fun toUiItem(extension: Extension): ExtensionUiModel.Item =
        ExtensionUiModel.Item(extension, InstallStep.Idle)
}
