@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.ProcessedContent
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.ThemeUtils
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.CHAPTER_DIVIDER_CLASS
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.CHAPTER_ID_ATTR
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.CHAPTER_NUMBER_ATTR
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.CHAPTER_PATH_ATTR
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.CHAPTER_TAG_NAME
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.CHAPTER_TITLE_ATTR
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.CHAPTER_URL_ATTR
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.htmlAttributeEscape
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.Locale

internal object NovelWebViewDocumentBuilder {

    data class DocumentInput(
        val documentId: String,
        val readerConfig: JsonObject,
        val processed: ProcessedContent,
        val chapter: ReaderChapter?,
        val style: NovelWebViewStyler.CustomStylePayload,
        val themeTokens: ThemeUtils.ThemeTokens,
        val infiniteScrollEnabled: Boolean,
        val pagedLayoutEnabled: Boolean = false,
        val chapterDirection: NovelContentDirection = NovelContentDirection.LTR,
        val chapterLanguage: String = "",
        val blockMedia: Boolean,
        val chapterDirectives: NovelWebViewChapterDirectives = NovelWebViewChapterDirectives(),
    )

    fun assemble(input: DocumentInput): String {
        val chapterModel = input.chapter?.chapter
        val chapterId = chapterModel?.id ?: -1L
        val chapterName = chapterModel?.name.orEmpty()
        val chapterNumber = chapterModel?.chapter_number ?: -1f
        val chapterPath = chapterModel?.url.orEmpty()
        val mediaBlockCss = if (input.blockMedia) {
            "img, video, audio, source, svg, image { display: none !important; }"
        } else {
            ""
        }

        val chapterContent = if (input.processed.isPlainText) {
            // Text remains serialized data; the packaged TS runtime creates text nodes after boot.
            ""
        } else {
            extractBodyOrFallback(input.processed.text)
        }

        // Every layout needs the same chapter boundary for TTS, selection, summaries and edits.
        val divider = buildChapterDivider(chapterId, chapterName, chapterNumber, chapterPath, input)
        val wrapper = buildChapterWrapper(chapterId, chapterName, chapterNumber, chapterPath, input)
        val wrappedContent = listOf(divider, wrapper.first, chapterContent, wrapper.second)
            .filter { it.isNotBlank() }.joinToString("\n")

        val config = buildReaderConfig(input)
        val lang = readerLanguageTag().htmlAttributeEscape()
        val assetRoot = ASSET_ROOT
        val videoAssets = if (input.chapterDirectives.isVideo) {
            """
                <script src="$assetRoot/hls.min.js"></script>
                <script src="$assetRoot/videojs.min.js"></script>
            """.trimIndent()
        } else {
            ""
        }
        return """
            <!DOCTYPE html>
            <html lang="$lang">
            <head>
                <meta charset="UTF-8">
                <meta id="nekori-viewport" name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
                <link rel="stylesheet" href="${NovelWebViewStyler.READER_CSS_URL}">
                <style>${input.themeTokens.cssVariables.escapeForStyleTag()}\n$mediaBlockCss</style>
                <style id="nekori-custom-style">${input.style.css.escapeForStyleTag()}</style>
                $videoAssets
                <script id="nekori-reader-config" type="application/json">${config.toString().safeJsonForHtml()}</script>
                <script src="$assetRoot/reader.js"></script>
            </head>
            <body class="${input.style.bodyClasses.htmlAttributeEscape()}">
                <div id="nekori-reader" class="nekori-reader" data-nekori-reader-root>
                    $wrappedContent
                    <div id="reader-ui"></div>
                </div>
            </body>
            </html>
        """.trimIndent()
    }

    fun assembleErrorDocument(documentId: String, readerConfig: JsonObject, themeTokens: ThemeUtils.ThemeTokens): String {
        val lang = readerLanguageTag().htmlAttributeEscape()
        val config = readerConfig.withDocumentId(documentId)
        val style = config["style"] as? JsonObject
        val css = (style?.get("css") as? JsonPrimitive)?.content.orEmpty()
        val bodyClasses = (style?.get("bodyClasses") as? JsonPrimitive)?.content.orEmpty()
        return """
            <!DOCTYPE html>
            <html lang="$lang">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
                <link rel="stylesheet" href="${NovelWebViewStyler.READER_CSS_URL}">
                <style>${themeTokens.cssVariables.escapeForStyleTag()}</style>
                <style id="nekori-custom-style">${css.escapeForStyleTag()}</style>
                <script id="nekori-reader-config" type="application/json">${config.toString().safeJsonForHtml()}</script>
                <script src="$ASSET_ROOT/reader.js"></script>
            </head>
            <body class="${bodyClasses.htmlAttributeEscape()}" data-nekori-error-document><div id="nekori-reader" class="nekori-reader" data-nekori-reader-root><div id="reader-ui"></div></div></body>
            </html>
        """.trimIndent()
    }

