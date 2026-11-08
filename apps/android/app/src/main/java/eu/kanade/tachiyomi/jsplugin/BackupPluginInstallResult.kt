package eu.kanade.tachiyomi.jsplugin

import eu.kanade.tachiyomi.jsplugin.model.JsPlugin

data class BackupPluginInstallResult(
    val plugin: JsPlugin,
    val installed: Boolean,
)
