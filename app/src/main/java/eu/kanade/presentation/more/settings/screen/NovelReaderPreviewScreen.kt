package eu.kanade.presentation.more.settings.screen

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.viewinterop.AndroidView
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.reader.settings.ReaderSettingsDialog
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.setting.NovelReadingLayout
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsViewModel
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.ContentConfig
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.ContentPipeline
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.RenderTarget
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.ThemeUtils
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelContentDirection
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewAssetLoader
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewDocumentBuilder
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewPagedController
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewPreferenceObserver
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewStyler
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.detectNovelContentDirection
import eu.kanade.tachiyomi.util.system.setUserAgent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.i18n.MR
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
        val layoutDirection = LocalLayoutDirection.current
        val scope = rememberCoroutineScope()
        val preferences = remember { Injekt.get<ReaderPreferences>() }
        val userAgent = remember { Injekt.get<NetworkHelper>().defaultUserAgentProvider() }
        val previewHtml = remember(context) {
            context.assets.open(PREVIEW_ASSET).bufferedReader().use { it.readText() }
        }
        val previewWebView = remember { mutableStateOf<WebView?>(null) }
        val showSettings = remember { mutableStateOf(false) }
        val settingsScreenModel = remember {
            ReaderSettingsViewModel(
                readerState = MutableStateFlow(ReaderViewModel.State()),
                onChangeOrientation = {},
                resolveNovelThemeColors = { ThemeUtils.getThemeColors(context, preferences, it) },
            )
        }

        DisposableEffect(Unit) {
            onDispose {
                previewWebView.value?.apply {
                    webViewClient = WebViewClient()
                    destroy()
                }
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
            floatingActionButton = {
                FloatingActionButton(onClick = { showSettings.value = true }) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = stringResource(MR.strings.action_settings),
                    )
                }
            },
        ) { contentPadding ->
            AndroidView(
                factory = {
                    createPreviewWebView(context, preferences, previewHtml, userAgent, scope).also {
                        previewWebView.value = it
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .absolutePadding(
                        left = contentPadding.calculateLeftPadding(layoutDirection),
                        top = contentPadding.calculateTopPadding(),
                        right = contentPadding.calculateRightPadding(layoutDirection),
                    ),
            )
        }

        if (showSettings.value) {
            ReaderSettingsDialog(
                onDismissRequest = { showSettings.value = false },
                onShowMenus = {},
                onHideMenus = {},
                screenModel = settingsScreenModel,
                isNovelMode = true,
            )
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createPreviewWebView(
        context: Context,
        preferences: ReaderPreferences,
        previewHtml: String,
        userAgent: String,
        scope: CoroutineScope,
    ): WebView {
        val assetLoader = NovelWebViewAssetLoader(context.assets)
        val contentPipeline = ContentPipeline(preferences)
        lateinit var styler: NovelWebViewStyler
        lateinit var pagedController: NovelWebViewPagedController
        var loadJob: Job? = null
        val previewDirection = detectNovelContentDirection(previewHtml, "en")

        val webView = WebView(context).apply {
            setUserAgent(userAgent)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

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

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    styler.injectScript { "" }
                    styler.injectReaderUi()
                    pagedController.install(
                        direction = previewDirection,
                        infinite = false,
                        chapterId = -1L,
                    )
                }
            }

            styler = NovelWebViewStyler(
                context = context,
                preferences = preferences,
                webView = this,
                container = this,
                evaluateJs = { script -> evaluateJavascript(script, null) },
            )
            pagedController = NovelWebViewPagedController(
                context = context,
                webView = this,
                preferences = preferences,
                evaluateJs = { script -> evaluateJavascript(script, null) },
            )
        }

        fun reloadPreview() {
            loadJob?.cancel()
            loadJob = scope.launch {
                loadPreview(
                    webView,
                    styler,
                    context,
                    preferences,
                    contentPipeline,
                    previewHtml,
                    previewDirection,
                )
            }
        }

        NovelWebViewPreferenceObserver(
            preferences = preferences,
            scope = scope,
            onStyleChanged = {
                styler.injectStyles()
                styler.setBionicReading(preferences.novelBionicReading.get())
            },
            onScriptChanged = { styler.injectScript(reapplyChangedOnly = true) { "" } },
            onChapterReloadRequested = ::reloadPreview,
            onBlockMediaChanged = { reloadPreview() },
            onTtsSettingsChanged = {},
            onTtsEngineChanged = {},
        ).observe()
        reloadPreview()
        return webView
    }

    private suspend fun loadPreview(
        webView: WebView,
        styler: NovelWebViewStyler,
        context: Context,
        preferences: ReaderPreferences,
        contentPipeline: ContentPipeline,
        previewHtml: String,
        previewDirection: NovelContentDirection,
    ) {
        val processed = withContext(Dispatchers.Default) {
            contentPipeline.process(
                previewHtml,
                ContentConfig.from(
                    preferences = preferences,
                    target = RenderTarget.WEB_VIEW,
                    chapterUrl = PREVIEW_BASE_URL,
                    chapterName = PREVIEW_CHAPTER_TITLE,
                ),
            )
        }
        styler.applyScrollbarSettings()
        val style = styler.buildPayload()
        webView.setBackgroundColor(style.backgroundColor)
        webView.settings.apply {
            blockNetworkImage = preferences.novelBlockMedia.get()
            loadsImagesAutomatically = !preferences.novelBlockMedia.get()
        }
        val input = NovelWebViewDocumentBuilder.DocumentInput(
            processed = processed,
            chapter = null,
            style = style,
            themeTokens = ThemeUtils.getThemeTokens(context, preferences, preferences.novelTheme.get()),
            tsundokuScript = "",
            pluginJavaScript = styler.initialPluginJavaScript(),
            infiniteScrollEnabled = false,
            pagedLayoutEnabled = preferences.novelReadingLayout.get() == NovelReadingLayout.PAGED,
            chapterDirection = previewDirection,
            chapterLanguage = "en",
            blockMedia = preferences.novelBlockMedia.get(),
        )
        val document = withContext(Dispatchers.Default) { NovelWebViewDocumentBuilder.assemble(input) }
        webView.loadDataWithBaseURL(PREVIEW_BASE_URL, document, "text/html", "UTF-8", null)
    }

    private companion object {
        const val PREVIEW_ASSET = "novel-reader/dummy.html"
        const val PREVIEW_BASE_URL = "https://tsundoku.reader/"
        const val PREVIEW_CHAPTER_TITLE = "Lorem ipsum dolor sit amet consectetuer adipiscing elit"
    }
}
