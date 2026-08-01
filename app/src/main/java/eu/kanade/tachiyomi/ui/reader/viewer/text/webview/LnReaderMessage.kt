package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed interface LnReaderMessage {
    data class Save(val progress: Int) : LnReaderMessage

    data object Refetch : LnReaderMessage

    data object Next : LnReaderMessage

    companion object {
        fun parse(message: String): LnReaderMessage? = runCatching {
            val payload = Json.parseToJsonElement(message).jsonObject
            when (payload["type"]?.jsonPrimitive?.content) {
                "save" -> {
                    val progress = payload["data"]?.jsonPrimitive
                        ?.takeUnless { it.isString }
                        ?.intOrNull
                        ?: return null
                    Save(progress.coerceIn(0, 100))
                }
                "refetch" -> Refetch
                "next" -> Next
                else -> null
            }
        }.getOrNull()
    }
}
