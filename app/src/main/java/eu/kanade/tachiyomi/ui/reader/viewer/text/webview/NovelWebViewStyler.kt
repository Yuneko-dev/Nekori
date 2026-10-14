package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import android.content.Context
import android.view.View
import android.webkit.WebView
import androidx.core.graphics.Insets
import androidx.core.net.toUri
import eu.kanade.presentation.reader.settings.CodeSnippet
import eu.kanade.presentation.reader.settings.safeTitle
import eu.kanade.tachiyomi.jsplugin.JsPluginManager
import eu.kanade.tachiyomi.jsplugin.source.JsSource
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.NovelProgress
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.ThemeUtils
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import logcat.LogPriority
import logcat.logcat
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

internal class NovelWebViewStyler(
    private val context: Context,
    private val preferences: ReaderPreferences,
    private val webView: WebView,
    private val container: View,
    private val executeCommand: (ReaderCommand) -> Unit,
) {
    private val background = NovelWebViewBackground(context, preferences)

    fun interceptBackground(url: String) = background.intercept(url)

    data class CustomStylePayload(
        val css: String,
        val bodyClasses: String,
        val backgroundColor: Int,
        val backgroundConfig: String = "{}",
    )

    fun applyScrollbarSettings(target: WebView = webView) {
        target.isVerticalScrollBarEnabled = preferences.novelShowProgressSlider.get()
        target.isHorizontalScrollBarEnabled = false
        target.scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
        target.isScrollbarFadingEnabled = true
        target.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        target.layoutDirection = View.LAYOUT_DIRECTION_LTR
    }

    fun buildPayload(): CustomStylePayload {
        val fontSize = preferences.novelFontSize.get()
        val fontFamily = preferences.novelFontFamily.get()
        val lineHeight = preferences.novelLineHeight.get()
        val insets = (context as? ReaderActivity)?.novelContentInsets ?: Insets.NONE
        val density = context.resources.displayMetrics.density
        val marginLeft = preferences.novelMarginLeft.get() + insets.left / density
        val marginRight = preferences.novelMarginRight.get() + insets.right / density
        val marginTop = preferences.novelMarginTop.get() + insets.top / density
        val marginBottom = preferences.novelMarginBottom.get() + insets.bottom / density
        val paragraphIndent = preferences.novelParagraphIndent.get()
        val paragraphSpacing = preferences.novelParagraphSpacing.get()
        val textAlign = preferences.novelTextAlign.get()
        val theme = preferences.novelTheme.get()
        val hideChapterTitle = preferences.novelHideChapterTitle.get()
        val (finalBgColor, finalTextColor) = ThemeUtils.getThemeColors(context, preferences, theme)
        val bgColorHex = ThemeUtils.colorToHex(finalBgColor)
        val textColorHex = ThemeUtils.colorToHex(finalTextColor)
        val customCss = preferences.novelCustomCss.get()
        val pluginCustomCss = currentJsSource()?.customCSS.orEmpty()
            .takeIf { preferences.novelPluginUseCustomCss.get() }.orEmpty()
        val useOriginalFonts = preferences.novelUseOriginalFonts.get() || fontFamily.isBlank()
        val enabledSnippetsCss = try {
            Json.decodeFromString<List<CodeSnippet>>(preferences.novelCustomCssSnippets.get())
                .filter { it.enabled }.joinToString("\n") { it.code }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Failed to parse CSS snippets" }
            ""
        }
        val (fontFaceDeclaration, effectiveFontFamily) = resolveFontFace(fontFamily, useOriginalFonts)
        val textSelect = if (preferences.novelTextSelectable.get()) "text" else "none"
        val minFragmentLines = if (preferences.novelAllowLonelyLines.get()) 1 else 2
        val fontSizeCss = fontSize.toString() + "px"
        val marginTopCss = marginTop.toString() + "px"
        val marginRightCss = marginRight.toString() + "px"
        val marginBottomCss = marginBottom.toString() + "px"
        val marginLeftCss = marginLeft.toString() + "px"
        val paragraphIndentCss = paragraphIndent.toString() + "em"
        val paragraphSpacingCss = paragraphSpacing.toString() + "em"
        val css = """
            $fontFaceDeclaration
            :root {
                --reader-font-size: $fontSizeCss;
                --reader-font-family: $effectiveFontFamily;
                --reader-line-height: $lineHeight;
                --reader-margin-top: $marginTopCss;
                --reader-margin-right: $marginRightCss;
                --reader-margin-bottom: $marginBottomCss;
                --reader-min-fragment-lines: $minFragmentLines;
                --reader-margin-left: $marginLeftCss;
                --reader-text-color: $textColorHex;
                --reader-background-color: $bgColorHex;
                --reader-text-align: $textAlign;
                --reader-user-select: $textSelect;
                --reader-paragraph-indent: $paragraphIndentCss;
                --reader-paragraph-spacing: $paragraphSpacingCss;
            }
            $pluginCustomCss
            $customCss
            $enabledSnippetsCss
        """.trimIndent().replace("\n", " ")
        return CustomStylePayload(
            css = css,
            bodyClasses = buildList {
                if (hideChapterTitle) add("nekori-reader-hide-title")
                if (!preferences.novelSourceCssPriority.get()) add("nekori-reader-force-style")
                if (useOriginalFonts) add("nekori-reader-original-font")
            }.joinToString(" "),
            backgroundColor = finalBgColor,
            backgroundConfig = background.configJson(),
        )
    }

    fun initialPluginJavaScript(): String =
        if (preferences.novelPluginUseCustomJs.get()) currentJsSource()?.customJS.orEmpty() else ""

    private fun currentJsSource(): JsSource? {
        val readerActivity = context as? ReaderActivity ?: return null
        (readerActivity.viewModel.getSource() as? JsSource)?.let { return it }
        val sourceId = readerActivity.viewModel.manga?.source ?: return null
        return Injekt.get<JsPluginManager>().getSource(sourceId) as? JsSource
    }

    private fun resolveFontFace(fontFamily: String, useOriginalFonts: Boolean): Pair<String, String> {
        if (useOriginalFonts) {
            fontUriString = null
            return "" to "inherit"
        }
        if (!(fontFamily.startsWith("file://") || fontFamily.startsWith("content://"))) {
            fontUriString = null
            return "" to fontFamily
        }
        fontUriString = fontFamily
        val isOtf = fontFamily.endsWith(".otf", ignoreCase = true)
        val format = if (isOtf) "opentype" else "truetype"
        val fontHash = fontFamily.hashCode()
        val url = "$FONT_URL_PREFIX?v=$fontHash"
        return "@font-face { font-family:'CustomFont'; src:url('$url') format('$format'); font-display:swap; }" to
            "'CustomFont', sans-serif"
    }

    @Volatile private var fontUriString: String? = null

    @Volatile private var cachedFontBytes: Pair<String, ByteArray>? = null
    private var lastAppliedSnippetCode: Map<String, String> = emptyMap()
    private var lastAppliedCustomJs: String? = null

    fun interceptFont(url: String): android.webkit.WebResourceResponse? {
        if (!url.startsWith(FONT_URL_PREFIX)) return null
        val family = fontUriString ?: return null
        val bytes = loadFontBytes(family) ?: return null
        val mime = if (family.endsWith(".otf", ignoreCase = true)) "font/otf" else "font/ttf"
        return android.webkit.WebResourceResponse(mime, null, java.io.ByteArrayInputStream(bytes)).apply {
            responseHeaders = mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "max-age=31536000")
        }
    }

    private fun loadFontBytes(family: String): ByteArray? {
        cachedFontBytes?.let { if (it.first == family) return it.second }
        return try {
            val bytes = context.contentResolver.openInputStream(family.toUri())?.use { it.readBytes() } ?: return null
            cachedFontBytes = family to bytes
            bytes
        } catch (_: Exception) {
            logcat(LogPriority.ERROR) { "Failed to read custom font" }
            null
        }
    }

    fun injectStyles() {
        val payload = buildPayload()
        webView.setBackgroundColor(payload.backgroundColor)
        container.setBackgroundColor(payload.backgroundColor)
        executeCommand(
            ReaderCommand(
                "style.apply",
                buildJsonObject {
                    put("css", payload.css)
                    put("bodyClasses", payload.bodyClasses)
                    put("backgroundColor", payload.backgroundColor)
                    put("background", Json.parseToJsonElement(payload.backgroundConfig))
                },
            ),
        )
    }

    fun injectScript(isAppend: Boolean = false, reapplyChangedOnly: Boolean = false) {
        if (!isAppend) {
            val customJs = preferences.novelCustomJs.get()
            val shouldRun = !reapplyChangedOnly || lastAppliedCustomJs != customJs
            if (customJs.isNotBlank() && shouldRun) runSnippet(customJs, "custom-js")
            lastAppliedCustomJs = customJs
        }
        val enabledSnippets = try {
            Json.decodeFromString<List<CodeSnippet>>(preferences.novelCustomJsSnippets.get())
                .filter { it.enabled && (!isAppend || it.runOnAppend) }
        } catch (_: Exception) {
            logcat(LogPriority.ERROR) { "Failed to parse JS snippets" }
            emptyList()
        }
        snippetsToRun(enabledSnippets, reapplyChangedOnly, lastAppliedSnippetCode)
            .forEach { runSnippet(it.code, it.safeTitle()) }
        lastAppliedSnippetCode = nextAppliedSnippetCode(lastAppliedSnippetCode, enabledSnippets)
    }

    private fun runSnippet(code: String, name: String) {
        executeCommand(snippetCommand(name, code))
    }

    fun injectNextChapterButton(chapterName: String, nextChapterName: String?) {
        executeCommand(
            ReaderCommand(
                "ui.configureNextChapter",
                buildJsonObject {
                    put("hasNext", nextChapterName != null)
                    put("finishedText", context.stringResource(TDMR.strings.reader_chapter_finished, chapterName))
                    put("nextText", context.stringResource(TDMR.strings.reader_next_chapter, nextChapterName.orEmpty()))
                    put("noNextText", context.stringResource(MR.strings.transition_no_next))
                },
            ),
        )
    }

    fun injectReaderUi() {
        executeCommand(
            ReaderCommand(
                "ui.configure",
                buildJsonObject {
                    put("bionicEnabled", preferences.novelBionicReading.get())
                    put("ttsEnabled", preferences.novelTtsEnabled.get())
                    put(
                        "labels",
                        buildJsonObject {
                            put("tts", context.stringResource(TDMR.strings.reader_tts_control))
                            put("imageClose", context.stringResource(TDMR.strings.reader_image_close))
                        },
                    )
                },
            ),
        )
    }

    fun setTtsEnabled(enabled: Boolean) {
        executeCommand(ReaderCommand("ui.setTtsEnabled", buildJsonObject { put("enabled", enabled) }))
    }

    fun setBionicReading(enabled: Boolean) {
        executeCommand(ReaderCommand("ui.setBionic", buildJsonObject { put("enabled", enabled) }))
    }

    fun injectScrollTracking(infiniteScrollEnabled: Boolean) {
        val threshold = preferences.novelAutoLoadNextChapterAt.get().coerceIn(0, 100) / 100.0
        executeCommand(
            ReaderCommand(
                "scrollTracking.configure",
                buildJsonObject {
                    put("infiniteScrollEnabled", infiniteScrollEnabled)
                    put("loadThreshold", threshold)
                    put("doneThreshold", NovelProgress.DONE_THRESHOLD)
                },
            ),
        )
    }

    fun injectScopedChapterAnchors() {
        executeCommand(ReaderCommand("anchors.install"))
    }

    companion object {
        const val STYLE_ID_CUSTOM = "nekori-custom-style"
        const val ID_NEXT_CHAPTER_BTN_CONTAINER = "next-chapter-btn-container"
        const val READER_CSS_URL = "https://nekori.reader/assets/reader.css"
        const val FONT_URL_PREFIX = "https://nekori.reader/fonts/custom"

        internal fun snippetCommand(name: String, code: String) =
            ReaderCommand(
                "snippet.run",
                buildJsonObject {
                    put("name", name)
                    put("code", code)
                },
            )

        internal fun snippetsToRun(
            enabledSnippets: List<CodeSnippet>,
            reapplyChangedOnly: Boolean,
            lastAppliedSnippetCode: Map<String, String>,
        ): List<CodeSnippet> = if (reapplyChangedOnly) {
            enabledSnippets.filter { lastAppliedSnippetCode[it.id] != it.code }
        } else {
            enabledSnippets
        }

        internal fun nextAppliedSnippetCode(
            lastAppliedSnippetCode: Map<String, String>,
            enabledSnippets: List<CodeSnippet>,
        ): Map<String, String> = lastAppliedSnippetCode + enabledSnippets.associate { it.id to it.code }
    }
}
