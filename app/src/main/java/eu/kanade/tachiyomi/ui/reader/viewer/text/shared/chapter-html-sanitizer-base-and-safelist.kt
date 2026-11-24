@file:Suppress("ktlint:standard:filename")

package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Attribute
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.safety.Safelist

internal object ChapterHtmlBaseAndSafelist {
    private const val PLACEHOLDER_BASE = "https://chapter.nekori.invalid/"
    private const val BASE_RESOLVED_SELECTOR = "[src], [href], [poster], [data-src], [srcset], [data-srcset]"
    private val baseResolvedAttributes = listOf("src", "href", "poster", "data-src")
    private val srcsetAttributes = listOf("srcset", "data-srcset")
    private val schemePrefix = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
    private val mediaProtocols = arrayOf("http", "https", "data", "tsundoku-novel-image")

    // <base> is dropped so a chapter can't repoint the reader's own URLs, but the chapter's relative
    // links and media were written against it, so they are made absolute first.
    fun resolveAgainstBase(root: Element): Boolean {
        val href = root.selectFirst("base[href]")?.attr("href")?.trim().orEmpty()
        // HttpUrl resolves RFC 3986 style, so "?page=2" keeps the base document; java.net drops it.
        val base = href.toHttpUrlOrNull() ?: return false
        fun resolve(value: String): String? {
            if (value.isEmpty() || value.startsWith("#") || schemePrefix.containsMatchIn(value)) return null
            return base.resolve(value)?.toString()
        }
        var resolved = false
        for (element in root.select(BASE_RESOLVED_SELECTOR)) {
            for (key in baseResolvedAttributes) {
                val absolute = resolve(element.attr(key).trim()) ?: continue
                element.attr(key, absolute)
                resolved = true
            }
            for (key in srcsetAttributes) {
                val value = element.attr(key).trim()
                if (value.isEmpty()) continue
                var changed = false
                val rewritten = srcsetCandidates(value).joinToString(", ") { (rawUrl, descriptor) ->
                    val url = resolve(rawUrl)?.also { changed = true } ?: rawUrl
                    if (descriptor != null) "$url $descriptor" else url
                }
                if (changed) {
                    element.attr(key, rewritten)
                    resolved = true
                }
            }
        }
        return resolved
    }

    // The HTML srcset rules: a url runs to whitespace and gives up trailing commas, and its descriptors
    // run to the next comma outside parentheses, so "a.jpg 1x,b.jpg 2x" is two candidates while a comma
    // inside a url stays part of it.
    internal fun srcsetCandidates(value: String): List<Pair<String, String?>> {
        val candidates = mutableListOf<Pair<String, String?>>()
        var i = 0
        while (i < value.length) {
            while (i < value.length && (value[i].isHtmlSpace() || value[i] == ',')) i++
            if (i >= value.length) break
            val urlStart = i
            while (i < value.length && !value[i].isHtmlSpace()) i++
            var url = value.substring(urlStart, i)
            if (url.endsWith(',')) {
                url = url.trimEnd(',')
                if (url.isNotEmpty()) candidates += url to null
                continue
            }
            val descriptorStart = i
            var depth = 0
            while (i < value.length) {
                val c = value[i]
                if (c == '(') {
                    depth++
                } else if (c == ')' && depth > 0) {
                    depth--
                } else if (c == ',' && depth == 0) {
                    break
                }
                i++
            }
            val descriptor = value.substring(descriptorStart, i).trim { it.isHtmlSpace() }.ifEmpty { null }
            candidates += url to descriptor
        }
        return candidates
    }

    // HTML splits srcset on ASCII whitespace only; a no-break space stays part of the url.
    private fun Char.isHtmlSpace() = this == ' ' || this == '\t' || this == '\n' || this == '\u000C' || this == '\r'

    // Relative links only survive cleaning when resolvable, so clean against a placeholder base;
    // preserveRelativeLinks keeps them relative in the output.
    fun cleanWithSafelist(html: String, keepCss: Boolean, blockMedia: Boolean): String =
        Jsoup.clean(
            html,
            PLACEHOLDER_BASE,
            fallbackSafelist(keepCss, blockMedia),
            Document.OutputSettings().prettyPrint(false),
        )

    private fun fallbackSafelist(keepCss: Boolean, blockMedia: Boolean): Safelist =
        DataAttributeSafelist()
            .preserveRelativeLinks(true)
            .addTags(
                "hr", "s", "del", "ins", "mark", "ruby", "rt", "rp", "section", "article", "aside",
                "header", "footer", "figure", "figcaption", "center",
                "video", "audio", "source", "track", "picture", "meta",
            )
            .addAttributes(":all", "class", "id", "dir", "lang", "title")
            .addAttributes("meta", "name", "content")
            .addAttributes("video", "src", "poster", "controls", "loop", "muted", "width", "height", "preload")
            .addAttributes("audio", "src", "controls", "loop", "muted", "preload")
            .addAttributes("source", "src", "srcset", "type", "media", "sizes")
            .addAttributes("track", "src", "kind", "srclang", "label", "default")
            .addAttributes("img", "srcset", "sizes", "loading")
            .addProtocols("img", "src", *mediaProtocols)
            .addProtocols("video", "src", *mediaProtocols)
            .addProtocols("video", "poster", *mediaProtocols)
            .addProtocols("audio", "src", *mediaProtocols)
            .addProtocols("source", "src", *mediaProtocols)
            .addProtocols("track", "src", *mediaProtocols)
            .apply {
                if (keepCss) {
                    addTags("style")
                    addAttributes(":all", "style")
                }
                if (blockMedia) removeTags("img", "video", "audio", "source", "track", "picture")
            }

    // data-* attributes carry lazy-load sources and reader state; their names can't be listed upfront.
    private class DataAttributeSafelist : Safelist(relaxed()) {
        override fun isSafeAttribute(tagName: String, el: Element, attr: Attribute): Boolean {
            if (super.isSafeAttribute(tagName, el, attr)) return true
            if (!attr.key.startsWith("data-")) return false
            val normalized = attr.value.filterNot { it.isWhitespace() || it.isISOControl() }.lowercase()
            return !normalized.contains("javascript:") && !normalized.contains("vbscript:")
        }
    }
}
