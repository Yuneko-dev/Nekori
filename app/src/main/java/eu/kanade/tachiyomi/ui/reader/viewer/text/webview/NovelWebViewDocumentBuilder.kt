@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

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
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.TSUNDOKU_CHAPTER_ATTR
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.htmlAttributeEscape
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.quoteForJson

internal object NovelWebViewDocumentBuilder {

    data class DocumentInput(
        val processed: ProcessedContent,
        val chapter: ReaderChapter?,
        val style: NovelWebViewStyler.CustomStylePayload,
        val themeTokens: ThemeUtils.ThemeTokens,
        val tsundokuScript: String,
        val pluginJavaScript: String,
        val infiniteScrollEnabled: Boolean,
        val blockMedia: Boolean,
        val compatConfigJson: String = "{}",
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

        val finalContent = if (input.processed.isPlainText) {
            // Per-paragraph <p> (textContent-set, never parsed as markup) instead of one <pre>, so
            // plain text exposes the same block elements the copy/quote paragraph-index counter walks.
            val paragraphsJsonArray = input.processed.text
                .split(Regex("\n{2,}"))
                .filter { it.isNotEmpty() }
                .joinToString(",", prefix = "[", postfix = "]") { quoteForJson(it) }
            """
                <div class="$PLAIN_TEXT_CLASS" $ATTR_DATA_PLAIN_TEXT="1"></div>
                <script>
                    (function() {
                        var container = document.querySelector('.$PLAIN_TEXT_CLASS');
                        var paragraphs = $paragraphsJsonArray;
                        var frag = document.createDocumentFragment();
                        for (var i = 0; i < paragraphs.length; i++) {
                            var p = document.createElement('p');
                            p.style.whiteSpace = 'pre-wrap';
                            p.style.wordBreak = 'break-word';
                            p.style.overflowWrap = 'anywhere';
                            p.textContent = paragraphs[i];
                            frag.appendChild(p);
                        }
                        container.appendChild(frag);
                    })();
                </script>
            """.trimIndent()
        } else {
            extractBodyOrFallback(input.processed.text)
        }

        val chapterContent = if (input.infiniteScrollEnabled) {
            val chapterDivider = buildChapterDivider(chapterId, chapterName, chapterNumber, chapterPath, input)
            val (chapterWrapperStart, chapterWrapperEnd) = buildChapterWrapper(
                chapterId,
                chapterName,
                chapterNumber,
                chapterPath,
                input,
            )
            """
                $chapterDivider
                $chapterWrapperStart
                $finalContent
                $chapterWrapperEnd
            """.trimIndent()
        } else {
            finalContent
        }

        val escapedInitialStyle = input.style.css.escapeForStyleTag()

        val escapedThemeCss = input.themeTokens.cssVariables.escapeForStyleTag()
        val escapedThemeJson = input.themeTokens.jsObject
            .replace("\\", "\\\\")
            .replace("</script>", "<\\/script>")
            .replace("</Script>", "<\\/Script>")
            .replace("</SCRIPT>", "<\\/SCRIPT>")
        val themeExposureScript = "window.TsundokuTheme = $escapedThemeJson;"
        val pluginScript = input.pluginJavaScript.escapeForScriptTag()
        val videoAssets = if (input.chapterDirectives.video != null) {
            """
                <link rel="stylesheet" href="$ASSET_ROOT/core-player.css">
                <script src="$ASSET_ROOT/hls.min.js"></script>
                <script src="$ASSET_ROOT/core-player.js"></script>
            """.trimIndent()
        } else {
            ""
        }
        val localVideoScript = if (input.chapterDirectives.localVideo != null) {
            """
                <script>
                    document.getElementById('$LOCAL_VIDEO_BUTTON_ID')?.addEventListener('click', function(event) {
                        event.stopPropagation();
                        Android.suppressReaderGestures();
                        Android.playLocalVideo();
                    });
                </script>
            """.trimIndent()
        } else {
            ""
        }
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta id="tsundoku-viewport" name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
                <link rel="stylesheet" href="${NovelWebViewStyler.READER_CSS_URL}">
                <style>
                    $escapedThemeCss
                    $mediaBlockCss
                </style>
                <style id="tsundoku-custom-style">$escapedInitialStyle</style>
                <script>${input.tsundokuScript}</script>
                <script>$themeExposureScript</script>
                ${input.chapterDirectives.metadataHtml}
            </head>
            <body class="${input.style.bodyClasses}">
                <div id="LNReader-chapter">
                    $chapterContent
                </div>
                <div id="reader-ui"></div>
                <script id="lnreader-compat-config" type="application/json">${input.compatConfigJson}</script>
                <script src="$ASSET_ROOT/lnreader-compat.js"></script>
                $videoAssets
                $localVideoScript
                ${if (pluginScript.isNotBlank() && input.chapterDirectives.localVideo == null) "<script>$pluginScript</script>" else ""}
            </body>
            </html>
        """.trimIndent()
    }

    @Suppress("ktlint:standard:max-line-length")
    private fun buildChapterDivider(
        chapterId: Long,
        chapterName: String,
        chapterNumber: Float,
        chapterPath: String,
        input: DocumentInput,
    ): String {
        if (chapterId == -1L || !input.infiniteScrollEnabled) return ""
        val absoluteUrl = NovelWebViewChapterMeta
            .toAbsoluteChapterUrl(chapterPath, input.chapter?.chapter?.url)
            .htmlAttributeEscape()
        val name = chapterName.htmlAttributeEscape()
        val path = chapterPath.htmlAttributeEscape()
        // visibility:hidden (not display:none) so the first chapter's boundary marker still
        // generates a layout box: getBoundingClientRect().top on a display:none element is always 0,
        // which made updateChapterBoundaries record startOffset = scrollY (the scroll position at
        // requery time) instead of the chapter's true top, zeroing progress and misattributing
        // scroll to the wrong chapter whenever a reflow re-queried mid-scroll.
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
        val absoluteUrl = NovelWebViewChapterMeta
            .toAbsoluteChapterUrl(chapterPath, input.chapter?.chapter?.url)
            .htmlAttributeEscape()
        val name = chapterName.htmlAttributeEscape()
        val path = chapterPath.htmlAttributeEscape()

        @Suppress("ktlint:standard:max-line-length")
        val start = """<$CHAPTER_TAG_NAME $CHAPTER_ID_ATTR="$chapterId" $CHAPTER_TITLE_ATTR="$name" $CHAPTER_NUMBER_ATTR="$chapterNumber" $CHAPTER_PATH_ATTR="$path" $CHAPTER_URL_ATTR="$absoluteUrl" $TSUNDOKU_CHAPTER_ATTR="1">"""
        val end = "</$CHAPTER_TAG_NAME>"
        return start to end
    }

    internal fun extractBodyOrFallback(html: String): String = try {
        val doc = org.jsoup.Jsoup.parse(html)
        val embeddedStyles = doc.head()
            .select("style, link[rel=stylesheet]")
            .joinToString("\n") { it.outerHtml() }
        val body = doc.body()
        val bodyHtml = when {
            body.hasText() -> body.html()
            body.children().isNotEmpty() -> body.html()
            else -> html
        }
        listOf(embeddedStyles, bodyHtml)
            .filter { it.isNotBlank() }
            .joinToString("\n")
    } catch (_: Exception) {
        html
    }

    internal fun String.escapeForStyleTag(): String =
        replace(Regex("</style>", RegexOption.IGNORE_CASE)) { "<\\/" + it.value.substring(2) }

    internal fun String.escapeForScriptTag(): String =
        replace(Regex("</script>", RegexOption.IGNORE_CASE)) { "<\\/" + it.value.substring(2) }

    const val PLAIN_TEXT_CLASS = "tsundoku-plain-text"
    const val ATTR_DATA_PLAIN_TEXT = "data-tsundoku-plain-text"
    private const val ASSET_ROOT = "https://tsundoku.reader/assets"
}