    private fun buildReaderConfig(input: DocumentInput): JsonObject = buildJsonObject {
        input.readerConfig.forEach { (key, value) -> put(key, value) }
        put("documentId", input.documentId)
        if (input.chapterDirectives.localVideo != null) put("pluginJavaScript", "")
        putJsonObject("style") {
            input.readerConfig["style"]?.let { existing ->
                existing.jsonObject.forEach { (key, value) -> put(key, value) }
            }
            put("css", input.style.css)
            put("bodyClasses", input.style.bodyClasses)
            put("backgroundColor", input.style.backgroundColor)
            put(
                "background",
                runCatching {
                    Json.parseToJsonElement(input.style.backgroundConfig)
                }.getOrDefault(JsonObject(emptyMap())),
            )
        }
        if (input.processed.isPlainText) {
            putJsonObject("initialContent") {
                put("kind", "plainText")
                put("text", input.processed.text)
            }
        }
    }

    private fun JsonObject.withDocumentId(documentId: String): JsonObject = buildJsonObject {
        this@withDocumentId.forEach { (key, value) -> if (key != "documentId") put(key, value) }
        put("documentId", documentId)
    }

    private fun buildChapterDivider(
        chapterId: Long,
        chapterName: String,
        chapterNumber: Float,
        chapterPath: String,
        input: DocumentInput,
    ): String {
        if (chapterId == -1L || !input.infiniteScrollEnabled) return ""
        val absoluteUrl = NovelWebViewChapterMeta.toAbsoluteChapterUrl(chapterPath, input.chapter?.chapter?.url)
            .htmlAttributeEscape()
        val name = chapterName.htmlAttributeEscape()
        val path = chapterPath.htmlAttributeEscape()
        return """<div class="$CHAPTER_DIVIDER_CLASS" $CHAPTER_ID_ATTR="$chapterId" $CHAPTER_TITLE_ATTR="$name" $CHAPTER_NUMBER_ATTR="$chapterNumber" $CHAPTER_PATH_ATTR="$path" $CHAPTER_URL_ATTR="$absoluteUrl" style="visibility:hidden;height:0;margin:0;padding:0;border:none;"></div>"""
    }

    private fun buildChapterWrapper(
        chapterId: Long,
        chapterName: String,
        chapterNumber: Float,
        chapterPath: String,
        input: DocumentInput,
    ): Pair<String, String> {
        if (chapterId == -1L) return "" to ""
        val absoluteUrl = NovelWebViewChapterMeta.toAbsoluteChapterUrl(chapterPath, input.chapter?.chapter?.url)
            .htmlAttributeEscape()
        val name = chapterName.htmlAttributeEscape()
        val path = chapterPath.htmlAttributeEscape()
        val direction = input.chapterDirection.htmlValue
        val language = input.chapterLanguage.htmlAttributeEscape()
        val start = """<$CHAPTER_TAG_NAME class="nekori-chapter" $CHAPTER_ID_ATTR="$chapterId" $CHAPTER_TITLE_ATTR="$name" $CHAPTER_NUMBER_ATTR="$chapterNumber" $CHAPTER_PATH_ATTR="$path" $CHAPTER_URL_ATTR="$absoluteUrl" dir="$direction" lang="$language">"""
        return start to "</$CHAPTER_TAG_NAME>"
    }

    internal fun extractBodyOrFallback(html: String): String = try {
        val doc = org.jsoup.Jsoup.parse(html)
        val embeddedStyles = doc.head().select("style, link[rel=stylesheet]").joinToString("\n") { it.outerHtml() }
        val body = doc.body()
        val bodyHtml = when {
            body.hasText() -> body.html()
            body.children().isNotEmpty() -> body.html()
            else -> html
        }
        listOf(embeddedStyles, bodyHtml).filter { it.isNotBlank() }.joinToString("\n")
    } catch (_: Exception) {
        html
    }

    internal fun String.escapeForStyleTag(): String =
        replace(Regex("</style>", RegexOption.IGNORE_CASE)) { "<\\/" + it.value.substring(2) }

    private fun String.safeJsonForHtml(): String = replace("<", "\\u003c")

    const val PLAIN_TEXT_CLASS = "nekori-plain-text"
    const val ATTR_DATA_PLAIN_TEXT = "data-nekori-plain-text"
    private const val ASSET_ROOT = "https://nekori.reader/assets"
}

internal fun readerLanguageTag(
    appLocales: LocaleListCompat = AppCompatDelegate.getApplicationLocales(),
    systemLocale: Locale = Locale.getDefault(),
): String {
    val tag = (appLocales.get(0) ?: systemLocale).toLanguageTag()
    return tag.takeIf { it.isNotBlank() && it != "und" } ?: "en"
}
