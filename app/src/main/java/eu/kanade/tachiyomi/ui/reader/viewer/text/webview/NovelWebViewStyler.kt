package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import android.view.View
import android.webkit.WebView
import androidx.core.net.toUri
import eu.kanade.presentation.reader.settings.CodeSnippet
import eu.kanade.presentation.reader.settings.safeTitle
import eu.kanade.tachiyomi.jsplugin.JsPluginManager
import eu.kanade.tachiyomi.jsplugin.source.JsSource
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.NovelProgress
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.ThemeUtils
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.CHAPTER_DIVIDER_CLASS
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.CHAPTER_ID_ATTR
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.CHAPTER_TAG_NAME
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.TSUNDOKU_OBJECT_NAME
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.quoteForJson
import kotlinx.serialization.json.Json
import logcat.LogPriority
import logcat.logcat
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

internal class NovelWebViewStyler(
    private val activity: ReaderActivity,
    private val preferences: ReaderPreferences,
    private val webView: WebView,
    private val container: View,
    private val evaluateJs: (String) -> Unit,
) {

    data class CustomStylePayload(
        val css: String,
        val bodyClasses: String,
        val backgroundColor: Int,
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
        val marginLeft = preferences.novelMarginLeft.get()
        val marginRight = preferences.novelMarginRight.get()
        val marginTop = preferences.novelMarginTop.get()
        val marginBottom = preferences.novelMarginBottom.get()
        val paragraphIndent = preferences.novelParagraphIndent.get()
        val paragraphSpacing = preferences.novelParagraphSpacing.get()
        val textAlign = preferences.novelTextAlign.get()
        val theme = preferences.novelTheme.get()
        val hideChapterTitle = preferences.novelHideChapterTitle.get()

        val (finalBgColor, finalTextColor) = ThemeUtils.getThemeColors(activity, preferences, theme)

        val bgColorHex = ThemeUtils.colorToHex(finalBgColor)
        val textColorHex = ThemeUtils.colorToHex(finalTextColor)

        val customCss = preferences.novelCustomCss.get()
        val pluginCustomCss = currentJsSource()
            ?.customCSS
            .orEmpty()
            .takeIf { preferences.novelPluginUseCustomCss.get() }
            .orEmpty()
        val useOriginalFonts = preferences.novelUseOriginalFonts.get()

        val cssSnippetsJson = preferences.novelCustomCssSnippets.get()
        val enabledSnippetsCss = try {
            val snippets = Json.decodeFromString<List<CodeSnippet>>(cssSnippetsJson)
            snippets.filter { it.enabled }.joinToString("\n") { it.code }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Failed to parse CSS snippets: ${e.message}" }
            ""
        }

        val (fontFaceDeclaration, effectiveFontFamily) = resolveFontFace(fontFamily, useOriginalFonts)

        val textSelect = if (preferences.novelTextSelectable.get()) "text" else "none"

        val hideChapterTitleCss = if (hideChapterTitle) {
            "#LNReader-chapter h1:first-of-type, #LNReader-chapter h2:first-of-type, " +
                "#LNReader-chapter h3:first-of-type, #LNReader-chapter h4:first-of-type, " +
                "#LNReader-chapter h5:first-of-type, #LNReader-chapter h6:first-of-type " +
                "{ display: none !important; }"
        } else {
            ""
        }

        val css = """
            $fontFaceDeclaration
            :root {
                --reader-font-size: ${fontSize}px;
                --reader-font-family: $effectiveFontFamily;
                --reader-line-height: $lineHeight;
                --reader-margin-top: ${marginTop}px;
                --reader-margin-right: ${marginRight}px;
                --reader-margin-bottom: ${marginBottom}px;
                --reader-margin-left: ${marginLeft}px;
                --reader-text-color: $textColorHex;
                --reader-background-color: $bgColorHex;
                --reader-text-align: $textAlign;
                --reader-user-select: $textSelect;
                --reader-paragraph-indent: ${paragraphIndent}em;
                --reader-paragraph-spacing: ${paragraphSpacing}em;
            }
            $hideChapterTitleCss
            $pluginCustomCss
            $customCss
            $enabledSnippetsCss
        """.trimIndent().replace("\n", " ")

        return CustomStylePayload(
            css = css,
            bodyClasses = buildList {
                if (!preferences.novelSourceCssPriority.get()) add("tsundoku-reader-force-style")
                if (useOriginalFonts) add("tsundoku-reader-original-font")
            }.joinToString(" "),
            backgroundColor = finalBgColor,
        )
    }

    fun initialPluginJavaScript(): String {
        if (!preferences.novelPluginUseCustomJs.get()) return ""
        return currentJsSource()?.customJS.orEmpty()
    }

    private fun currentJsSource(): JsSource? {
        (activity.viewModel.getSource() as? JsSource)?.let { return it }
        val sourceId = activity.viewModel.manga?.source ?: return null
        return Injekt.get<JsPluginManager>().getSource(sourceId) as? JsSource
    }

    private fun resolveFontFace(fontFamily: String, useOriginalFonts: Boolean): Pair<String, String> {
        if (useOriginalFonts) {
            fontUriString = null
            return "" to fontFamily
        }
        if (!(fontFamily.startsWith("file://") || fontFamily.startsWith("content://"))) {
            fontUriString = null
            return "" to fontFamily
        }

        // Reference the font by URL instead of a base64 data: URI so the multi-MB bytes never enter
        // the CSS string (re-escaped + bridged + re-parsed on every style change) or the LOS/GC.
        // interceptFont() streams the bytes from disk via the WebView's shouldInterceptRequest, off
        // the main thread, and the WebView decodes and caches the face once.
        fontUriString = fontFamily
        val isOtf = fontFamily.endsWith(".otf", ignoreCase = true)
        val format = if (isOtf) "opentype" else "truetype"
        // Version by the uri so switching fonts busts the WebView's cached face for the same URL.
        val url = "$FONT_URL_PREFIX?v=${fontFamily.hashCode()}"
        val declaration =
            "@font-face { font-family: 'CustomFont'; src: url('$url') format('$format'); font-display: swap; }"
        return declaration to "'CustomFont', sans-serif"
    }

    // Written on the UI thread (style injection), read on the WebView worker thread (interceptFont);
    // @Volatile so the worker sees the latest value instead of a stale cached one.
    @Volatile private var fontUriString: String? = null

    @Volatile private var cachedFontBytes: Pair<String, ByteArray>? = null

    private var lastAppliedSnippetCode: Map<String, String> = emptyMap()
    private var lastAppliedCustomJs: String? = null

    /**
     * Serve the custom font for the sentinel URL referenced by the injected @font-face. Runs on the
     * WebView's worker thread (not the UI thread), so the disk read never stalls the UI. Fonts are
     * fetched in CORS mode, so the response must allow the cross-origin document.
     */
    fun interceptFont(url: String): android.webkit.WebResourceResponse? {
        if (!url.startsWith(FONT_URL_PREFIX)) return null
        val family = fontUriString ?: return null
        val bytes = loadFontBytes(family) ?: return null
        val mime = if (family.endsWith(".otf", ignoreCase = true)) "font/otf" else "font/ttf"
        return android.webkit.WebResourceResponse(mime, null, java.io.ByteArrayInputStream(bytes)).apply {
            responseHeaders = mapOf(
                "Access-Control-Allow-Origin" to "*",
                "Cache-Control" to "max-age=31536000",
            )
        }
    }

    private fun loadFontBytes(family: String): ByteArray? {
        cachedFontBytes?.let { if (it.first == family) return it.second }
        return try {
            val bytes = activity.contentResolver.openInputStream(family.toUri())?.use { it.readBytes() }
                ?: return null
            cachedFontBytes = family to bytes
            bytes
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Failed to read custom font: ${e.message}" }
            null
        }
    }

    fun injectStyles() {
        val payload = buildPayload()
        webView.setBackgroundColor(payload.backgroundColor)
        container.setBackgroundColor(payload.backgroundColor)
        val js = NovelWebViewJsAssets.loadWith(
            activity,
            "inject-styles.js",
            mapOf(
                "STYLE_ID" to STYLE_ID_CUSTOM,
                "CSS" to quoteForJson(payload.css),
            ),
        )
        evaluateJs(js)
    }

    fun injectScript(
        isAppend: Boolean = false,
        reapplyChangedOnly: Boolean = false,
        buildTsundokuScript: () -> String,
    ) {
        evaluateJs(buildTsundokuScript())

        // Appends re-run only runOnAppend snippets; one-shot code stays on the initial load so it
        // doesn't fire again on every appended chapter.
        if (!isAppend) {
            val customJs = preferences.novelCustomJs.get()
            val shouldRunCustomJs = !reapplyChangedOnly || lastAppliedCustomJs != customJs
            if (customJs.isNotBlank() && shouldRunCustomJs) evaluateJs(customJs)
            lastAppliedCustomJs = customJs
        }

        val jsSnippetsJson = preferences.novelCustomJsSnippets.get()
        val enabledSnippets = try {
            Json.decodeFromString<List<CodeSnippet>>(jsSnippetsJson)
                .filter { it.enabled && (!isAppend || it.runOnAppend) }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Failed to parse JS snippets: ${e.message}" }
            emptyList()
        }

        val toRun = snippetsToRun(enabledSnippets, reapplyChangedOnly, lastAppliedSnippetCode)
        if (toRun.isNotEmpty()) evaluateJs(buildSnippetRunnerJs(toRun))

        lastAppliedSnippetCode = nextAppliedSnippetCode(lastAppliedSnippetCode, enabledSnippets)
    }

    private fun buildSnippetRunnerJs(snippets: List<CodeSnippet>): String {
        val entries = snippets.mapIndexed { i, s ->
            val safeName = s.safeTitle().ifBlank { "snippet-$i" }
            "{ name: ${quoteForJson(safeName)}, code: ${quoteForJson(s.code)} }"
        }.joinToString(",\n")
        return """
            (function(){
                var __snippets = [$entries];
                var __host = document.head || document.documentElement;
                for (var __i = 0; __i < __snippets.length; __i++) {
                    var __el = document.createElement('script');
                    __el.textContent = __snippets[__i].code +
                        '\n//# sourceURL=tsundoku-snippet-' + __snippets[__i].name + '.js';
                    __host.appendChild(__el);
                    __host.removeChild(__el);
                }
            })();
        """.trimIndent()
    }

    fun injectNextChapterButton(chapterName: String, nextChapterName: String?) {
        val js = NovelWebViewJsAssets.loadWith(
            activity,
            "next-chapter-button.js",
            mapOf(
                "BTN_CONTAINER_ID" to ID_NEXT_CHAPTER_BTN_CONTAINER,
                "HAS_NEXT_CHAPTER" to (nextChapterName != null).toString(),
                "FINISHED_TEXT" to quoteForJson(
                    activity.stringResource(TDMR.strings.reader_chapter_finished, chapterName),
                ),
                "NEXT_CHAPTER_TEXT" to quoteForJson(
                    activity.stringResource(TDMR.strings.reader_next_chapter, nextChapterName.orEmpty()),
                ),
                "NO_NEXT_CHAPTER_TEXT" to quoteForJson(activity.stringResource(MR.strings.transition_no_next)),
            ),
        )
        evaluateJs(js)
    }

    fun injectReaderUi() {
        val js = NovelWebViewJsAssets.loadWith(
            activity,
            "reader-ui.js",
            mapOf(
                "BIONIC_ENABLED" to preferences.novelBionicReading.get().toString(),
                "TTS_ENABLED" to preferences.novelTtsEnabled.get().toString(),
                "TTS_STATE_EVENT" to NovelWebViewChapterMeta.EVENT_TTS_STATE,
                "TTS_CONTROL_LABEL" to quoteForJson(activity.stringResource(TDMR.strings.reader_tts_control)),
                "IMAGE_CLOSE_LABEL" to quoteForJson(activity.stringResource(TDMR.strings.reader_image_close)),
            ),
        )
        evaluateJs(js)
    }

    fun setTtsEnabled(enabled: Boolean) {
        evaluateJs(
            "window.$TSUNDOKU_OBJECT_NAME?.runtime?.readerUi?.setTtsEnabled?.($enabled);",
        )
    }

    fun setBionicReading(enabled: Boolean) {
        evaluateJs(
            "(function(){var r=window.$TSUNDOKU_OBJECT_NAME&&window.$TSUNDOKU_OBJECT_NAME.runtime;" +
                "if(r&&r.readerUi&&r.readerUi.setBionic)r.readerUi.setBionic($enabled);})();",
        )
    }

    fun injectScrollTracking(infiniteScrollEnabled: Boolean) {
        val autoLoadThreshold = preferences.novelAutoLoadNextChapterAt.get()
        val effectiveThreshold = if (autoLoadThreshold > 0) autoLoadThreshold / 100.0 else 0.95
        val js = NovelWebViewJsAssets.loadWith(
            activity,
            "scroll-tracking.js",
            mapOf(
                "TSUNDOKU_OBJECT_NAME" to TSUNDOKU_OBJECT_NAME,
                "CHAPTER_DIVIDER_CLASS" to CHAPTER_DIVIDER_CLASS,
                "CHAPTER_ID_ATTR" to CHAPTER_ID_ATTR,
                "INFINITE_SCROLL_ENABLED" to infiniteScrollEnabled.toString(),
                "LOAD_THRESHOLD" to effectiveThreshold.toString(),
                "DONE_THRESHOLD" to NovelProgress.DONE_THRESHOLD.toString(),
                "PROGRESS_EVENT" to NovelWebViewChapterMeta.EVENT_PROGRESS,
            ),
        )
        evaluateJs(js)
    }

    fun injectScopedChapterAnchors() {
        evaluateJs(NovelWebViewJsAssets.load(activity, "scoped-chapter-anchors.js"))
    }

    companion object {
        const val STYLE_ID_CUSTOM = "tsundoku-custom-style"
        const val ID_NEXT_CHAPTER_BTN_CONTAINER = "next-chapter-btn-container"
        const val READER_CSS_URL = "https://tsundoku.reader/assets/reader.css"

        // Sentinel URL the injected @font-face points at; resolved by interceptFont() in the
        // WebView's shouldInterceptRequest. Never hits the network.
        const val FONT_URL_PREFIX = "https://tsundoku.font/custom"

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
