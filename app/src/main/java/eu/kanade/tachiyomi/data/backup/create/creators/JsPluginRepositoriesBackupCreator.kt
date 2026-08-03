package eu.kanade.tachiyomi.data.backup.create.creators

import eu.kanade.tachiyomi.data.backup.models.BackupJsPluginRepository
import eu.kanade.tachiyomi.data.backup.models.toBackupRepository
import eu.kanade.tachiyomi.jsplugin.JsPluginManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class JsPluginRepositoriesBackupCreator(
    private val jsPluginManager: JsPluginManager = Injekt.get(),
) {
    operator fun invoke(): List<BackupJsPluginRepository> =
        jsPluginManager.repositories.value.map { it.toBackupRepository() }
}
