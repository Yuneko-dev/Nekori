package eu.kanade.tachiyomi.jsplugin.source

import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale

data class JsImageRequestInit(
    val method: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)

fun Request.Builder.applyJsImageRequestInit(init: JsImageRequestInit): Request.Builder = apply {
    init.headers.forEach { (name, value) -> header(name, value) }

    val method = init.method?.trim()?.uppercase(Locale.ENGLISH).orEmpty().ifBlank { "GET" }
    val body = init.body?.toRequestBody()
    val requestBody = when (method) {
        "GET", "HEAD" -> null
        "POST", "PUT", "PATCH", "PROPPATCH", "REPORT" -> body ?: ByteArray(0).toRequestBody()
        else -> body
    }
    method(method, requestBody)
}
