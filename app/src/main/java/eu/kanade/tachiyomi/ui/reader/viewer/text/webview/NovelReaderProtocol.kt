package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.net.URI

internal object NovelReaderProtocol {
    const val VERSION = 1
    const val TRANSPORT_NAME = "__nekoriNativeTransport"

    data class Message(
        val kind: String,
        val method: String?,
        val requestId: String?,
        val payload: JsonObject?,
        val result: JsonElement?,
        val error: JsonObject?,
    )

    fun decode(wire: String, documentId: String): Message? = runCatching {
        val objectValue = Json.parseToJsonElement(wire) as? JsonObject ?: return null
        val version = objectValue["protocolVersion"] as? JsonPrimitive ?: return null
        if (version.isString || version.intOrNull != VERSION) return null
        if (objectValue.string("documentId") != documentId) return null
        val kind = objectValue.string("kind") ?: return null
        if (kind !in setOf("boot", "ready", "event", "command", "result", "cancel")) return null
        val method = objectValue.string("method")
        val requestId = objectValue.string("requestId")
        if (kind in setOf("event", "command") && method.isNullOrBlank()) return null
        if (kind in setOf("command", "result", "cancel") && requestId.isNullOrBlank()) return null
        val payload = objectValue["payload"]
        if (kind != "result" && payload != null && payload !is JsonObject) return null
        if (objectValue["error"] != null && objectValue["error"] !is JsonObject) return null
        Message(kind, method, requestId, payload as? JsonObject, payload, objectValue["error"] as? JsonObject)
    }.getOrNull()

    fun origin(url: String): String? = runCatching {
        val uri = URI(url)
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme !in setOf("https", "http") || uri.userInfo != null) return null
        val host = uri.host?.lowercase() ?: return null
        val defaultPort = if (scheme == "https") 443 else 80
        val port = uri.port.takeUnless { it == -1 || it == defaultPort }
        "$scheme://$host${port?.let { ":$it" }.orEmpty()}"
    }.getOrNull()

    fun supportsVersion(version: String?): Boolean {
        val actual = version?.split('.')?.map { it.toIntOrNull() ?: return false } ?: return false
        val minimum = listOf(138, 0, 7204, 181)
        for (index in minimum.indices) {
            val comparison = (actual.getOrNull(index) ?: 0).compareTo(minimum[index])
            if (comparison != 0) return comparison > 0
        }
        return true
    }

    private fun JsonObject.string(name: String): String? =
        (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
}
