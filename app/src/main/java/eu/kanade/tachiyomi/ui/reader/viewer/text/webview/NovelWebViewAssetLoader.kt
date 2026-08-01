package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import android.content.res.AssetManager
import android.webkit.WebResourceResponse
import java.net.URI
import java.nio.charset.StandardCharsets

internal class NovelWebViewAssetLoader(
    private val assets: AssetManager,
) {
    fun intercept(url: String): WebResourceResponse? {
        if (!isReaderAssetUrl(url)) return null
        val path = resolveAssetPath(url) ?: return errorResponse(403, "Forbidden")
        val stream = runCatching { assets.open(path, AssetManager.ACCESS_STREAMING) }
            .getOrNull()
            ?: return errorResponse(404, "Not Found")
        return WebResourceResponse(mimeType(path), null, stream)
    }

    private fun errorResponse(statusCode: Int, reason: String) = WebResourceResponse(
        "text/plain",
        "UTF-8",
        statusCode,
        reason,
        emptyMap(),
        reason.byteInputStream(),
    )

    companion object {
        private const val ORIGIN_SCHEME = "https"
        private const val ORIGIN_HOST = "tsundoku.reader"
        private const val URL_PREFIX = "/assets/"
        private const val ASSET_ROOT = "novel-reader"

        internal fun isReaderAssetUrl(url: String): Boolean {
            val uri = runCatching { URI(url) }.getOrNull() ?: return false
            return uri.scheme.equals(ORIGIN_SCHEME, true) &&
                uri.host.equals(ORIGIN_HOST, true) &&
                uri.rawPath.orEmpty().startsWith(URL_PREFIX)
        }

        internal fun resolveAssetPath(url: String): String? {
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            if (!uri.scheme.equals(ORIGIN_SCHEME, true) || !uri.host.equals(ORIGIN_HOST, true)) return null

            val rawPath = uri.rawPath ?: return null
            if (!rawPath.startsWith(URL_PREFIX) || rawPath.length == URL_PREFIX.length) return null

            val segments = rawPath.removePrefix(URL_PREFIX).split('/')
            if (segments.any(String::isEmpty)) return null
            val decoded = segments.map { decodePathSegment(it) ?: return null }
            if (decoded.any { it == "." || it == ".." || '\u0000' in it || '/' in it || '\\' in it }) return null

            return (listOf(ASSET_ROOT) + decoded).joinToString("/")
        }

        private fun decodePathSegment(raw: String): String? = runCatching {
            val bytes = ByteArray(raw.length)
            var byteCount = 0
            var index = 0
            val output = StringBuilder(raw.length)

            fun flushBytes() {
                if (byteCount > 0) {
                    output.append(String(bytes, 0, byteCount, StandardCharsets.UTF_8))
                    byteCount = 0
                }
            }

            while (index < raw.length) {
                if (raw[index] == '%') {
                    if (index + 2 >= raw.length) return null
                    val value = raw.substring(index + 1, index + 3).toIntOrNull(16) ?: return null
                    bytes[byteCount++] = value.toByte()
                    index += 3
                } else {
                    flushBytes()
                    output.append(raw[index++])
                }
            }
            flushBytes()
            output.toString()
        }.getOrNull()

        private fun mimeType(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
            "js" -> "application/javascript"
            "css" -> "text/css"
            "json" -> "application/json"
            "svg" -> "image/svg+xml"
            "html", "htm" -> "text/html"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            else -> "application/octet-stream"
        }
    }
}
