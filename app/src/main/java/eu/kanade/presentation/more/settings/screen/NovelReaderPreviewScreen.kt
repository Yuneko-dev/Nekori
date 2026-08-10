package eu.kanade.presentation.more.settings.screen

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.ProcessedContent
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.ThemeUtils
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewAssetLoader
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewDocumentBuilder
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewStyler
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class NovelReaderPreviewScreen : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val preferences = remember { Injekt.get<ReaderPreferences>() }
        val previewHtml = remember(context) {
            context.assets.open(PREVIEW_ASSET).bufferedReader().use { it.readText() }
        }
        val previewWebView = remember { mutableStateOf<WebView?>(null) }

        DisposableEffect(Unit) {
            onDispose {
                previewWebView.value?.destroy()
                previewWebView.value = null
            }
        }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(TDMR.strings.pref_novel_reader_preview),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            AndroidView(
                factory = {
                    createPreviewWebView(context, preferences, previewHtml).also {
                        previewWebView.value = it
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            )
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createPreviewWebView(
        context: Context,
        preferences: ReaderPreferences,
        previewHtml: String,
    ): WebView {
        val assetLoader = NovelWebViewAssetLoader(context.assets)
        lateinit var styler: NovelWebViewStyler

        return WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): WebResourceResponse? {
                    val url = request?.url?.toString() ?: return null
                    return assetLoader.intercept(url)
                        ?: styler.interceptFont(url)
                        ?: super.shouldInterceptRequest(view, request)
                }
            }

            styler = NovelWebViewStyler(
                context = context,
                preferences = preferences,
                webView = this,
                container = this,
                evaluateJs = { script -> evaluateJavascript(script, null) },
            )
            styler.applyScrollbarSettings()

            val style = styler.buildPayload()
            setBackgroundColor(style.backgroundColor)
            val document = NovelWebViewDocumentBuilder.assemble(
                NovelWebViewDocumentBuilder.DocumentInput(
                    processed = ProcessedContent(previewHtml, isPlainText = false, chapterUrl = null),
                    chapter = null,
                    style = style,
                    themeTokens = ThemeUtils.getThemeTokens(context, preferences, preferences.novelTheme.get()),
                    tsundokuScript = "",
                    pluginJavaScript = "",
                    infiniteScrollEnabled = false,
                    blockMedia = preferences.novelBlockMedia.get(),
                ),
            )
            loadDataWithBaseURL(PREVIEW_BASE_URL, document, "text/html", "UTF-8", null)
        }
    }

    private companion object {
        const val PREVIEW_ASSET = "novel-reader/dummy.html"
        const val PREVIEW_BASE_URL = "https://tsundoku.reader/"
    }
}
