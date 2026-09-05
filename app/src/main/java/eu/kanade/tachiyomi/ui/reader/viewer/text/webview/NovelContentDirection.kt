package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import org.jsoup.Jsoup
import java.util.Locale

internal enum class NovelContentDirection(val htmlValue: String) {
    LTR("ltr"),
    RTL("rtl"),
}

/** Detects book direction from rendered text, with language used only when text has no strong character. */
internal fun detectNovelContentDirection(
    renderedHtml: String,
    fallbackLanguage: String?,
): NovelContentDirection {
    val text = runCatching {
        Jsoup.parse(renderedHtml).apply {
            select("script, style, template, noscript, [hidden], [aria-hidden=true]").remove()
        }.text()
    }.getOrDefault(renderedHtml)
    var offset = 0
    while (offset < text.length) {
        val codePoint = text.codePointAt(offset)
        when (Character.getDirectionality(codePoint)) {
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return NovelContentDirection.LTR
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
            -> return NovelContentDirection.RTL
        }
        offset += Character.charCount(codePoint)
    }
    val locale = fallbackLanguage
        ?.takeUnless { it.isBlank() || it.equals("auto", ignoreCase = true) }
        ?.let(Locale::forLanguageTag)
        ?: return NovelContentDirection.LTR
    return if (locale.language.lowercase() in RTL_LANGUAGES) {
        NovelContentDirection.RTL
    } else {
        NovelContentDirection.LTR
    }
}

private val RTL_LANGUAGES = setOf("ar", "dv", "fa", "he", "iw", "ku", "ps", "sd", "ug", "ur", "yi")
