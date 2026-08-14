package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed interface LnReaderMessage {
    data class Save(val progress: Int) : LnReaderMessage

    data object Refetch : LnReaderMessage

    data object Next : LnReaderMessage

    /** Any in-page failure the reader should surface inline. Posted by `reader.error(message)`. */
    data class ShowError(val message: String) : LnReaderMessage

    companion object {
        fun parse(message: String): LnReaderMessage? = runCatching {
            val payload = Json.parseToJsonElement(message).jsonObject
            when (payload["type"]?.jsonPrimitive?.content) {
                "save" -> Save((payload.intData() ?: return null).coerceIn(0, 100))
                "refetch" -> Refetch
                "next" -> Next
                "error" -> ShowError(payload.stringData() ?: return null)
                else -> null
            }
        }.getOrNull()

        private fun JsonObject.intData(): Int? =
            this["data"]?.jsonPrimitive?.takeUnless { it.isString }?.intOrNull

        private fun JsonObject.stringData(): String? =
            this["data"]?.jsonPrimitive?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
    }
}
