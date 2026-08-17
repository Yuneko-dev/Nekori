package eu.kanade.tachiyomi.data.translation

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser

/**
 * Stateless utility functions for HTML manipulation during translation.
 *
 * Extracted from [TranslationService] to satisfy SRP and allow reuse
 * from other call-sites (e.g. real-time reader translation).
 */
object TranslationHtmlUtils {

    private const val TRANSLATABLE_SELECTOR = "p, div, span, h1, h2, h3, h4, h5, h6, li, td, th"
    private const val BLOCK_SELECTOR = "p, div, h1, h2, h3, h4, h5, h6, li, td, th, ul, ol, blockquote, pre, table"

    class TranslationPlan internal constructor(
        private val body: Element,
        val texts: List<String>,
        private val targets: List<TranslationTarget>,
    ) {
        fun apply(translations: List<String>): String {
            require(translations.size == targets.size) {
                "Expected ${targets.size} translations, received ${translations.size}"
            }
            targets.zip(translations).forEach { (target, translation) -> target.replace(translation) }
            return body.html()
        }
    }

    internal sealed interface TranslationTarget {
        fun replace(translation: String)

        class Html(private val element: Element) : TranslationTarget {
            override fun replace(translation: String) {
                element.html(translation)
            }
        }

        class Text(private val node: TextNode) : TranslationTarget {
            override fun replace(translation: String) {
                node.text(translation)
            }
        }
    }

    /**
     * A segment without letters (scene breaks, ellipses, bare numbers) has nothing to translate.
     * Models silently drop or merge those, which used to break the paragraph alignment of a whole chunk,
     * so they are left untouched in the DOM instead of being sent.
     */
    private fun hasTranslatableText(text: String) = text.any(Char::isLetter)

    /**
     * Builds a LNReader-compatible translation plan: translate leaf block HTML and direct text nodes,
     * then write the results into the original DOM instead of rebuilding the chapter from plain text.
     */
    fun prepareTranslation(html: String): TranslationPlan {
        val document = Jsoup.parseBodyFragment(html)
        document.outputSettings().prettyPrint(false)
        val texts = mutableListOf<String>()
        val targets = mutableListOf<TranslationTarget>()

        fun visit(element: Element) {
            val isTranslatable = element.`is`(TRANSLATABLE_SELECTOR)
            if (isTranslatable && element.children().none { it.`is`(BLOCK_SELECTOR) }) {
                val innerHtml = element.html().trim()
                if (innerHtml.isNotEmpty() && hasTranslatableText(element.text())) {
                    texts += innerHtml
                    targets += TranslationTarget.Html(element)
                }
                return
            }

            element.childNodes().forEach { node ->
                when (node) {
                    is TextNode -> if (isTranslatable) {
                        val text = node.text().trim()
                        if (hasTranslatableText(text)) {
                            texts += text
                            targets += TranslationTarget.Text(node)
                        }
                    }
                    is Element -> visit(node)
                }
            }
        }
        visit(document.body())

        if (texts.isEmpty() && hasTranslatableText(document.body().text())) {
            texts += document.body().html().trim()
            targets += TranslationTarget.Html(document.body())
        }
        return TranslationPlan(document.body(), texts, targets)
    }

    /**
     * Normalize line-break variants so paragraph handling is stable.
     */
    fun normalizeLineBreaks(text: String): String {
        return text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace('\u2028', '\n')
            .replace('\u2029', '\n')
    }

    // ── Text ↔ HTML conversion ──────────────────────────────────────

    /**
     * Convert HTML to plain text, preserving paragraph boundaries as `\n\n`.
     *
     * Uses Jsoup for robust entity decoding instead of manual replacement.
     */
    fun extractTextFromHtml(html: String): String {
        // Strip embedded base64 data URIs before parsing (they can be huge)
        val cleaned = html.replace(
            Regex("data:[a-zA-Z0-9/+.-]+;base64,[A-Za-z0-9+/=\\s]+"),
            "",
        )

        val doc = Jsoup.parse(cleaned)
        // Insert markers for structural breaks so we can split later
        doc.select("p, div, br, h1, h2, h3, h4, h5, h6, li, blockquote").forEach { el ->
            if (el.tagName() == "br") {
                el.before(org.jsoup.nodes.TextNode("\n"))
            } else {
                el.before(org.jsoup.nodes.TextNode("\n\n"))
            }
        }

        return normalizeLineBreaks(doc.body().wholeText())
            .replace(Regex("[ \\t\\u000B\\f]+"), " ")
            .replace(Regex("\\n[ \\t]+"), "\n")
            .replace(Regex("[ \\t]+\\n"), "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    /**
     * Build a complete translated-chapter HTML string from an optional title
     * and a list of already-translated paragraph strings.
     *
     * This is the single source of truth previously duplicated in
     * `translateChapter` and `savePartialTranslation`.
     */
    fun buildTranslatedHtml(
        translatedTitle: String?,
        translatedParagraphs: List<String>,
    ): String = buildString {
        if (!translatedTitle.isNullOrBlank()) {
            append("<h1>${escapeHtml(translatedTitle.trim())}</h1>")
        }
        translatedParagraphs.forEach { paragraph ->
            append("<p>${escapeHtml(paragraph.trim()).replace("\n", "<br/>")}</p>")
        }
    }

    /** Reads only saved paragraph entries; the optional translated title is not resume data. */
    fun extractTranslatedParagraphs(html: String): List<String> = Jsoup.parse(html)
        .body()
        .children()
        .filter { it.tagName() == "p" }
        .map { normalizeLineBreaks(it.wholeText()).trim() }

    // ── Language code normalisation (fixes 6.4) ─────────────────────

    /**
     * Normalise a language code to its base 2-letter form for comparison.
     *
     * Examples: `"EN-US"` → `"en"`, `"zh-TW"` → `"zh"`, `"ja"` → `"ja"`.
     */
    fun normalizeLanguageCode(code: String): String {
        return code.lowercase().substringBefore('-').substringBefore('_')
    }

    /**
     * Compare two language codes ignoring case and regional suffixes.
     */
    fun languageCodesMatch(a: String, b: String): Boolean {
        return normalizeLanguageCode(a) == normalizeLanguageCode(b)
    }

    // ── HTML escaping (fixes 6.5) ───────────────────────────────────

    /**
     * Minimal HTML entity escaping for text that will be embedded inside tags.
     *
     * Uses Jsoup's [Parser.unescapeEntities] in reverse via [org.jsoup.nodes.Entities].
     */
    fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }
}
