package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import android.content.Context
import android.webkit.WebResourceResponse
import eu.kanade.tachiyomi.data.background.ReaderBackgroundManager
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.backgroundSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.novel.TDMR
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

internal class NovelWebViewBackground(private val context: Context, private val preferences: ReaderPreferences) {
    private val manager = ReaderBackgroundManager(context)

    fun configJson(): String {
        val settings = preferences.backgroundSettings()
        val config = Json.parseToJsonElement(
            Json.encodeToString(settings.copy(image = imageUrl(settings.image))),
        ).jsonObject
        return buildJsonObject {
            config.forEach { (key, value) -> put(key, value) }
            put("errorMessage", JsonPrimitive(context.stringResource(TDMR.strings.novel_background_load_failed)))
        }.toString()
    }

    fun intercept(url: String): WebResourceResponse? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!uri.host.equals(HOST, true) || !uri.scheme.equals("https", true)) return null
        val id = imageId(url)
        // A source document may read its displayed background, never the rest of the user's collection.
        val stream = id?.takeIf { it == preferences.backgroundSettings().image }
            ?.let { runCatching { manager.open(it) }.getOrNull() }
        val headers = mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store")
        return if (stream != null) {
            WebResourceResponse(ReaderBackgroundManager.mimeType(id), null, stream).apply { responseHeaders = headers }
        } else {
            WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", headers, "Not Found".byteInputStream())
        }
    }

    companion object {
        private const val HOST = "tsundoku.background"

        internal fun imageUrl(id: String): String = if (id.isBlank()) {
            ""
        } else {
            "https://$HOST/image?id=${URLEncoder.encode(id, "UTF-8")}"
        }

        internal fun imageId(url: String): String? = runCatching {
            val uri = URI(url)
            if (!uri.scheme.equals("https", true) || !uri.host.equals(HOST, true) || uri.port != -1 ||
                uri.userInfo != null ||
                uri.rawPath != "/image"
            ) {
                return null
            }
            val query = uri.rawQuery ?: return null
            if (!query.startsWith("id=") || '&' in query) return null
            URLDecoder.decode(query.removePrefix("id="), "UTF-8").takeIf {
                ReaderBackgroundManager.validFileName(it.removePrefix("asset:"))
            }
        }.getOrNull()
    }
}
