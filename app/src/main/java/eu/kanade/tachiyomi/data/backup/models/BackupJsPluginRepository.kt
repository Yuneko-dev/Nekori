package eu.kanade.tachiyomi.data.backup.models

import eu.kanade.tachiyomi.jsplugin.model.JsPluginRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
data class BackupJsPluginRepository(
    @ProtoNumber(1) val name: String,
    @ProtoNumber(2) val url: String,
    @ProtoNumber(3) val enabled: Boolean = true,
) {
    fun toRepository() = JsPluginRepository(name = name, url = url, enabled = enabled)
}

fun JsPluginRepository.toBackupRepository() = BackupJsPluginRepository(
    name = name,
    url = url,
    enabled = enabled,
)
