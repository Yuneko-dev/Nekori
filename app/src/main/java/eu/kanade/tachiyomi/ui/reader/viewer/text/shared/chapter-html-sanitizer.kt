@file:Suppress("ktlint:standard:filename")

package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

import org.jsoup.Jsoup
import org.jsoup.nodes.Comment
import org.jsoup.nodes.TextNode

internal object ChapterHtmlSanitizer {
    private const val MAX_SANITIZE_PASSES = 4
    private val urlAttributes =
        setOf("href", "src", "action", "formaction", "xlink:href", "data", "poster", "background", "cite")
    private val dataUrlMediaTags = setOf("img", "image", "source", "video", "audio", "track")
    private val textCommentRegex = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)

    fun sanitize(
        content: String,
        target: RenderTarget,
        keepEmbeddedCss: Boolean,
        keepEmbeddedJs: Boolean,
        blockMedia: Boolean,
    ): String {
        val stripJs = target == RenderTarget.TEXT_VIEW || !keepEmbeddedJs
        val stripCss = target == RenderTarget.TEXT_VIEW || !keepEmbeddedCss

        val first = sanitizePass(content, target, stripJs, stripCss, blockMedia)
        if (!stripJs) return if (first.changed) first.html else content

        // Serialized markup can parse differently than the original (foreign content mXSS), and the
        // WebView only ever parses our serialization, so repeat until it is stable and clean.
        var html = first.html
        repeat(MAX_SANITIZE_PASSES) {
            val pass = sanitizePass(html, target, stripJs, stripCss, blockMedia)
            if (!pass.changed && pass.html == html) return html
            html = pass.html
        }
        return HtmlUtils.escapeHtml(Jsoup.parseBodyFragment(html).text())
    }

    private class SanitizeResult(val html: String, val changed: Boolean)

    private fun sanitizePass(
        content: String,
        target: RenderTarget,
        stripJs: Boolean,
        stripCss: Boolean,
        blockMedia: Boolean,
    ): SanitizeResult {
        val doc = Jsoup.parseBodyFragment(content)
        doc.outputSettings().prettyPrint(false)
        val body = doc.body()
        var changed = false

        fun removeAll(query: String) {
            val found = body.select(query)
            if (found.isNotEmpty()) {
                found.remove()
                changed = true
            }
        }

        removeAll("noscript, base")
        if (stripJs) {
            // Embedded documents run their own script; SVG animations can set href to javascript: at runtime.
            removeAll(
                "script, meta[http-equiv], iframe, frame, frameset, object, embed, " +
                    "animate, set, animatemotion, animatetransform",
            )
        }
        if (stripCss) removeAll("style, link[rel~=(?i)stylesheet]")
        if (blockMedia) removeAll("img, image, video, audio, source")

        for (element in body.allElements) {
            for (node in element.childNodes().toList()) {
                if (node is Comment) {
                    node.remove()
                    changed = true
                } else if (node is TextNode && "<!--" in node.wholeText) {
                    val cleaned = node.wholeText.replace(textCommentRegex, "")
                    if (cleaned != node.wholeText) {
                        node.text(cleaned)
                        changed = true
                    }
                }
            }

            if (stripCss && target == RenderTarget.WEB_VIEW && element.hasAttr("style")) {
                element.removeAttr("style")
                changed = true
            }

            if (stripJs) {
                val unsafe = element.attributes().asList().filter {
                    isUnsafeAttribute(element.normalName(), it.key, it.value)
                }
                unsafe.forEach { element.removeAttr(it.key) }
                if (unsafe.isNotEmpty()) changed = true
            }

            // Native loading="lazy" never fires inside the custom scroll/paged-transform containers.
            if (target == RenderTarget.WEB_VIEW &&
                (element.normalName() == "img" || element.normalName() == "iframe") &&
                element.attr("loading").equals("lazy", ignoreCase = true)
            ) {
                element.attr("loading", "eager")
                changed = true
            }
        }

        return SanitizeResult(body.html(), changed)
    }

    private fun isUnsafeAttribute(tag: String, key: String, value: String): Boolean {
        val name = key.lowercase()
        if (name.length > 2 && name.startsWith("on") && name.drop(2).all { it in 'a'..'z' }) return true
        if (name == "srcdoc") return true
        if (name !in urlAttributes) return false
        val url = value.filterNot { it.isWhitespace() || it.isISOControl() }.lowercase()
        return url.startsWith("javascript:") ||
            url.startsWith("vbscript:") ||
            (url.startsWith("data:") && tag !in dataUrlMediaTags)
    }
}
