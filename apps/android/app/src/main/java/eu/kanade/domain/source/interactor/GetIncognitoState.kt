package eu.kanade.domain.source.interactor

import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.jsplugin.JsPluginManager
import eu.kanade.tachiyomi.jsplugin.model.InstalledJsPlugin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

class GetIncognitoState(
    private val basePreferences: BasePreferences,
    private val sourcePreferences: SourcePreferences,
    private val pluginManager: JsPluginManager,
) {
    fun await(sourceId: Long?): Boolean {
        if (basePreferences.incognitoMode.get()) return true
        if (sourceId == null) return false

        val packageName = pluginManager.installedPlugins.value.packageNameForSource(sourceId) ?: return false
        return packageName in sourcePreferences.incognitoExtensions.get()
    }

    fun subscribe(sourceId: Long?): Flow<Boolean> {
        if (sourceId == null) return basePreferences.incognitoMode.changes()

        return combine(
            basePreferences.incognitoMode.changes(),
            sourcePreferences.incognitoExtensions.changes(),
            pluginManager.installedPlugins,
        ) { incognito, incognitoExtensions, installedPlugins ->
            incognito || installedPlugins.packageNameForSource(sourceId) in incognitoExtensions
        }.distinctUntilChanged()
    }
}

private fun List<InstalledJsPlugin>.packageNameForSource(sourceId: Long): String? {
    return firstOrNull { it.plugin.sourceId() == sourceId }?.plugin?.pkgName()
}
