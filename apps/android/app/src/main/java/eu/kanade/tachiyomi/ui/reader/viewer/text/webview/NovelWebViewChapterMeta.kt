package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.HtmlUtils
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mihon.core.archive.NOVEL_EPUB_CHAPTER_SCHEME
import java.net.URI
import java.net.URL
import java.net.URLDecoder

internal object NovelWebViewChapterMeta {

    const val CHAPTER_TAG_NAME = "nekori-chapter"
    const val CHAPTER_ID_ATTR = "data-nekori-chapter-id"
    const val CHAPTER_TITLE_ATTR = "data-nekori-chapter-name"
    const val CHAPTER_NUMBER_ATTR = "data-nekori-chapter-number"
    const val CHAPTER_PATH_ATTR = "data-nekori-chapter-path"
    const val CHAPTER_URL_ATTR = "data-nekori-chapter-url"
    const val CHAPTER_DIVIDER_CLASS = "nekori-chapter-divider"
    const val READER_DOCUMENT_BASE_URL = "https://nekori.reader/"

    fun String.htmlAttributeEscape(): String =
        replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    /** Decode the JSON string value returned by the bridge, if the result is a JSON string. */
    fun unescapeJsResult(result: String): String {
        if (!(result.startsWith("\"") && result.endsWith("\""))) return result
        return runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(result).jsonPrimitive.content
        }.getOrDefault(result)
    }

    fun toAbsoluteChapterUrl(chapterPath: String?, novelUrl: String?): String {
        val normalized = HtmlUtils.normalizeUrl(chapterPath).orEmpty().trim()
        if (normalized.isBlank()) return ""
        if (normalized.startsWith("http://") || normalized.startsWith("https://")) return normalized

        val novel = HtmlUtils.normalizeUrl(novelUrl).orEmpty().trim()
        if (!(novel.startsWith("http://") || novel.startsWith("https://"))) return normalized

        // URI.resolve collapses repeated path separators, which can change a source's external URL.
        return runCatching { URL(URL(novel), normalized).toExternalForm() }.getOrDefault(normalized)
    }

    fun resolveWebViewBaseUrl(chapterUrl: String?, novelUrl: String?, sourceBaseUrl: String? = null): String? {
        val repaired = HtmlUtils.normalizeUrl(chapterUrl)
        val absoluteChapterUrl = repaired?.trim().takeUnless { it.isNullOrBlank() }
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        if (absoluteChapterUrl != null) return absoluteChapterUrl
        val novel = HtmlUtils.normalizeUrl(novelUrl)?.trim().takeUnless { it.isNullOrBlank() }
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        if (novel != null) return novel

        val base = sourceBaseUrl?.trim().takeUnless { it.isNullOrBlank() }
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: return READER_DOCUMENT_BASE_URL
        val relative = repaired?.trim().takeUnless { it.isNullOrBlank() }
            ?: HtmlUtils.normalizeUrl(novelUrl)?.trim().takeUnless { it.isNullOrBlank() }
            ?: return base
        return runCatching { URI(base.trimEnd('/') + "/").resolve(relative.trimStart('/')).toString() }
            .getOrDefault(base)
    }

    fun resolveEpubChapterUrl(currentChapterUrl: String?, navigationUrl: String): String? {
        if (!navigationUrl.startsWith(NOVEL_EPUB_CHAPTER_SCHEME)) return null
        val bookUrl = currentChapterUrl?.substringBefore('#')?.takeIf { it.endsWith(".epub", ignoreCase = true) }
            ?: return null
        val href = runCatching {
            URLDecoder.decode(navigationUrl.removePrefix(NOVEL_EPUB_CHAPTER_SCHEME), "UTF-8")
        }.getOrNull()?.replace('\\', '/')?.takeIf { it.isNotBlank() } ?: return null
        val path = href.substringBefore('#')
        if (path.startsWith('/') || path.split('/').any { it == ".." }) return null
        return "$bookUrl#$href"
    }

    fun buildNovelJson(id: String, name: String, path: String): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("path", path)
    }

    /** Build reader metadata as data; source paths remain exactly as returned by the plugin. */
    fun buildChapterJson(chapter: ReaderChapter?, novelUrl: String?): JsonObject = buildJsonObject {
        val model = chapter?.chapter
        put("id", model?.id?.toString().orEmpty())
        put("name", model?.name.orEmpty())
        put("path", model?.url.orEmpty())
        put("progress", model?.last_page_read?.coerceAtLeast(0) ?: 0)
        model?.chapter_number?.takeIf { it.isFinite() && it >= 0f }?.let { put("number", it) }
        put("url", toAbsoluteChapterUrl(model?.url, novelUrl))
    }

    fun buildChaptersJson(chapters: List<ReaderChapter>, novelUrl: String?): JsonArray =
        buildJsonArray { chapters.forEach { add(buildChapterJson(it, novelUrl)) } }
}
