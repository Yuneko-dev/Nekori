@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.net.Uri
import android.view.ActionMode
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.ConsoleMessage
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.Lifecycle
import com.hippo.unifile.UniFile
import eu.kanade.domain.manga.model.WordDensity
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.translation.ChapterSummaryService
import eu.kanade.tachiyomi.jsplugin.source.JsSource
import eu.kanade.tachiyomi.jsplugin.source.applyJsImageRequestInit
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.ReaderNavigationRequest
import eu.kanade.tachiyomi.ui.reader.ReaderNavigationSource
import eu.kanade.tachiyomi.ui.reader.loader.DownloadPageLoader
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.NovelPageEffect
import eu.kanade.tachiyomi.ui.reader.setting.NovelPagePosition
import eu.kanade.tachiyomi.ui.reader.setting.NovelReadingLayout
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.NovelWebViewNetworkMode
import eu.kanade.tachiyomi.ui.reader.setting.ReplacementTarget
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderProgressIndicator
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.text.NovelConfig
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.ChapterQueue
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.ContentConfig
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.ContentPipeline
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.ErrorFormatter
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.HtmlUtils
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.NovelPageLoader
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.NovelProgress
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.ProcessedContent
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.RenderTarget
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.ThemeUtils
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.TtsController
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.TtsHandoffState
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.TtsTextUtils
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.handleNovelFlingGesture
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.localized
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.unescapeJsResult
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.curl.NovelPageCurlController
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.curl.NovelPageCurlReadingDirection
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.curl.NovelPageCurlTurnDirection
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.curl.NovelPageCurlView
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.curl.finishAfterVisualState
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.proxy.NovelReaderProxyServer
import eu.kanade.tachiyomi.util.system.setUserAgent
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import logcat.LogPriority
import logcat.logcat
import okhttp3.Request
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.translation.service.TranslationPreferences
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import uy.kohesive.injekt.injectLazy
import kotlin.coroutines.resume

class NovelWebViewViewer(val activity: ReaderActivity) : Viewer {

    val pagePosition: StateFlow<NovelPagePosition?>
        get() = pagedController.position

    enum class TtsPlaybackState(val wireValue: String) {
        STOPPED("stopped"),
        PLAYING("playing"),
        PAUSED("paused"),
    }

    private companion object {
        const val REMEMBER_MENU_ITEM_ID = 0xBEEF // arbitrary unique ID
        const val ID_EDIT_MODE_STYLE = "edit-mode-style"
        const val SEEK_ECHO_SUPPRESS_MS = 350L
        const val AUTO_SCROLL_START_VERIFY_MS = 400L
        val SUMMARY_ACTIONS = setOf("cancel", "close", "regenerate")
        val IMAGE_URL_REGEX = Regex("\\.(?:avif|gif|jpe?g|png|svg|webp)$", RegexOption.IGNORE_CASE)
        const val AUTO_SCROLL_MAX_START_ATTEMPTS = 3
    }

    private val container = FrameLayout(activity)
    private val _ttsPlaybackState = MutableStateFlow(TtsPlaybackState.STOPPED)
    val ttsPlaybackState: StateFlow<TtsPlaybackState> = _ttsPlaybackState.asStateFlow()
    private lateinit var webView: WebView
    val devToolsTarget: WebView?
        get() = if (::webView.isInitialized && !isDestroyed) webView else null
    private lateinit var readerBridge: NovelReaderBridge
    private var readerConfigJson: JsonObject? = null
    private var pendingErrorFeedback: JsonObject? = null
    private var expectingBridgeDocumentStart = false
    private var loadingIndicator: ReaderProgressIndicator? = null
    private val preferences: ReaderPreferences by injectLazy()
    private val translationPreferences: TranslationPreferences by injectLazy()
    private val isTtsEnabled: Boolean
        get() = preferences.novelTtsEnabled.get()
    private val libraryPreferences: tachiyomi.domain.library.service.LibraryPreferences by injectLazy()
    private val networkHelper: NetworkHelper by injectLazy()
    private val getIncognitoState: eu.kanade.domain.source.interactor.GetIncognitoState by injectLazy()
    private val chapterSummaryService: ChapterSummaryService by injectLazy()
    private val contentPipeline = ContentPipeline(preferences)
    private val assetLoader = NovelWebViewAssetLoader(activity.assets)
    private var proxyServer: NovelReaderProxyServer? = null
    private val pluginAllowsInfiniteScroll by lazy {
        (activity.viewModel.getSource() as? JsSource)?.allowsInfiniteScroll ?: true
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Jobs launched in `scope`, so destroy()'s scope.cancel() already stops any request in flight.
    private val summaryController by lazy {
        NovelWebViewSummaryController(
            scope = scope,
            service = chapterSummaryService,
            target = {
                activity.viewModel.manga?.let {
                    eu.kanade.tachiyomi.ui.reader.setting.ReplacementTarget(it.source, it.url)
                }
            },
            labels = NovelWebViewSummaryController.Labels(
                title = activity.stringResource(TDMR.strings.chapter_summary_title),
                loading = activity.stringResource(TDMR.strings.chapter_summary_loading),
                regenerate = activity.stringResource(TDMR.strings.action_regenerate),
                close = activity.stringResource(MR.strings.action_close),
                cancel = activity.stringResource(MR.strings.action_cancel),
                cancelled = activity.stringResource(TDMR.strings.chapter_summary_cancelled),
                contentUnavailable = activity.stringResource(TDMR.strings.chapter_summary_no_content),
                invalidConfig = activity.stringResource(TDMR.strings.ai_guidelines_invalid_config),
            ),
            executeCommand = ::executeReaderCommand,
            chapterHtml = ::loadChapterHtml,
            onUnconfigured = {
                activity.toast(activity.stringResource(TDMR.strings.chapter_summary_unconfigured))
            },
            onUnavailable = {
                activity.toast(activity.stringResource(TDMR.strings.chapter_summary_unavailable))
            },
        )
    }

    private var loadJob: Job? = null
    private var contentJob: Job? = null
    private var appendJob: Job? = null
    private var chapterRecoveryJob: Job? = null
    private var chapterRecoveryId: Long? = null
    private var chapterRecoveryRequest: ReaderNavigationRequest? = null
    private var pendingChapterRecoveryId: Long? = null
    private var attachListener: View.OnAttachStateChangeListener? = null
    private var currentPage: ReaderPage? = null
    private var currentChapters: ViewerChapters? = null
    private var currentDocumentIsVideo = false
    internal var currentDocumentNoPrefetch = false
        private set
    private var currentDocumentDirection = NovelContentDirection.LTR
    private var currentLocalVideo: Pair<Long, UniFile>? = null

    @Volatile
    private var protectedMediaPlaybackArmed = false

    @Volatile
    private var protectedMediaPlaybackOrigin: ProtectedMediaOrigin? = null

    // Prevent reopening the player until the chapter changes or the user taps Play.
    private var launchedVideoChapterId: Long? = null

    // Claimed page-side on every pointerdown and reset to a fail-closed default on every
    // ACTION_DOWN, so a touch the document never classified cannot reach reader actions. Written
    // from the JavaBridge thread, read from the UI thread.
    @Volatile
    private var gestureTarget = ReaderGestureTarget.BLOCKED

    // Documents without the reader gesture module (loading skeleton, error page) keep the pre-classifier
    // behaviour: every tap is reader surface, so those pages stay tappable.
    @Volatile
    private var pageOwnsGestures = false
    private var pendingTtsParagraphIndex: Int? = null
    private val imageCache = NovelWebViewImageCache(activity.cacheDir, scope)

    private var lastSavedProgress = 0f

    private val chapterQueue = ChapterQueue<ReaderChapter> { it.chapter.id }

    // Suppresses JS scroll callbacks while a full-document load + scroll restore is in flight, so a
    // stale event or the programmatic restore scroll can't persist against the new chapter's page.
    private var isRestoringScroll = false
    private var scrollRestoreToken = 0

    // Blocks flushing the backward-entry 1f baseline until a real scroll sample replaces it.
    private var awaitingFirstScrollSample = false

    // Timestamp of the last slider seek; scroll->slider echoes are ignored briefly after so a stale
    // async onScrollUpdate can't overwrite the value the user is dragging to.
    private var lastUserSeekAt = 0L

    // Latched once the novel has no further chapter to append.
    private var reachedNovelEnd = false

    private var nextRequiresDocumentNavigation = false

    // A failed append waits for its Retry button instead of repeatedly fetching while at the bottom.
    private var nextLoadRequiresManualRetry = false

    // Lightweight property accessors so existing call sites keep working.
    // Mutations should go through chapterQueue's methods (append / prepend /
    // removeFirst / clear) - they keep the cursor and id-set in sync.
    private val loadedChapters: List<ReaderChapter> get() = chapterQueue.all
    private val loadedChapterIds: Set<Long> get() = chapterQueue.loadedIds
    private var currentChapterIndex: Int
        get() = chapterQueue.currentIndex
        set(value) {
            chapterQueue.currentIndex = value
        }
    private var isLoadingNext: Boolean
        get() = chapterQueue.isLoadingNext
        set(value) {
            chapterQueue.isLoadingNext = value
        }

    /**
     * Whether [destroy] has run. Readable because the viewer outlives the activity that built it:
     * it is held by [eu.kanade.tachiyomi.ui.reader.ReaderViewModel], which survives configuration
     * changes, so the next activity has to be able to tell a live viewer from a spent one.
     */
    var isDestroyed = false
        private set
    private var isEditingMode = false
    private var activeFindQuery = ""

    private var isAutoScrolling = false
    private var autoScrollStartAttempt = 0

    // Never reset (unlike autoScrollStartAttempt, which restarts at 0 each session), so a verify
    // callback from a stopped/superseded session can't collide with a same-numbered attempt from a
    // fresh one started within the same AUTO_SCROLL_START_VERIFY_MS window.
    private var autoScrollSession = 0

    // The error page is a fresh document that drops the autoscroll rAF loop; re-arm it once its
    // onPageFinished lands, since that load never enters DocState.LOADING_REAL so the re-arm path
    // in the real-chapter gate is skipped.
    private var rearmAutoScrollOnErrorPage = false

    // Tracked so a JS dialog still on screen at teardown is dismissed instead of leaking the window.
    private var activeJsDialog: AlertDialog? = null

    private var fullscreenVideoContainer: FrameLayout? = null
    private var fullscreenVideoCallback: WebChromeClient.CustomViewCallback? = null
    private var fullscreenVideoBackCallback: OnBackPressedCallback? = null
    private var orientationBeforeFullscreenVideo: Int? = null
    internal val isVideoFullscreen: Boolean get() = fullscreenVideoContainer != null

    // Reader bars overlay the WebView and must never change its layout viewport.
    private var chromeMenuVisible = activity.viewModel.state.value.menuVisible

    private val config = NovelConfig(scope)
    private val navigator get() = config.navigator

    private val handoffState = TtsHandoffState<Pair<ReaderChapter, ReaderPage>>(scope)
    private var ttsChapterJob: Job? = null

    // Survives ttsController.stop() - set when TTS triggers a non-inf-scroll chapter load.
    private var pendingTtsAutoStartOnLoad = false

    // Single source of truth for the loaded document's lifecycle, replacing three hand-synced
    // booleans (isLoadingRealChapter/webChapterContentReady/webChapterIsError) that had to be flipped
    // together on every load/error/finish. Only these four combinations were ever legal; the enum
    // makes the illegal ones unrepresentable.
    //   LOADING       loading-indicator page up, or a base load queued but not yet issued
    //   LOADING_REAL  real-chapter loadDataWithBaseURL issued, awaiting its onPageFinished
    //   READY         real content committed; TTS may read the body and appends may splice onto it
    //   ERROR         error placeholder committed; body counts as ready but has no DOM to append onto
    private enum class DocState { LOADING, LOADING_REAL, READY, ERROR }
    private var docState = DocState.LOADING

    // Real content or an error placeholder is committed. Error counts as ready so a failed load
    // can't block infinite-scroll appends forever; webChapterIsError then suppresses the append.
    private val webChapterContentReady get() = docState == DocState.READY || docState == DocState.ERROR

    // Current DOM is an error placeholder, so there is no valid base to append the next chapter onto.
    private val webChapterIsError get() = docState == DocState.ERROR

    internal fun isInfiniteScrollEnabled(): Boolean =
        preferences.novelInfiniteScroll.get() && pluginAllowsInfiniteScroll &&
            !currentDocumentNoPrefetch && !currentDocumentIsVideo

    private fun isVideoChapter(): Boolean = currentDocumentIsVideo

    private fun isPagedLayoutEnabled(): Boolean =
        preferences.novelReadingLayout.get().isPaged && !currentDocumentIsVideo &&
            ((activity.viewModel.getSource() as? JsSource)?.allowsPagedReading ?: true)

    private val ttsController: TtsController

    private lateinit var styler: NovelWebViewStyler
    private lateinit var pagedController: NovelWebViewPagedController
    private lateinit var curlController: NovelPageCurlController
    private lateinit var curlView: NovelPageCurlView
    private var curlPreviewMoved = false

    private val inlineFeedback by lazy {
        NovelWebViewInlineFeedback(
            context = activity,
            scope = scope,
            executeCommand = ::executeReaderCommand,
            currentChapterId = { getCurrentReaderChapter()?.chapter?.id },
        )
    }

    var pendingSelectedText: String? = null
    var pendingParagraphIndex: Int? = null

    private val gestureDetector = GestureDetector(
        activity,
        object : GestureDetector.SimpleOnGestureListener() {

            // The touch listener returns false unless curl claims the gesture, so accepting DOWN
            // here only keeps GestureDetector's fling state alive; it does not consume WebView taps.
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                if (!gestureTarget.allowsChapterSwipe()) return true
                if (isEditingMode) return false
                val swipeEnabled = if (pagedController.enabled) {
                    preferences.novelPagedSwipeNavigation.get()
                } else {
                    preferences.novelSwipeNavigation.get()
                }
                if (!swipeEnabled) return false
                return handleNovelFlingGesture(
                    e1,
                    e2,
                    velocityX,
                    velocityY,
                    onPrevious = {
                        if (pagedController.enabled) {
                            pageScrollBy(if (isRtlPageFlow()) 1 else -1)
                        } else {
                            loadedChapters.getOrNull(currentChapterIndex - 1)?.chapter?.id
                                ?.let { chapterId -> scope.launch { scrollToLoadedChapter(chapterId) } }
                        }
                    },
                    onNext = {
                        if (pagedController.enabled) {
                            pageScrollBy(if (isRtlPageFlow()) -1 else 1)
                        } else {
                            activity.loadNextChapter()
                        }
                    },
                )
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isEditingMode) return false
                if (activity.isFindInPageOpen()) return false
                if (e.eventTime - e.downTime >= android.view.ViewConfiguration.getLongPressTimeout()) return true
                when (gestureTarget.tapAction(isVideoChapter = isVideoChapter())) {
                    ReaderTapAction.NONE -> return true
                    ReaderTapAction.TOGGLE_MENU -> {
                        activity.toggleMenu()
                        return true
                    }
                    ReaderTapAction.TAP_ZONES -> Unit
                }
                if (container.width <= 0 || container.height <= 0) return true

                val pos = android.graphics.PointF(
                    e.x / container.width.toFloat(),
                    e.y / container.height.toFloat(),
                )

                if (preferences.navigationModeNovel.get() in ReaderPreferences.TAPZONE_ZONE_ONLY_MODES) {
                    val menuZone = navigator.getRegions().firstOrNull()?.rectF
                    if (menuZone != null && menuZone.contains(pos.x, pos.y)) {
                        activity.toggleMenu()
                    }
                    return true
                }

                when (navigator.getAction(pos)) {
                    eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion.MENU -> {
                        activity.toggleMenu()
                    }
                    eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion.NEXT -> pageScrollBy(1)
                    eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion.PREV -> pageScrollBy(-1)
                    eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion.RIGHT -> {
                        pageScrollBy(
                            if (pagedController.enabled &&
                                isRtlPageFlow()
                            ) {
                                -1
                            } else {
                                1
                            },
                        )
                    }
                    eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion.LEFT -> {
                        pageScrollBy(
                            if (pagedController.enabled &&
                                isRtlPageFlow()
                            ) {
                                1
                            } else {
                                -1
                            },
                        )
                    }
                }

                return true
            }
        },
    ).apply {
        // Disable long press handling so WebView can handle text selection
        setIsLongpressEnabled(false)
    }

    init {
        proxyServer = if (preferences.novelWebViewLocalProxyEnabled.get()) {
            runCatching {
                NovelReaderProxyServer(networkHelper.client).also(NovelReaderProxyServer::start)
            }.onFailure { error ->
                logcat(LogPriority.ERROR) {
                    "NovelWebViewViewer: Failed to start local reader proxy: ${error.stackTraceToString()}"
                }
            }.getOrNull()
        } else {
            null
        }
        ttsController = TtsController(
            context = activity,
            preferences = preferences,
            networkClient = networkHelper.client,
            scope = scope,
            callbacks = object : TtsController.Callbacks {
                override fun onInitialized(pendingRequest: TtsController.StartRequest?) {
                    if (!isTtsEnabled) {
                        ttsController.pendingStartRequest = null
                        pendingTtsParagraphIndex = null
                        return
                    }
                    when (pendingRequest) {
                        TtsController.StartRequest.NORMAL -> startTts()
                        TtsController.StartRequest.VIEWPORT -> {
                            pendingTtsParagraphIndex?.let {
                                pendingTtsParagraphIndex = null
                                startTtsAtParagraph(it)
                            } ?: startTtsFromViewport()
                        }
                        null -> {}
                    }
                }

                override fun onChunkStarted(chunkIndex: Int, chunk: String, startOffset: Int, paragraphIndex: Int) {
                    if (preferences.novelTtsEnableHighlight.get()) applyTtsHighlight(chunkIndex, paragraphIndex)
                    saveTtsProgressForChunk(chunkIndex)
                    preFetchNextChapterForTts()
                }

                override fun onClearHighlights() {
                    clearWebViewTtsHighlight()
                    dispatchTtsState()
                }

                override fun onLastChunkDone() {
                    if (!isTtsEnabled) return
                    loadNextChapterForTts()
                }

                override fun onError(error: Throwable) {
                    activity.toast(
                        activity.stringResource(
                            TDMR.strings.novel_tts_playback_error,
                            error.message ?: error::class.java.simpleName,
                        ),
                    )
                    dispatchTtsState()
                }

                override fun runOnUiThread(action: () -> Unit) {
                    activity.runOnUiThread(action)
                }
            },
        )
        initWebView()
        observePreferences()

        // NovelConfig swallows the initial navigationMode emit, so this
        // listener now fires only when the user actually changes the nav-mode
        // preference. Always show the preview in that case - opening the
        // reader plainly should NOT re-pop the overlay.
        config.navigationModeChangedListener = {
            activity.binding.navigationOverlay.setNavigation(config.navigator, true)
        }
        // Initial publish so overlay reflects the configured navigator from the
        // start instead of staying on whatever the previous viewer set, but
        // without the show-on-start preview.
        activity.binding.navigationOverlay.setNavigation(config.navigator, false)
        // Opening preference and first-user onboarding share one preview per activity session.
        if ((config.forceNavigationOverlay || preferences.showNavigationOverlay.get()) &&
            preferences.navigationModeNovel.get() != ReaderPreferences.TAPZONE_DISABLED_INDEX &&
            !activity.tapZonesShownInSession
        ) {
            activity.tapZonesShownInSession = true
            activity.binding.navigationOverlay.setNavigation(config.navigator, true)
        }
    }

    private fun applyTtsHighlight(chunkIndex: Int, paragraphIndex: Int) {
        if (chunkIndex < 0 || chunkIndex >= ttsController.ttsChunks.size) return

        val highlightColor = ThemeUtils.colorToHex(preferences.novelTtsHighlightColor.get())
        val highlightTextColor = ThemeUtils.colorToHex(preferences.novelTtsHighlightTextColor.get())
        val keepInView = preferences.novelTtsKeepHighlightInView.get()
        val chapterId = ttsController.ttsPlaybackChapterId
        executeReaderCommand(
            NovelWebViewTtsDomScripts.highlight(
                chapterId = chapterId,
                paragraphIndex = paragraphIndex,
                backgroundColor = highlightColor,
                textColor = highlightTextColor,
                style = preferences.novelTtsHighlightStyle.get(),
                keepInView = keepInView,
            ),
        )
    }

    private fun clearWebViewTtsHighlight() {
        executeReaderCommand(NovelWebViewTtsDomScripts.CLEAR_HIGHLIGHT)
    }

    private fun loadNextChapterForTts() {
        if (!ttsController.isTtsAutoPlay || ttsChapterJob?.isActive == true) return
        logcat(LogPriority.DEBUG) {
            "TTS (WebView): Auto-loading next chapter ts=${System.currentTimeMillis()} ttsPlaybackChapterIndex=${ttsController.ttsPlaybackChapterIndex} ttsPlaybackChapterId=${ttsController.ttsPlaybackChapterId}"
        }

        ttsChapterJob = scope.launch {
            if (isInfiniteScrollEnabled()) {
                // A foreground append may already be translating the next chapter.
                appendJob?.join()
                val nextLoaded = loadedChapters.getOrNull(ttsController.ttsPlaybackChapterIndex + 1) != null
                // Source fetches retain their timeout; translation uses its own request timeouts.
                if (nextLoaded || appendNextChapterIfAvailable()) {
                    advanceTtsToNextLoadedChapter()
                } else if (nextRequiresDocumentNavigation) {
                    navigateNextChapterForTts()
                } else {
                    stopTts()
                }
            } else {
                navigateNextChapterForTts()
            }
        }
    }

    private fun navigateNextChapterForTts() {
        if (currentChapters?.nextChapter == null) {
            stopTts()
            return
        }
        pendingTtsAutoStartOnLoad = true
        activity.loadNextChapterForTtsHandoff()
    }

    private suspend fun advanceTtsToNextLoadedChapter() {
        val currentIdx = ttsController.ttsPlaybackChapterIndex
        val nextIdx = currentIdx + 1
        val nextChapter = loadedChapters.getOrNull(nextIdx) ?: return
        val nextChapterId = nextChapter.chapter.id ?: return

        if (!scrollToLoadedChapter(nextChapterId)) {
            stopTts()
            return
        }
        nextChapter.pages?.firstOrNull()?.let { page ->
            currentPage = page
            activity.viewModel.setNovelVisibleChapter(nextChapter.chapter)
            activity.onPageSelected(page)
            activity.onNovelProgressChanged(0f)
            updateChapterMetaJs()
        }
        clearWebViewTtsHighlight()
        startTts()
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun initWebView() {
        // This platform switch is process-wide; destroy() restores the build's normal debug state.
        WebView.setWebContentsDebuggingEnabled(preferences.novelWebViewRemoteDebugging.get())
        attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                // Remove blocksDescendants from reader_activity.xml's viewer_container parent
                // so the WebView can actually receive text input focus.
                (container.parent as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            }
            override fun onViewDetachedFromWindow(v: View) {}
        }.also(container::addOnAttachStateChangeListener)

        webView = object : WebView(activity) {
            override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? {
                if (!preferences.novelTextSelectable.get() || callback == null) {
                    return super.startActionMode(callback, type)
                }
                // Preserve Callback2 so the floating toolbar anchors correctly to the selection
                val wrapped = if (callback is ActionMode.Callback2) {
                    object : ActionMode.Callback2() {
                        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                            val result = callback.onCreateActionMode(mode, menu)
                            menu.add(
                                Menu.NONE,
                                REMEMBER_MENU_ITEM_ID,
                                Menu.NONE,
                                activity.stringResource(TDMR.strings.action_remember),
                            )
                                .setIcon(android.R.drawable.ic_menu_save)
                                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                            return result
                        }
                        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean =
                            callback.onPrepareActionMode(mode, menu)
                        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                            if (item.itemId == REMEMBER_MENU_ITEM_ID) {
                                onRememberSelectedText(mode) // pass mode in
                                return true
                            }
                            return callback.onActionItemClicked(mode, item)
                        }
                        override fun onDestroyActionMode(mode: ActionMode) =
                            callback.onDestroyActionMode(mode)

                        // Forward the content rect so the toolbar floats near the selection
                        override fun onGetContentRect(mode: ActionMode, view: View, outRect: android.graphics.Rect) =
                            callback.onGetContentRect(mode, view, outRect)
                    }
                } else {
                    object : ActionMode.Callback {
                        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                            val result = callback.onCreateActionMode(mode, menu)
                            menu.add(
                                Menu.NONE,
                                REMEMBER_MENU_ITEM_ID,
                                Menu.NONE,
                                activity.stringResource(TDMR.strings.action_remember),
                            )
                                .setIcon(android.R.drawable.ic_menu_save)
                                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                            return result
                        }
                        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean =
                            callback.onPrepareActionMode(mode, menu)
                        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                            if (item.itemId == REMEMBER_MENU_ITEM_ID) {
                                onRememberSelectedText()
                                mode.finish()
                                return true
                            }
                            return callback.onActionItemClicked(mode, item)
                        }
                        override fun onDestroyActionMode(mode: ActionMode) =
                            callback.onDestroyActionMode(mode)
                    }
                }
                return super.startActionMode(wrapped, type)
            }
        }.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setUserAgent(networkHelper.defaultUserAgentProvider())
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                // The document viewport disables page zoom during normal reading. Keep native
                // zoom support available so the in-page image viewer can enable pinch zoom only
                // while its modal is open.
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                mediaPlaybackRequiresUserGesture = false
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                val shouldBlock = preferences.novelBlockMedia.get()
                blockNetworkImage = shouldBlock
                loadsImagesAutomatically = !shouldBlock
            }

            webViewClient = object : WebViewClient() {
                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                    // Returning false hands the dead renderer back to Android, which kills the whole
                    // app - the same reason HeadlessChapterWebView claims this callback. The renderer
                    // is unrecoverable either way, so retire the viewer and say so; updateViewer()
                    // builds a fresh one once its destroyed state is seen.
                    logcat(LogPriority.ERROR) { "Reader WebView renderer gone (didCrash=${detail?.didCrash()})" }
                    destroy()
                    activity.toast(activity.stringResource(TDMR.strings.novel_reader_renderer_gone))
                    return true
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val targetUrl = NovelWebViewChapterMeta.resolveEpubChapterUrl(
                        currentPage?.chapter?.chapter?.url,
                        request?.url?.toString().orEmpty(),
                    )
                    if (targetUrl != null) {
                        navigateToEpubChapter(targetUrl)
                        return true
                    }
                    if (request?.isForMainFrame == true) {
                        // A source-origin page is trusted only for the document we configured.
                        // Tear down its native channel before allowing any external top-level load.
                        readerBridge.endDocument()
                        expectingBridgeDocumentStart = false
                        protectedMediaPlaybackArmed = false
                        protectedMediaPlaybackOrigin = null
                        pageOwnsGestures = false
                    }
                    return false
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): WebResourceResponse? {
                    val url = request?.url?.toString() ?: return null
                    assetLoader.intercept(url)?.let { return it }
                    styler.interceptFont(url)?.let { return it }
                    styler.interceptBackground(url)?.let { return it }
                    val fallbackChapterId =
                        currentPage?.chapter?.chapter?.id ?: currentChapters?.currChapter?.chapter?.id
                    val fallbackLoader = activity.viewModel.state.value.viewerChapters?.currChapter?.pageLoader
                    imageCache.intercept(url, fallbackChapterId, fallbackLoader)?.let { return it }
                    if (preferences.novelWebViewNetworkMode.get() == NovelWebViewNetworkMode.NETWORK_HELPER) {
                        interceptNetworkRequest(request)?.let { return it }
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    if (::styler.isInitialized) styler.resetScriptState()
                    if (!expectingBridgeDocumentStart) readerBridge.endDocument()
                    expectingBridgeDocumentStart = false
                    protectedMediaPlaybackArmed = false
                    // The new document has not installed the reader gesture module yet.
                    pageOwnsGestures = false
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)

                    // The error page is a fresh document; re-arm autoscroll before the real-chapter
                    // gate below, which the error load (docState=ERROR, not LOADING_REAL) would skip.
                    if (rearmAutoScrollOnErrorPage) {
                        rearmAutoScrollOnErrorPage = false
                        if (isAutoScrolling) startAutoScroll()
                    }

                    // The loading skeleton uses loadDataWithBaseURL too. Its callback can arrive
                    // after loadHtmlContent has already switched the state to LOADING_REAL, so the
                    // state alone cannot identify the real chapter document.
                    if (docState != DocState.LOADING_REAL) return
                    // Runtime readiness is reported through the document-scoped typed bridge.
                }
            }

            val devToolsEnabled = preferences.novelWebViewDevTools.get()
            webChromeClient = object : WebChromeClient() {
                // The arm deliberately survives this callback. One DASH source asks many times: dash.js
                // probes MediaCapabilities.decodingInfo() once per representation before it ever calls
                // requestMediaKeySystemAccess, and every probe on encrypted content raises its own
                // permission request. Consuming the arm on the first one let a capability probe spend it,
                // after which every representation was reported unsupported and the real key-system
                // request was denied. The window is still bounded - onPageStarted and destroy() clear it,
                // and the origin and resource checks below are unchanged.
                override fun onPermissionRequest(request: PermissionRequest) {
                    val granted = canGrantProtectedMediaPlayback(
                        armed = protectedMediaPlaybackArmed,
                        requestOrigin = protectedMediaOrigin(
                            request.origin.scheme,
                            request.origin.host,
                            request.origin.port,
                        ),
                        documentOrigin = protectedMediaPlaybackOrigin,
                        resources = request.resources.toList(),
                        protectedMediaResource = PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID,
                    )
                    if (granted) {
                        request.grant(arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID))
                    } else {
                        request.deny()
                    }
                }

                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    if (view == null || callback == null) return
                    showFullscreenVideo(view, callback)
                }

                override fun onHideCustomView() {
                    hideFullscreenVideo()
                }

                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    if (!devToolsEnabled) return super.onConsoleMessage(consoleMessage)
                    val level = consoleMessage.messageLevel()
                    val shouldToast = level == ConsoleMessage.MessageLevel.LOG ||
                        level == ConsoleMessage.MessageLevel.WARNING ||
                        level == ConsoleMessage.MessageLevel.ERROR
                    if (shouldToast && preferences.novelConsoleErrorToast.get()) {
                        activity.toast(consoleMessage.message().take(120))
                    }
                    return true
                }

                override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult): Boolean {
                    if (!devToolsEnabled) return super.onJsAlert(view, url, message, result)
                    if (activity.isFinishing || activity.isDestroyed) {
                        result.cancel()
                        return true
                    }
                    activeJsDialog = AlertDialog.Builder(activity)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                        .setOnCancelListener { result.cancel() }
                        .setOnDismissListener { activeJsDialog = null }
                        .show()
                    return true
                }

                override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult): Boolean {
                    if (!devToolsEnabled) return super.onJsConfirm(view, url, message, result)
                    if (activity.isFinishing || activity.isDestroyed) {
                        result.cancel()
                        return true
                    }
                    activeJsDialog = AlertDialog.Builder(activity)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                        .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                        .setOnCancelListener { result.cancel() }
                        .setOnDismissListener { activeJsDialog = null }
                        .show()
                    return true
                }

                override fun onJsPrompt(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    defaultValue: String?,
                    result: JsPromptResult,
                ): Boolean {
                    if (!devToolsEnabled) return super.onJsPrompt(view, url, message, defaultValue, result)
                    if (activity.isFinishing || activity.isDestroyed) {
                        result.cancel()
                        return true
                    }
                    val input = EditText(activity).apply { setText(defaultValue.orEmpty()) }
                    activeJsDialog = AlertDialog.Builder(activity)
                        .setMessage(message)
                        .setView(input)
                        .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm(input.text.toString()) }
                        .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                        .setOnCancelListener { result.cancel() }
                        .setOnDismissListener { activeJsDialog = null }
                        .show()
                    return true
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?,
                ): Boolean {
                    if (!devToolsEnabled) {
                        return super.onShowFileChooser(webView, filePathCallback, fileChooserParams)
                    }
                    if (filePathCallback == null || fileChooserParams == null) return false
                    return activity.launchWebViewFileChooser(filePathCallback, fileChooserParams)
                }
            }

            isLongClickable = true

            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    if (activity.isFindInPageOpen()) activity.dismissFindInPageIme()
                    // Runs before the WebView dispatches the event, so the pointerdown claim that
                    // follows always overwrites this default rather than being overwritten by it.
                    gestureTarget = when {
                        pageOwnsGestures -> ReaderGestureTarget.BLOCKED
                        else -> ReaderGestureTarget.SURFACE
                    }
                }
                val pageEffect = pagedController.effectiveEffect()
                val pageTurnEnabled = pagedController.enabled &&
                    pageEffect != NovelPageEffect.NONE &&
                    preferences.novelPagedSwipeNavigation.get() &&
                    !isEditingMode && !activity.isFindInPageOpen()
                val pageTurnConsumed = curlController.onTouchEvent(
                    event = event,
                    // The WebView classifies the target after this listener receives DOWN. Arm the
                    // detector on DOWN, then require the document's SURFACE claim before MOVE can turn.
                    canStart = pageTurnEnabled &&
                        (event.actionMasked == MotionEvent.ACTION_DOWN || gestureTarget.allowsChapterSwipe()),
                    readingDirection = curlReadingDirection(),
                    doubleSpread = pagedController.isDoubleSpread(),
                    effect = pageEffect,
                )
                if (!pageTurnConsumed) gestureDetector.onTouchEvent(event)
                pageTurnConsumed
            }
        }

        readerBridge = NovelReaderBridge(
            webView = webView,
            onReady = ::handleReaderReady,
            onEvent = ::handleReaderEvent,
            onRequest = ::handleReaderRequest,
            onError = { inlineFeedback.showInlineError(it) },
        )

        styler = NovelWebViewStyler(
            context = activity,
            preferences = preferences,
            webView = webView,
            container = container,
            executeCommand = ::executeReaderCommand,
        )
        pagedController = NovelWebViewPagedController(
            context = activity,
            webView = webView,
            preferences = preferences,
            executeCommand = ::executeReaderCommand,
        )
        styler.applyScrollbarSettings()

        val (backgroundColor, _) = getThemeColors(preferences.novelTheme.get())
        webView.setBackgroundColor(backgroundColor)
        container.setBackgroundColor(backgroundColor)

        container.addView(webView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        curlView = NovelPageCurlView(activity)
        container.addView(curlView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        curlController = NovelPageCurlController(
            curlView = curlView,
            sourceView = webView,
            requestTarget = ::requestCurlTarget,
            onCommit = { _, onCommitted ->
                curlPreviewMoved = false
                finishCurlPreview(onCommitted)
            },
            onRollback = { onRestored ->
                rollbackCurlPreview(onRestored)
            },
            onFallback = { direction ->
                if (curlPreviewMoved) {
                    curlPreviewMoved = false
                    finishCurlPreview()
                } else {
                    pagedController.moveVisualUnit(if (direction == NovelPageCurlTurnDirection.FORWARD) 1 else -1)
                }
            },
        )
        curlView.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (oldRight > oldLeft && oldBottom > oldTop &&
                (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop)
            ) {
                curlController.destroy()
            }
        }
    }

    private fun handleReaderReady() {
        pendingErrorFeedback?.let { executeReaderCommand(readerCommand("feedback.show", it)) }
        pendingErrorFeedback = null
        if (docState == DocState.LOADING_REAL) finishRealChapterLoad()
    }

    private fun handleReaderEvent(name: String, payload: JsonObject) {
        activity.runOnUiThread {
            when (name) {
                "reader.saveVideoProgress" -> {
                    val chapterId = payload.long("chapterId")
                    val path = payload.string("path")
                    val currentChapter = getCurrentReaderChapter()?.chapter
                    if (currentChapter != null && chapterId == currentChapter.id && path == currentChapter.url) {
                        payload.int("progress")?.let(::saveVideoProgress)
                    }
                }
                "reader.refetch" -> activity.viewModel.reloadChapter(fromSource = true)
                "navigation.next" -> activity.loadNextChapter()
                "navigation.loadNext" -> loadNextChapterFromPage()
                "reader.error" -> payload.string("message")?.let { message ->
                    if (webChapterContentReady) inlineFeedback.showInlineError(message) else activity.toast(message)
                }
                "edit.changed" -> activity.viewModel.setHasUnsavedChanges(true)
                "edit.save" -> payload.string("content")
                    ?.takeIf(::isAllowedEditPayload)
                    ?.let(activity.viewModel::saveEditedChapterContent)
                "scroll.progressPersist" -> payload.float("progress")?.let(::onScrollProgress)
                "scroll.progressPreview" -> payload.float("progress")?.let(::onScrollPreview)
                "chapter.visible" -> {
                    val id = payload.long("chapterId")
                    val progress = payload.float("progress")
                    if (id != null && progress != null) onChapterVisible(id, progress)
                }
                "layout.positionChanged" -> onLayoutPositionChanged(payload)
                "summary.action" -> {
                    val id = payload.long("chapterId")
                    val action = payload.string("action")
                    if (id != null && action in SUMMARY_ACTIONS) summaryController.onAction(id, action.orEmpty())
                }
                "scroll.restoreComplete" -> payload.int("token")?.let(::onScrollRestoreComplete)
                "chapter.appended" -> {
                    if (payload.long("chapterId") != null) refreshFindInPage()
                }
                "gesture.claim" -> payload.string("owner")?.let { gestureTarget = ReaderGestureTarget.fromWire(it) }
                "gesture.ready" -> pageOwnsGestures = true
                "tts.toggle" -> toggleTtsFromPage()
                "tts.startAtParagraph" -> payload.int("index")?.let(::startTtsAtPageParagraph)
                "tts.startAtTaggedParagraph" -> startTtsAtTaggedPageParagraph()
                "chapter.markShort" -> markCurrentChapterShort()
                "chapter.retry" -> retryCurrentChapter()
                "feedback.retry" -> if (!isDestroyed) {
                    if (webChapterIsError) retryCurrentChapter() else inlineFeedback.retryPendingError()
                }
                "clipboard.copy" -> payload.string("text")?.let(::copyToClipboard)
                "media.playLocalVideo" -> launchLocalVideo(force = true)
                "navigation.openNext" -> activity.loadNextChapter()
                "navigation.previous" -> activity.loadPreviousChapter()
                "tts.start" -> startTts()
                "tts.pause" -> pauseTts()
                "tts.resume" -> resumeTts()
                "tts.stop" -> stopTts()
                "scroll.seekPercent" -> payload.int("percent")?.let(::setProgressPercent)
            }
        }
    }

    private fun handleReaderRequest(name: String, payload: JsonObject, reply: (kotlinx.serialization.json.JsonElement?) -> Unit) {
        when (name) {
            "media.requestProtectedPlayback" -> {
                val documentId = readerBridge.documentId ?: return reply(null)
                scope.launch {
                    val incognito = runCatching {
                        getIncognitoState.await(activity.viewModel.getSource()?.id)
                    }.getOrDefault(true)
                    val isCurrentDocument = !isDestroyed && readerBridge.documentId == documentId
                    if (!isCurrentDocument) return@launch
                    protectedMediaPlaybackArmed = !incognito && protectedMediaPlaybackOrigin != null
                    reply(JsonPrimitive(protectedMediaPlaybackArmed))
                }
            }
            else -> reply(null)
        }
    }

    private fun isAllowedEditPayload(content: String): Boolean {
        val chapters = runCatching { Json.parseToJsonElement(content).jsonArray }.getOrNull() ?: return false
        if (chapters.isEmpty() || chapters.size > loadedChapterIds.size) return false
        val allowedIds = loadedChapterIds.mapTo(mutableSetOf()) { it.toString() }
        val seenIds = mutableSetOf<String>()
        val valid = chapters.all { item ->
            val chapter = runCatching { item.jsonObject }.getOrNull() ?: return@all false
            val id = (chapter["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@all false
            val hasHtml = (chapter["content"] as? JsonPrimitive)?.isString == true
            if (!hasHtml) return@all false
            id in allowedIds && seenIds.add(id)
        }
        return valid && seenIds == allowedIds
    }

    private fun saveVideoProgress(progress: Int) {
        if (!isVideoChapter()) return
        val page = currentPage ?: return
        val bounded = progress.coerceIn(0, 100)
        lastSavedProgress = bounded / 100f
        lastPersistedPercent = bounded
        awaitingFirstScrollSample = false
        // Video seeking and disabled/live progress can legitimately move back to zero.
        activity.saveNovelProgress(page, bounded, backwardJumpAllowancePercent = 100)
        activity.onNovelProgressChanged(lastSavedProgress)
    }

    private fun onScrollProgress(progress: Float) {
        if (isVideoChapter() || isRestoringScroll || !progress.isFinite()) return
        awaitingFirstScrollSample = false
        lastSavedProgress = progress.coerceIn(0f, 1f)
        if (NovelProgress.progressToPercent(lastSavedProgress) != lastPersistedPercent) saveProgress()
    }

    private fun onScrollPreview(progress: Float) {
        if (isVideoChapter() || isRestoringScroll || !progress.isFinite() ||
            System.currentTimeMillis() - lastUserSeekAt < SEEK_ECHO_SUPPRESS_MS
        ) {
            return
        }
        awaitingFirstScrollSample = false
        lastSavedProgress = progress.coerceIn(0f, 1f)
        activity.onNovelProgressChanged(lastSavedProgress)
    }

    private fun onChapterVisible(id: Long, progress: Float) {
        if (isRestoringScroll || !progress.isFinite()) return
        val chapterIndex = loadedChapters.indexOfFirst { it.chapter.id == id }
        val chapter = loadedChapters.getOrNull(chapterIndex) ?: return
        val newPage = chapter.pages?.firstOrNull()
        val oldIndex = currentChapterIndex
        val identityChanged = chapterIndex != oldIndex
        val pageChanged = currentPage?.chapter?.chapter?.id != id
        val titleChanged = activity.viewModel.state.value.novelVisibleChapter?.id != id
        if (identityChanged) {
            NovelProgress.forwardChaptersToMarkRead(oldIndex, chapterIndex, loadedChapters.size).forEach { idx ->
                loadedChapters.getOrNull(idx)?.let { activity.viewModel.saveNovelProgress(it, 100) }
            }
            currentChapterIndex = chapterIndex
        }
        if (identityChanged || titleChanged) {
            activity.viewModel.setNovelVisibleChapter(chapter.chapter)
            updateChapterMetaJs()
        }
        if (newPage == null) {
            currentPage = null
            lastSavedProgress = if (chapterIndex > oldIndex) 0f else 1f
            lastPersistedPercent = -1
            awaitingFirstScrollSample = true
            requestChapterRecovery(id)
            return
        }
        keepOnlyRelevantChapterRecovery(id)
        if (identityChanged || pageChanged) {
            currentPage = newPage
            activity.onPageSelected(newPage)
            lastSavedProgress = progress.coerceIn(0f, 1f)
            lastPersistedPercent = -1
            awaitingFirstScrollSample = false
        }
    }

    private fun onLayoutPositionChanged(payload: JsonObject) {
        val chapterId = payload.long("chapterId") ?: return
        val unitIndex = payload.int("unitIndex") ?: return
        val unitCount = payload.int("unitCount") ?: return
        val firstPage = payload.int("firstPage") ?: return
        val lastPage = payload.int("lastPage") ?: return
        val totalPages = payload.int("totalPages") ?: return
        val previous = pagedController.position.value
        pagedController.onPosition(chapterId, unitIndex, unitCount, firstPage, lastPage, totalPages)
        if (previous != null && (previous.chapterId != chapterId || previous.unitIndex != unitIndex)) {
            activity.onNovelVisualPageChanged()
        }
    }

    private fun onScrollRestoreComplete(token: Int) {
        if (token != scrollRestoreToken) return
        liftRestoreGuard(token)
        refreshFindInPage()
    }

    private fun toggleTtsFromPage() {
        if (!isTtsEnabled) return
        when {
            ttsController.isPaused() -> resumeTts()
            ttsController.isSpeaking() -> pauseTts()
            isTtsActive() -> stopTts()
            else -> startTtsFromViewport()
        }
    }

    private fun startTtsAtPageParagraph(index: Int) {
        if (isTtsEnabled) startTtsAtParagraph(index.coerceAtLeast(0))
    }

    private fun startTtsAtTaggedPageParagraph() {
        if (isTtsEnabled) startTtsAtTaggedParagraph()
    }

    private fun loadNextChapterFromPage() {
        logcat(LogPriority.DEBUG) {
            "NovelWebViewViewer: loadNextChapter triggered, infiniteScroll=${isInfiniteScrollEnabled()}, isLoadingNext=$isLoadingNext, loadedCount=${loadedChapterIds.size}"
        }
        if (isInfiniteScrollEnabled() && (!webChapterContentReady || webChapterIsError)) {
            setJsLoadingNext()
        } else if (!isInfiniteScrollEnabled() || nextRequiresDocumentNavigation) {
            activity.loadNextChapter()
        } else if (reachedNovelEnd) {
            setJsNoMoreChapters(true)
        } else {
            startNextChapterAppend()
        }
    }

    private fun markCurrentChapterShort() {
        lastSavedProgress = 1f
        saveProgress()
        activity.onNovelProgressChanged(1f)
        startNextChapterAppend()
    }

    private fun retryCurrentChapter() {
        if (isDestroyed || !webChapterIsError) return
        showLoadingIndicator()
        activity.viewModel.reloadChapter(fromSource = true)
    }

    private fun copyToClipboard(text: String) {
        val cm = activity.getSystemService(android.content.ClipboardManager::class.java)
        cm.setPrimaryClip(android.content.ClipData.newPlainText("error", text))
        activity.toast(activity.stringResource(TDMR.strings.novel_error_copied))
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.toLongOrNull()

    private fun JsonObject.float(key: String): Float? =
        (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.toFloat()?.takeIf(Float::isFinite)

    private fun executeReaderCommand(command: ReaderCommand, callback: ((String?) -> Unit)? = null) {
        if (!::readerBridge.isInitialized || isDestroyed) {
            callback?.invoke(null)
            return
        }
        readerBridge.execute(command, callback)
    }

    private fun readerCommand(method: String, payload: JsonObject = JsonObject(emptyMap())) =
        ReaderCommand(method, payload)

    fun onContentInsetsChanged() {
        styler.injectStyles()
        pagedController.reflow()
    }

    private fun observePreferences() {
        NovelWebViewPreferenceObserver(
            preferences = preferences,
            scope = scope,
            onStyleChanged = {
                styler.injectStyles()
                styler.setBionicReading(preferences.novelBionicReading.get())
                pagedController.reflow()
            },
            onScriptChanged = {
                val isAppend = isInfiniteScrollEnabled() && loadedChapterIds.size > 1
                styler.injectScript(isAppend = isAppend, reapplyChangedOnly = true)
            },
            onChapterReloadRequested = {
                // Force a full pipeline re-run so the new prefs take effect.
                // Plain setChapters() would no-op on an already-loaded chapter.
                flushProgress()
                reloadChapter()
            },
            onBlockMediaChanged = { blockMedia ->
                webView.settings.apply {
                    blockNetworkImage = blockMedia
                    loadsImagesAutomatically = !blockMedia
                }
                webView.reload()
            },
            onTtsSettingsChanged = {
                if (ttsController.ttsInitialized) ttsController.applySettings()
            },
            onTtsEngineChanged = {
                ttsController.onEngineChanged()
                dispatchTtsState()
            },
        ).observe()
    }

    private fun restoreScrollPosition() {
        val page = currentPage ?: run {
            liftRestoreGuard(scrollRestoreToken)
            return
        }
        val initialPosition = activity.viewModel.takeInitialPosition(page.chapter.chapter.id ?: -1L)
        val savedProgress = initialPosition?.let { (it * 100).toInt() } ?: page.chapter.chapter.last_page_read
        val isRead = page.chapter.chapter.read

        val shouldRestore = if (initialPosition != null) {
            true
        } else if (!isRead) {
            savedProgress > 0 && savedProgress <= 100
        } else {
            libraryPreferences.novelReadProgress100.get() && savedProgress > 0 && savedProgress <= 100
        }
        if (pagedController.enabled) {
            val percent = if (shouldRestore) {
                initialPosition?.times(100) ?: savedProgress.coerceIn(0, 100).toFloat()
            } else {
                0f
            }
            lastSavedProgress = percent / 100f
            activity.onNovelProgressChanged(lastSavedProgress)
            isRestoringScroll = true
            val token = ++scrollRestoreToken
            executeReaderCommand(
                readerCommand(
                    "layout.seekPercent",
                    buildJsonObject {
                        put("percent", percent.toInt().coerceIn(0, 100))
                    },
                ),
            )
            webView.postDelayed({ liftRestoreGuard(token) }, 3000)
            return
        }
        if (shouldRestore) {
            val progress = initialPosition ?: savedProgress / 100f
            lastSavedProgress = progress
            activity.onNovelProgressChanged(progress)
            isRestoringScroll = true
            val token = ++scrollRestoreToken

            // Apply the saved ratio once the content has a scrollable range: immediately if laid
            // out, else a ResizeObserver waits for the body height. onScrollRestoreComplete lifts
            // the guard when done.
            executeReaderCommand(NovelWebViewReadingCommands.restoreVertical(progress, token))
            webView.postDelayed({ liftRestoreGuard(token) }, 3000)
        } else {
            isRestoringScroll = true
            val token = ++scrollRestoreToken
            webView.scrollTo(0, 0)
            lastSavedProgress = 0f
            activity.onNovelProgressChanged(0f)
            // Hold the guard past the scrollTo(0,0) settle so it can't persist 0 over a read chapter.
            webView.postDelayed({ liftRestoreGuard(token) }, 300)
        }
    }

    private fun getThemeColors(theme: String): Pair<Int, Int> =
        ThemeUtils.getThemeColors(activity, preferences, theme)

    override fun destroy() {
        if (isDestroyed) return
        inlineFeedback.clear()
        protectedMediaPlaybackArmed = false
        hideFullscreenVideo()
        WebView.setWebContentsDebuggingEnabled(
            BuildConfig.DEBUG &&
                activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
        )
        activity.closeFindInPage(this)
        stopAutoScroll()
        // Only persist if real progress exists. lastSavedProgress starts at 0 and stays 0
        // until onPageFinished restores or the user scrolls. Saving 0 here on an early
        // teardown (orientation lock recreates the activity before restore runs) would
        // wipe the chapter's saved progress.
        if (lastSavedProgress > 0f && !awaitingFirstScrollSample) saveProgress()

        ttsController.destroy()
        curlController.destroy()
        imageCache.clear()
        proxyServer?.close()
        proxyServer = null

        isDestroyed = true

        scope.cancel()

        attachListener?.let(container::removeOnAttachStateChangeListener)
        attachListener = null

        // cancel(), not dismiss(): dismiss() skips the OnCancelListener, so the pending
        // JsResult/JsPromptResult would never resolve and the WebView's JS thread stays blocked.
        try {
            activeJsDialog?.cancel()
        } catch (e: Throwable) {
            logcat(LogPriority.WARN) { "Failed to cancel active JS dialog during destroy (${e.message})" }
        }
        activeJsDialog = null

        container.removeView(webView)
        webView.stopLoading()
        webView.loadUrl("about:blank")
        readerBridge.endDocument()
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = null
        webView.destroy()
    }

    private fun showFullscreenVideo(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (fullscreenVideoContainer != null) {
            callback.onCustomViewHidden()
            return
        }

        (view.parent as? ViewGroup)?.removeView(view)
        fullscreenVideoCallback = callback
        orientationBeforeFullscreenVideo = activity.requestedOrientation
        fullscreenVideoContainer = FrameLayout(activity).apply {
            setBackgroundColor(Color.BLACK)
            addView(view, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }.also { fullscreenContainer ->
            activity.binding.root.addView(
                fullscreenContainer,
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            fullscreenContainer.bringToFront()
        }
        fullscreenVideoBackCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = hideFullscreenVideo()
        }.also { activity.onBackPressedDispatcher.addCallback(activity, it) }

        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        activity.onWebViewVideoFullscreenChanged()
    }

    private fun hideFullscreenVideo() {
        val fullscreenContainer = fullscreenVideoContainer ?: return
        (fullscreenContainer.parent as? ViewGroup)?.removeView(fullscreenContainer)
        fullscreenContainer.removeAllViews()
        fullscreenVideoContainer = null

        fullscreenVideoBackCallback?.remove()
        fullscreenVideoBackCallback = null
        fullscreenVideoCallback?.onCustomViewHidden()
        fullscreenVideoCallback = null

        if (!activity.isFinishing && !activity.isDestroyed) {
            orientationBeforeFullscreenVideo?.let { activity.requestedOrientation = it }
        }
        orientationBeforeFullscreenVideo = null
        if (!activity.isFinishing && !activity.isDestroyed) {
            activity.onWebViewVideoFullscreenChanged()
        }
    }

    private fun finishRealChapterLoad() {
        docState = DocState.READY

        styler.injectScript()
        // A fresh DOM needs the current menu flag before custom reader scripts run.
        pushReaderChrome()
        if (isVideoChapter()) {
            pagedController.disable()
            stopTts(preserveChapterLoad = true)
            val progress = currentPage?.chapter?.chapter?.last_page_read?.coerceIn(0, 100) ?: 0
            lastSavedProgress = progress / 100f
            lastPersistedPercent = progress
            awaitingFirstScrollSample = false
            isRestoringScroll = false
            activity.onNovelProgressChanged(lastSavedProgress)
        } else {
            pagedController.install(
                enabled = isPagedLayoutEnabled(),
                direction = currentDocumentDirection,
                infinite = isInfiniteScrollEnabled(),
                chapterId = currentChapters?.currChapter?.chapter?.id ?: -1L,
            )
            if (isInfiniteScrollEnabled() || isPagedLayoutEnabled()) styler.injectScopedChapterAnchors()
            styler.injectScrollTracking(isInfiniteScrollEnabled())
            styler.injectReaderUi()
            restoreScrollPosition()
            if (isEditingMode) toggleEditMode(true)
        }
        activity.showReadingMode(isPagedMode())
        if (!isInfiniteScrollEnabled()) {
            styler.injectNextChapterButton(
                chapterName = currentChapters?.currChapter?.chapter?.name.orEmpty(),
                nextChapterName = currentChapters?.nextChapter?.chapter?.name,
            )
        }
        // Real content rendered (docState = READY above); TTS may now read the body.
        dispatchLoadingChapter(false)
        if (pendingTtsAutoStartOnLoad && !isVideoChapter()) {
            pendingTtsAutoStartOnLoad = false
            startTts()
        }
        ttsController.pendingStartRequest?.takeUnless { isVideoChapter() }?.let { request ->
            ttsController.pendingStartRequest = null
            when (request) {
                TtsController.StartRequest.NORMAL -> startTts()
                TtsController.StartRequest.VIEWPORT -> {
                    pendingTtsParagraphIndex?.let {
                        pendingTtsParagraphIndex = null
                        startTtsAtParagraph(it)
                    } ?: startTtsFromViewport()
                }
            }
        }
        // A full reload replaces window, dropping the autoscroll rAF loop; re-arm it
        // on the new document so autoscroll survives a non-inf-scroll chapter change.
        if (isAutoScrolling && !isVideoChapter()) startAutoScroll()
    }

    /**
     * Persist the latest live progress immediately. The JS debounce timer can be throttled while
     * the WebView is backgrounded, so the activity's onPause calls this to avoid losing the tail.
     */
    fun flushProgress() {
        if (awaitingFirstScrollSample) return
        if (lastSavedProgress > 0f && NovelProgress.progressToPercent(lastSavedProgress) != lastPersistedPercent) {
            saveProgress()
        }
    }

    private var lastPersistedPercent = -1
    private fun saveProgress() {
        currentPage?.let { page ->
            val progressValue = NovelProgress.progressToPercent(lastSavedProgress)
            lastPersistedPercent = progressValue
            val unitCount = pagedController.position.value
                ?.takeIf { pagedController.enabled && it.chapterId == page.chapter.chapter.id }
                ?.unitCount
            activity.saveNovelProgress(page, progressValue, NovelProgress.backwardJumpAllowancePercent(unitCount))
            logcat(LogPriority.DEBUG) { "NovelWebViewViewer: Saving progress $progressValue%" }
        }
    }

    /**
     * Persists progress for the chapter currently being spoken by TTS based on
     * chunk index. The scroll-based save path does not fire when the activity
     * is in the background (no JS scroll events make it back through the
     * bridge while paused), so TTS sessions running under the foreground
     * service would lose progress until the user returns.
     */
    private var lastSavedTtsChunkIndex: Int = -1
    private fun saveTtsProgressForChunk(chunkIndex: Int) {
        // Foreground: the per-chapter scroll bridge (onScrollProgress) owns progress and the slider.
        // Persist from TTS only when backgrounded, where the JS scroll bridge is paused and this is
        // the sole progress source. The TTS queue is scoped to ttsPlaybackChapterId.
        if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        if (chunkIndex == lastSavedTtsChunkIndex) return
        lastSavedTtsChunkIndex = chunkIndex
        val total = ttsController.ttsChunks.size
        if (total <= 0) return
        val chapterIdx = ttsController.ttsPlaybackChapterIndex
        val chapter = loadedChapters.getOrNull(chapterIdx) ?: return
        val page = chapter.pages?.firstOrNull() ?: return
        val percent = (((chunkIndex + 1) * 100f) / total).toInt().coerceIn(0, 100)
        activity.saveNovelProgress(page, percent)
    }

    private fun shouldAutoMarkShortChapter(page: ReaderPage?): Boolean {
        if (!preferences.novelMarkShortChapterAsRead.get()) return false
        val chapter = page?.chapter?.chapter ?: return false
        return !chapter.read && chapter.last_page_read <= 0
    }

    override fun getView(): View = container

    fun reloadWithTranslation() {
        val page = currentPage ?: return
        if (page.text.isNullOrBlank()) {
            activity.viewModel.reloadChapter(fromSource = true)
            return
        }
        // Reuse the full document path, including queue reset and stale append cancellation.
        stopTts(preserveChapterLoad = true)
        displayContent(page.chapter, page, showTranslationLoading = false)
    }

    override fun setChapters(chapters: ViewerChapters) {
        val chapterId = chapters.currChapter.chapter.id ?: return
        loadJob?.cancel()

        if (currentChapters?.currChapter?.chapter?.id != chapterId) {
            launchedVideoChapterId = null
        }

        currentChapters = chapters
        val chapterError = chapters.currChapter.state as? ReaderChapter.State.Error
        if (chapterError != null) {
            contentJob?.cancel()
            appendJob?.cancel()
            cancelTtsChapterPreparation()
            ttsController.stop()
            currentPage = null
            chapterQueue.clear()
            readerBridge.updateChapters(emptyList())
            displayError(chapterError.error)
            return
        }
        val page = chapters.currChapter.pages?.firstOrNull() ?: return
        currentPage = page

        if (loadedChapterIds.contains(chapterId)) {
            logcat(LogPriority.DEBUG) { "NovelWebViewViewer: Chapter $chapterId already loaded, skipping" }
            val index = chapterQueue.indexOf(chapterId)
            if (index >= 0) {
                currentChapterIndex = index
                updateReaderDocumentConfig()
            }
            return
        }

        cancelTtsChapterPreparation()
        ttsController.stop()

        if (!isInfiniteScrollEnabled() || loadedChapterIds.isEmpty()) {
            chapterQueue.clear()
            readerBridge.updateChapters(emptyList())
            currentChapterIndex = 0
        }
        if (page.status == Page.State.Ready && page.text.isNullOrBlank()) {
            page.status = Page.State.Queue
        }
        if (page.status == Page.State.Ready && !page.text.isNullOrEmpty()) {
            displayContent(chapters.currChapter, page)
            activity.viewModel.setNovelVisibleChapter(page.chapter.chapter)
            return
        }

        showLoadingIndicator()

        loadJob = scope.launch {
            val loader = page.chapter.pageLoader
            if (loader == null) {
                logcat(LogPriority.ERROR) { "NovelWebViewViewer: No page loader available" }
                return@launch
            }

            launch(Dispatchers.IO) {
                loader.loadPage(page)
            }

            page.statusFlow.collectLatest { state ->
                when (state) {
                    Page.State.Queue, Page.State.LoadPage -> {
                        showLoadingIndicator()
                    }
                    Page.State.Ready -> {
                        displayContent(chapters.currChapter, page)
                        activity.viewModel.setNovelVisibleChapter(page.chapter.chapter)
                    }
                    is Page.State.Error -> displayError(state.error)
                    else -> {}
                }
            }
        }
    }

    private fun displayContent(
        chapter: ReaderChapter,
        page: ReaderPage,
        showTranslationLoading: Boolean = true,
    ) {
        val rawContent = page.text
        if (rawContent.isNullOrBlank()) {
            displayError(Exception(activity.stringResource(TDMR.strings.novel_error_empty_chapter)))
            return
        }

        val chapterId = chapter.chapter.id ?: return

        contentJob?.cancel()
        cancelTtsChapterPreparation()
        // An in-flight append targets the DOM this base load is about to replace; cancelling it
        // avoids splicing a stale chapter's content onto the newly loaded one when it resumes.
        appendJob?.cancel()
        // Gate infinite-scroll appends until this base chapter's DOM is committed.
        docState = DocState.LOADING
        val job = scope.launch {
            try {
                if (showTranslationLoading && activity.isTranslationEnabled()) {
                    val labelRes = if (activity.hasCachedTranslation(chapterId)) {
                        TDMR.strings.novel_chapter_translating_from_cache
                    } else {
                        TDMR.strings.novel_chapter_translating_from_api
                    }
                    showLoadingIndicator(activity.stringResource(labelRes))
                }

                val prepared = prepareChapterContent(chapter, page, rawContent, isAppend = false)

                withContext(Dispatchers.Main) {
                    loadHtmlContent(
                        prepared.processed,
                        chapter,
                        prepared.directives,
                        prepared.direction,
                        prepared.language,
                    )
                    chapterQueue.reset(chapter)
                    readerBridge.updateChapters(loadedChapterIds)
                    if (prepared.directives.noCache) page.text = null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                displayError(e)
            }
        }
        contentJob = job
    }

    private data class PreparedChapterContent(
        val processed: ProcessedContent,
        val directives: NovelWebViewChapterDirectives,
        val direction: NovelContentDirection,
        val language: String,
    )

    private suspend fun prepareChapterContent(
        chapter: ReaderChapter,
        page: ReaderPage,
        rawContent: String,
        isAppend: Boolean,
    ): PreparedChapterContent {
        chapter.wordCount.value = null
        val chapterId = chapter.chapter.id ?: -1L
        val cfg = ContentConfig.from(
            preferences,
            RenderTarget.WEB_VIEW,
            chapter.chapter.url,
            chapter.chapter.name,
            replacementTarget = activity.viewModel.manga?.let { ReplacementTarget(it.source, it.url) },
        )
        val translator: (suspend (String) -> String)? =
            if (activity.isTranslationEnabled()) {
                { content -> activity.translateContentIfEnabled(content, chapterId) }
            } else {
                null
            }
        val contentLanguage = if (activity.isTranslationEnabled()) {
            translationPreferences.targetLanguage().get()
        } else {
            activity.viewModel.getSource()?.lang.orEmpty()
        }
        val prepared = withContext(Dispatchers.Default) {
            val directives = page.chapterContent?.let(NovelWebViewChapterDirectives::fromContent)
                ?: NovelWebViewChapterDirectives.parse(rawContent)
            val loadedHtml = directives.adaptLoadedHtml(rawContent)
            var processed = contentPipeline.process(loadedHtml, cfg, if (directives.checkpoint) null else translator)
            // Keep only the count so enabling the bar later never refetches no-cache content.
            // TODO: Skip counting when disabled and avoid delaying chapter preparation, while preserving no-cache behavior.
            val wordCount = if (directives.checkpoint) {
                null
            } else {
                WordDensity.countWordsAsync(processed.text)
            }
            currentCoroutineContext().ensureActive()
            chapter.wordCount.value = wordCount
            if (isAppend && processed.text.contains(NovelWebViewImageCache.URL_SCHEME_NOVEL_IMAGE)) {
                processed = processed.copy(
                    text = processed.text.replace(
                        NovelWebViewImageCache.URL_SCHEME_NOVEL_IMAGE,
                        "${NovelWebViewImageCache.URL_SCHEME_NOVEL_IMAGE}$chapterId/",
                    ),
                )
            }
            PreparedChapterContent(
                processed = processed,
                directives = directives,
                direction = detectNovelContentDirection(processed.text, contentLanguage),
                language = contentLanguage,
            )
        }
        imageCache.schedulePrefetch(prepared.processed.text, chapter.chapter.id, page.chapter.pageLoader)
        return prepared
    }

    private fun appendHtmlContent(
        processed: ProcessedContent,
        chapterId: Long,
        chapterName: String,
        chapterNumber: Float,
        chapterUrl: String?,
        direction: NovelContentDirection,
        language: String,
    ) {
        val command = NovelWebViewChapterDomScripts.append(
            chapter = scriptChapter(chapterId, chapterName, chapterNumber, chapterUrl, direction, language),
            processed = processed,
        )

        dispatchLoadingChapter(true)
        executeReaderCommand(command) {
            styler.injectScript(isAppend = true)
            dispatchLoadingChapter(false)
            updateReaderDocumentConfig()
        }

        // A chapter was appended, so the end-of-novel verdict is stale.
        reachedNovelEnd = false
        nextRequiresDocumentNavigation = false
        setJsNoMoreChapters(false)

        logcat(LogPriority.DEBUG) { "NovelWebViewViewer: Appended chapter $chapterId (${loadedChapterIds.size} total)" }
    }

    private fun scriptChapter(
        id: Long,
        name: String,
        number: Float,
        path: String?,
        direction: NovelContentDirection,
        language: String,
    ) = NovelWebViewChapterDomScripts.ScriptChapter(
        id = id,
        name = name,
        number = number,
        path = path.orEmpty(),
        absoluteUrl = toAbsoluteChapterUrl(path),
        direction = direction,
        language = language,
    )

    private suspend fun loadHtmlContent(
        processed: ProcessedContent,
        chapter: ReaderChapter? = null,
        directives: NovelWebViewChapterDirectives = NovelWebViewChapterDirectives(),
        direction: NovelContentDirection = NovelContentDirection.LTR,
        language: String = "",
    ) {
        activity.closeFindInPage(this)
        inlineFeedback.clear()
        nextLoadRequiresManualRetry = false

        val chapterModel = chapter?.chapter
        val chapterId = chapterModel?.id ?: -1L
        val chapterPath = chapterModel?.url.orEmpty()

        val stylePayload = styler.buildPayload()
        webView.setBackgroundColor(stylePayload.backgroundColor)
        container.setBackgroundColor(stylePayload.backgroundColor)

        val baseUrl = resolveWebViewBaseUrl(chapterPath) ?: NovelWebViewChapterMeta.READER_DOCUMENT_BASE_URL
        val documentId = readerBridge.beginDocument(baseUrl, listOf(chapterId))

        invalidateChapterRecovery()
        chapterQueue.clear()
        chapter?.let(chapterQueue::reset)
        readerBridge.updateChapters(loadedChapterIds)
        currentChapterIndex = 0
        currentDocumentIsVideo = directives.isVideo
        currentDocumentNoPrefetch = directives.noPrefetch
        currentDocumentDirection = direction
        currentLocalVideo = directives.localVideo?.let { fileName ->
            val loader = chapter?.pageLoader as? DownloadPageLoader
            loader?.findDownloadedFile(fileName)?.let { chapterId to it }
        }

        // Bootstrap must describe this document, including its media/layout policy and chapter list.
        val readerConfig = buildReaderConfig(documentId, "reader", chapter, processed)
        readerConfigJson = readerConfig

        // Inputs are gathered on Main (touch viewer state), but the heavy work - the image-URL
        // regex scan and the Jsoup parse + full-document string build - runs off the main thread.
        // For large chapters this was a multi-MB alloc + DOM parse on the UI thread (frame skips).
        val input = NovelWebViewDocumentBuilder.DocumentInput(
            documentId = documentId,
            readerConfig = readerConfig,
            processed = processed,
            chapter = chapter,
            style = stylePayload,
            themeTokens = ThemeUtils.getThemeTokens(activity, preferences, preferences.novelTheme.get()),
            infiniteScrollEnabled = isInfiniteScrollEnabled(),
            pagedLayoutEnabled = isPagedLayoutEnabled(),
            chapterDirection = direction,
            chapterLanguage = language,
            blockMedia = preferences.novelBlockMedia.get(),
            chapterDirectives = directives,
        )
        val html = withContext(Dispatchers.Default) {
            NovelWebViewDocumentBuilder.assemble(input)
        }

        // Signal to onPageFinished that the next callback is for real chapter content, not
        // the loading-indicator page (which also fires onPageFinished with url="about:blank").
        docState = DocState.LOADING_REAL
        dispatchLoadingChapter(true)
        // New document: hold scroll callbacks and clear the baseline so a stale flush can't write
        // the previous chapter's percent here, restoreScrollPosition seeds the real value.
        isRestoringScroll = true
        lastSavedProgress = 0f
        lastPersistedPercent = -1
        reachedNovelEnd = false
        nextRequiresDocumentNavigation = false
        protectedMediaPlaybackOrigin = baseUrl?.let(Uri::parse)?.let {
            protectedMediaOrigin(it.scheme, it.host, it.port)
        }
        expectingBridgeDocumentStart = true
        webView.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)
        launchLocalVideo()
    }

    private fun launchLocalVideo(force: Boolean = false) {
        val (chapterId, file) = currentLocalVideo ?: return
        if (!force && launchedVideoChapterId == chapterId) return
        launchedVideoChapterId = chapterId

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(file.uri, "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            activity.toast(activity.stringResource(TDMR.strings.novel_error_no_video_player))
        }
    }

    // Memoized: the manga URL is fixed for the viewer's lifetime, and getMangaUrl() does a source
    // lookup + toSManga() + getMangaUrlOrNull() that ran twice per chapter load/append otherwise.
    private var cachedMangaUrl: String? = null

    private fun resolvedMangaUrl(): String? {
        cachedMangaUrl?.let { return it }
        return (activity.viewModel.getMangaUrl() ?: activity.viewModel.manga?.url)
            ?.also { cachedMangaUrl = it }
    }

    private suspend fun resolveWebViewBaseUrl(chapterUrl: String?): String? {
        val source = activity.viewModel.getSource()
        val sourceBaseUrl = when (source) {
            is JsSource -> source.baseUrl.takeIf(String::isNotBlank)
            is eu.kanade.tachiyomi.source.online.HttpSource -> source.baseUrl.takeIf(String::isNotBlank)
            else -> null
        }
        // A source path is opaque. Its resolver owns the external URL and JS-scoped forwarding.
        val externalChapterUrl = if (source is JsSource && !chapterUrl.isNullOrBlank()) {
            source.resolveUrl(chapterUrl, isNovel = false)
        } else {
            chapterUrl
        }
        val base = NovelWebViewChapterMeta.resolveWebViewBaseUrl(externalChapterUrl, resolvedMangaUrl(), sourceBaseUrl)
        return if (source is JsSource) {
            base
        } else {
            base?.let {
                networkHelper.domainForwarding.rewrite(it, fromJsPlugin = false)
            }
        }
    }

    private fun interceptNetworkRequest(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        if (request.url.scheme != "http" && request.url.scheme != "https") return null
        // Intercepted streams cannot seek; let WebView handle media byte ranges.
        if (request.requestHeaders.keys.any { it.equals("Range", ignoreCase = true) }) return null
        val acceptsImages = request.requestHeaders.entries.any { (name, value) ->
            name.equals("Accept", ignoreCase = true) && "image/" in value
        }
        val looksLikeImage = IMAGE_URL_REGEX.containsMatchIn(request.url.path.orEmpty())
        val imageRequestInit = if (acceptsImages || looksLikeImage) {
            (activity.viewModel.getSource() as? JsSource)?.currentImageRequestInit()
        } else {
            null
        }
        if (imageRequestInit == null && !request.method.equals("GET", true) &&
            !request.method.equals("HEAD", true)
        ) {
            return null
        }

        return runCatching {
            val networkRequest = Request.Builder().apply {
                url(url)
                request.requestHeaders.forEach { (name, value) -> header(name, value) }
                if (imageRequestInit != null) {
                    applyJsImageRequestInit(imageRequestInit)
                } else if (request.method.equals("HEAD", true)) {
                    head()
                }
            }.build()
            val response = networkHelper.client.newCall(networkRequest).execute()
            val responseBody = response.body
            val contentType = responseBody.contentType()
            WebResourceResponse(
                contentType?.let { "${it.type}/${it.subtype}" } ?: "application/octet-stream",
                contentType?.charset()?.name() ?: "UTF-8",
                response.code,
                response.message.ifBlank { "OK" },
                response.headers.toMultimap().mapValues { (_, values) -> values.joinToString(", ") },
                responseBody.byteStream(),
            )
        }.getOrElse {
            logcat(LogPriority.WARN) { "Failed to load WebView resource $url: ${it.message}" }
            WebResourceResponse(
                "text/plain",
                "UTF-8",
                502,
                "Bad Gateway",
                emptyMap(),
                ByteArray(0).inputStream(),
            )
        }
    }

    private fun toAbsoluteChapterUrl(chapterPath: String?): String =
        NovelWebViewChapterMeta.toAbsoluteChapterUrl(chapterPath, activity.viewModel.manga?.url)

    private fun navigateToEpubChapter(targetUrl: String) {
        val targetChapterId = activity.viewModel.findChapterIdByUrl(targetUrl) ?: run {
            logcat(LogPriority.WARN) { "EPUB link target is not present in the chapter list: $targetUrl" }
            return
        }
        val request = activity.viewModel.beginChapterNavigation(ReaderNavigationSource.USER) ?: return
        scope.launch {
            try {
                stopAutoScroll()
                stopTts()
                flushProgress()
                if (activity.viewModel.loadChapterById(targetChapterId, request)) {
                    scrollToLoadedChapter(targetChapterId)
                }
            } finally {
                activity.viewModel.finishChapterNavigation(request)
            }
        }
    }

    private fun buildReaderConfig(
        documentId: String,
        mode: String,
        chapter: ReaderChapter?,
        processed: ProcessedContent? = null,
    ): JsonObject {
        val novel = activity.viewModel.manga
        val novelUrl = resolvedMangaUrl().orEmpty()
        val activeChapter = chapter ?: getCurrentReaderChapter()
        val chapters = if (mode == "reader") {
            loadedChapters.ifEmpty { currentChapters?.currChapter?.let(::listOf).orEmpty() }
        } else {
            emptyList()
        }
        val nextChapter = currentChapters?.nextChapter?.takeIf { mode == "reader" }
        val visible = activity.isReaderChromeVisible()
        val source = activity.viewModel.getSource()
        val sourceBaseUrl = when (source) {
            is JsSource -> source.baseUrl
            is eu.kanade.tachiyomi.source.online.HttpSource -> source.baseUrl
            else -> null
        }
        val activeId = activeChapter?.chapter?.id
        val chapterJson = NovelWebViewChapterMeta.buildChapterJson(
            if (mode == "reader") activeChapter else null,
            novelUrl,
        )
        return buildJsonObject {
            put("documentId", documentId)
            put("mode", mode)
            put(
                "novel",
                NovelWebViewChapterMeta.buildNovelJson(
                    novel?.id?.toString().orEmpty(),
                    novel?.title.orEmpty(),
                    novel?.url.orEmpty(),
                ),
            )
            put("chapter", chapterJson)
            put("nextChapter", nextChapter?.let { NovelWebViewChapterMeta.buildChapterJson(it, novelUrl) } ?: JsonNull)
            put("chapters", NovelWebViewChapterMeta.buildChaptersJson(chapters, novelUrl))
            put(
                "strings",
                buildJsonObject {
                    put(
                        "finished",
                        activity.stringResource(
                            TDMR.strings.reader_chapter_finished,
                            activeChapter?.chapter?.name.orEmpty(),
                        ),
                    )
                    put(
                        "nextChapter",
                        activity.stringResource(TDMR.strings.reader_next_chapter, nextChapter?.chapter?.name.orEmpty()),
                    )
                    put("noNextChapter", activity.stringResource(MR.strings.transition_no_next))
                    put("videoResumeTitle", activity.stringResource(TDMR.strings.video_resume_title))
                    put("videoResumeQuestion", activity.stringResource(TDMR.strings.video_resume_question))
                    put("videoResumeContinue", activity.stringResource(TDMR.strings.video_resume_continue))
                    put("videoResumeRestart", activity.stringResource(TDMR.strings.video_resume_restart))
                    put("videoNextUp", activity.stringResource(TDMR.strings.video_next_up))
                    put("videoNextPlay", activity.stringResource(TDMR.strings.video_next_play))
                    put("videoSkipIntro", activity.stringResource(TDMR.strings.video_skip_intro))
                    put("close", activity.stringResource(MR.strings.action_close))
                },
            )
            put("autoSaveInterval", 5_000)
            put(
                "ttsNormalizationRules",
                kotlinx.serialization.json.buildJsonArray {
                    TtsTextUtils.normalizationReplacements.forEach { (pattern, replacement) ->
                        add(
                            buildJsonObject {
                                put("pattern", pattern)
                                put("replacement", replacement)
                            },
                        )
                    }
                },
            )
            if (mode == "reader") proxyServer?.endpoint?.let { put("proxyEndpoint", it) }
            put(
                "runtime",
                buildJsonObject {
                    put("editing", mode == "reader" && isEditingMode)
                    put("infinite", mode == "reader" && isInfiniteScrollEnabled())
                    put("textSelectionBlocked", !preferences.novelTextSelectable.get())
                    put("forcedLowercase", preferences.novelForceTextLowercase.get())
                    put("menuVisible", visible)
                    put("immersive", !visible)
                    put(
                        "ttsState",
                        if (mode ==
                            "reader"
                        ) {
                            currentTtsState().wireValue
                        } else {
                            TtsPlaybackState.STOPPED.wireValue
                        },
                    )
                    put("loadingChapter", mode == "reader" && !webChapterContentReady)
                    put("loadingNext", mode == "reader" && isLoadingNext)
                    put("noMoreChapters", mode != "reader" || reachedNovelEnd)
                },
            )
            put("markShortChapterAsRead", preferences.novelMarkShortChapterAsRead.get())
            put(
                "ui",
                buildJsonObject {
                    put("bionicEnabled", mode == "reader" && preferences.novelBionicReading.get())
                    put("ttsEnabled", mode == "reader" && isTtsEnabled)
                    put(
                        "labels",
                        buildJsonObject {
                            put("tts", activity.stringResource(TDMR.strings.reader_tts_control))
                            put("imageClose", activity.stringResource(TDMR.strings.reader_image_close))
                        },
                    )
                },
            )
            val style = styler.buildPayload()
            put(
                "style",
                buildJsonObject {
                    put("css", style.css)
                    put("bodyClasses", style.bodyClasses)
                    put("backgroundColor", style.backgroundColor)
                    put(
                        "background",
                        runCatching {
                            Json.parseToJsonElement(style.backgroundConfig)
                        }.getOrDefault(JsonObject(emptyMap())),
                    )
                },
            )
            if (processed?.isPlainText == true) {
                put(
                    "initialContent",
                    buildJsonObject {
                        put("kind", "plainText")
                        put("text", processed.text)
                    },
                )
            }
            if (mode == "reader") {
                put(
                    "scrollTracking",
                    buildJsonObject {
                        put("infiniteScrollEnabled", isInfiniteScrollEnabled())
                        put("loadThreshold", preferences.novelAutoLoadNextChapterAt.get().coerceIn(0, 100) / 100.0)
                        put("doneThreshold", NovelProgress.DONE_THRESHOLD)
                    },
                )
                put(
                    "layout",
                    buildJsonObject {
                        put("enabled", isPagedLayoutEnabled())
                        put("spread", if (pagedController.isDoubleSpread()) "double" else "single")
                        put("direction", currentDocumentDirection.htmlValue)
                        put("vertical", preferences.novelReadingLayout.get() == NovelReadingLayout.VERTICAL)
                        put("infinite", isInfiniteScrollEnabled())
                        put("threshold", preferences.novelAutoLoadNextChapterAt.get().coerceIn(0, 100) / 100.0)
                        put("chapterId", activeId?.toString().orEmpty())
                    },
                )
            }
            put(
                "context",
                buildJsonObject {
                    put("documentId", documentId)
                    put("mode", mode)
                    put(
                        "novel",
                        NovelWebViewChapterMeta.buildNovelJson(
                            novel?.id?.toString().orEmpty(),
                            novel?.title.orEmpty(),
                            novel?.url.orEmpty(),
                        ),
                    )
                    put("chapter", chapterJson)
                    sourceBaseUrl?.let { put("sourceBaseUrl", it) }
                    novel?.id?.let { put("novelId", it.toString()) }
                    activeId?.let { put("chapterId", it.toString()) }
                    source?.id?.let { put("sourceId", it.toString()) }
                },
            )
            if (mode == "reader" && currentDocumentIsVideo) {
                val directives = currentPage?.let { page ->
                    (
                        page.chapterContent?.let(NovelWebViewChapterDirectives::fromContent)
                            ?: NovelWebViewChapterDirectives.parse(page.text.orEmpty())
                        )
                }
                directives?.video?.let { video ->
                    put(
                        "player",
                        video.playerConfig(
                            mode = "playback",
                            path = activeChapter?.chapter?.url.orEmpty(),
                            progress = activeChapter?.chapter?.last_page_read?.coerceIn(0, 100) ?: 0,
                            nextChapterName = nextChapter?.chapter?.name,
                            proxyEndpoint = proxyServer?.endpoint,
                        ),
                    )
                }
            }
            put("pluginJavaScript", if (mode == "reader") styler.initialPluginJavaScript() else "")
        }
    }

    private fun getCurrentReaderChapter(): ReaderChapter? =
        loadedChapters.getOrNull(currentChapterIndex) ?: currentChapters?.currChapter

    fun wordCountFor(chapterId: Long?): StateFlow<Int?>? = (
        currentChapters?.currChapter?.takeIf { it.chapter.id == chapterId }
            ?: loadedChapters.firstOrNull { it.chapter.id == chapterId }
        )?.wordCount

    /** A normal tap focuses an existing summary; the menu can explicitly regenerate it. */
    fun requestChapterSummary(regenerate: Boolean = false) {
        val chapterId = getCurrentReaderChapter()?.chapter?.id ?: return
        if (regenerate) summaryController.onAction(chapterId, "regenerate") else summaryController.request(chapterId)
    }

    private fun requestChapterRecovery(chapterId: Long) {
        if (isDestroyed) return
        if (chapterRecoveryJob != null) {
            if (chapterRecoveryId == chapterId) {
                // A non-null pending ID means this load was invalidated while another chapter was
                // visible. If the user came back, rerun it after the stale load releases its ref.
                if (pendingChapterRecoveryId != null) pendingChapterRecoveryId = chapterId
                return
            }
            pendingChapterRecoveryId = chapterId
            chapterRecoveryRequest?.let(activity.viewModel::finishChapterNavigation)
            chapterRecoveryId = null
            chapterRecoveryJob?.cancel()
            return
        }
        startChapterRecovery(chapterId)
    }

    private fun startChapterRecovery(chapterId: Long) {
        pendingChapterRecoveryId = null
        chapterRecoveryId = chapterId
        chapterRecoveryJob = scope.launch {
            var request: ReaderNavigationRequest? = null
            try {
                request = activity.viewModel.beginAutomaticChapterNavigation()
                chapterRecoveryRequest = request
                val stillVisible = getCurrentReaderChapter()?.chapter?.id == chapterId
                val stillMissingPage = loadedChapters
                    .firstOrNull { it.chapter.id == chapterId }
                    ?.pages
                    ?.firstOrNull() == null
                if (chapterRecoveryId != chapterId || !stillVisible || !stillMissingPage) return@launch
                val loaded = activity.viewModel.loadChapterById(chapterId, request)
                if (loaded) resetChapterTracking()
            } finally {
                request?.let(activity.viewModel::finishChapterNavigation)
                if (chapterRecoveryRequest === request) {
                    chapterRecoveryJob = null
                    chapterRecoveryId = null
                    chapterRecoveryRequest = null
                    val pendingId = pendingChapterRecoveryId
                    pendingChapterRecoveryId = null
                    val pendingNeedsPage = loadedChapters
                        .firstOrNull { it.chapter.id == pendingId }
                        ?.pages
                        ?.firstOrNull() == null
                    if (
                        scope.isActive &&
                        pendingId != null &&
                        getCurrentReaderChapter()?.chapter?.id == pendingId &&
                        pendingNeedsPage
                    ) {
                        startChapterRecovery(pendingId)
                    }
                }
            }
        }
    }

    private fun keepOnlyRelevantChapterRecovery(chapterId: Long) {
        if (chapterRecoveryJob != null && chapterRecoveryId != chapterId) {
            chapterRecoveryRequest?.let(activity.viewModel::finishChapterNavigation)
            chapterRecoveryId = null
            chapterRecoveryJob?.cancel()
            pendingChapterRecoveryId = chapterId
        } else if (chapterRecoveryJob == null) {
            pendingChapterRecoveryId = null
        }
    }

    private fun invalidateChapterRecovery() {
        chapterRecoveryRequest?.let(activity.viewModel::finishChapterNavigation)
        chapterRecoveryJob?.cancel()
        chapterRecoveryId = null
        pendingChapterRecoveryId = null
    }

    /**
     * The chapter's source HTML, loading it first if `noCache` dropped it.
     *
     * Deliberately not the rendered DOM: that may hold a translation, and summarizing a translation
     * summarizes the translator's choices rather than the chapter.
     */
    private suspend fun loadChapterHtml(chapterId: Long): String? {
        val chapter = loadedChapters.firstOrNull { it.chapter.id == chapterId }
            ?: currentChapters?.currChapter?.takeIf { it.chapter.id == chapterId }
            ?: return null
        val page = chapter.pages?.firstOrNull() ?: awaitRecoveredChapterPage(chapterId, chapter) ?: return null
        val loader = page.chapter.pageLoader
        if (page.text.isNullOrBlank() && loader != null) {
            awaitPageText(page = page, loader = loader, timeoutMs = 30_000)
        }
        val html = page.text
        if (!html.isNullOrBlank() && page.chapterContent?.noCache == true) {
            page.text = null
        }
        return html
    }

    private suspend fun awaitRecoveredChapterPage(chapterId: Long, chapter: ReaderChapter): ReaderPage? {
        if (getCurrentReaderChapter()?.chapter?.id != chapterId) return null
        requestChapterRecovery(chapterId)
        var recovery = chapterRecoveryJob
        while (recovery != null) {
            recovery.join()
            chapter.pages?.firstOrNull()?.let { return it }
            if (getCurrentReaderChapter()?.chapter?.id != chapterId) return null
            val nextRecovery = chapterRecoveryJob
            if (nextRecovery == null || nextRecovery === recovery) return null
            recovery = nextRecovery
        }
        return null
    }

    private fun updateChapterMetaJs() {
        updateReaderDocumentConfig()
    }

    private fun updateReaderDocumentConfig() {
        val oldConfig = readerConfigJson ?: return
        val documentId = oldConfig.string("documentId") ?: return
        val rebuilt = buildReaderConfig(
            documentId = documentId,
            mode = if (docState == DocState.ERROR) "error" else "reader",
            chapter = getCurrentReaderChapter(),
        )
        val updated = buildJsonObject {
            rebuilt.forEach { (key, value) -> put(key, value) }
            oldConfig["initialContent"]?.let { put("initialContent", it) }
        }
        readerConfigJson = updated
        executeReaderCommand(readerCommand("document.configure", updated))
    }

    private fun currentTtsState(): TtsPlaybackState = when {
        ttsController.isPaused() -> TtsPlaybackState.PAUSED
        pendingTtsAutoStartOnLoad || ttsController.isTtsAutoPlay ||
            ttsController.isSpeaking() || ttsController.isStarting() ->
            TtsPlaybackState.PLAYING
        else -> TtsPlaybackState.STOPPED
    }

    private fun patchReaderState(patch: JsonObject, event: String? = null) {
        executeReaderCommand(
            readerCommand(
                "document.patchState",
                buildJsonObject {
                    put("patch", patch)
                    event?.let { put("event", it) }
                },
            ),
        )
    }

    fun onMenuVisibilityChanged(visible: Boolean) {
        val chromeVisible = visible || activity.isFindInPageOpen()
        chromeMenuVisible = chromeVisible
        patchReaderState(
            buildJsonObject {
                put("menuVisible", chromeVisible)
                put("immersive", !chromeVisible)
            },
            event = "menuvisibilitychange",
        )
    }

    fun onChapterNavigate(direction: String) {
        if (direction in setOf("next", "previous")) {
            patchReaderState(JsonObject(emptyMap()), event = "chapternavigate:$direction")
        }
    }

    suspend fun scrollToLoadedChapter(chapterId: Long): Boolean {
        val loaded = withContext(Dispatchers.Main.immediate) {
            !isDestroyed && chapterQueue.contains(chapterId)
        }
        if (!loaded) return false

        val scrolled = withTimeoutOrNull(1_000L) {
            suspendCancellableCoroutine { continuation ->
                activity.runOnUiThread {
                    if (isDestroyed) {
                        continuation.resume(false)
                        return@runOnUiThread
                    }
                    try {
                        executeReaderCommand(NovelWebViewReadingCommands.revealChapter(chapterId)) { result ->
                            if (continuation.isActive) continuation.resume(result == "true")
                        }
                    } catch (_: Throwable) {
                        if (continuation.isActive) {
                            continuation.resume(false)
                        }
                    }
                }
            }
        } ?: false
        if (!scrolled) return false

        return withContext(Dispatchers.Main.immediate) {
            val index = chapterQueue.indexOf(chapterId)
            val chapter = loadedChapters.getOrNull(index) ?: return@withContext false
            currentChapterIndex = index
            currentPage = chapter.pages?.firstOrNull() ?: currentPage
            updateReaderDocumentConfig()
            true
        }
    }

    private fun dispatchLoadingChapter(loading: Boolean) {
        patchReaderState(buildJsonObject { put("loadingChapter", loading) }, event = "chapterloading")
    }

    private fun dispatchTtsState() {
        val state = currentTtsState()
        _ttsPlaybackState.value = state
        activity.onNovelTtsStateChanged()
        patchReaderState(buildJsonObject { put("ttsState", state.wireValue) }, event = "ttsstatechange")
    }

    // Re-applies the current menu flag to a fresh DOM without changing page dimensions.
    private fun pushReaderChrome() {
        patchReaderState(
            buildJsonObject {
                put("menuVisible", chromeMenuVisible)
                put("immersive", !chromeMenuVisible)
            },
            event = "menuvisibilitychange",
        )
    }

    private fun showLoadingIndicator(message: String = activity.stringResource(MR.strings.loading)) {
        activity.closeFindInPage(this)
        readerBridge.endDocument()
        pendingErrorFeedback = null

        val (backgroundColor, _) = getThemeColors(preferences.novelTheme.get())
        val loadingHtml = NovelWebViewLoadingSkeleton.buildHtml(
            style = NovelWebViewLoadingSkeleton.Style(
                backgroundColor = backgroundColor,
                fontSize = preferences.novelFontSize.get(),
                lineHeight = preferences.novelLineHeight.get(),
                marginLeft = preferences.novelMarginLeft.get(),
                marginRight = preferences.novelMarginRight.get(),
                marginTop = preferences.novelMarginTop.get(),
                marginBottom = preferences.novelMarginBottom.get(),
            ),
            message = message,
        )

        docState = DocState.LOADING
        expectingBridgeDocumentStart = false
        webView.loadDataWithBaseURL(null, loadingHtml, "text/html", "UTF-8", null)
    }

    private fun displayError(error: Throwable) {
        activity.closeFindInPage(this)
        inlineFeedback.clear()
        pagedController.disable()
        val formatted = ErrorFormatter.format(error)
        logcat(LogPriority.ERROR) { "NovelWebViewViewer: Chapter load failed\n${formatted.stackTrace}" }
        docState = DocState.ERROR
        protectedMediaPlaybackArmed = false
        protectedMediaPlaybackOrigin = null

        val baseUrl = NovelWebViewChapterMeta.READER_DOCUMENT_BASE_URL
        val documentId = try {
            readerBridge.beginDocument(baseUrl, emptyList())
        } catch (unsupported: IllegalStateException) {
            // The provider floor is mandatory; report it natively without retrying the same bridge.
            pendingErrorFeedback = null
            stopAutoScroll()
            activity.toast(unsupported.message ?: formatted.summary)
            return
        }
        val config = buildReaderConfig(documentId, "error", getCurrentReaderChapter())
        readerConfigJson = config
        val retryLabel = activity.stringResource(MR.strings.action_retry)
        val copyLabel = activity.stringResource(MR.strings.action_copy_to_clipboard)
        pendingErrorFeedback = buildJsonObject {
            put("severity", "error")
            put("presentation", "fullpage")
            put("category", formatted.category.localized(activity))
            put("summary", formatted.summary)
            put("retryLabel", retryLabel)
            put("copyLabel", copyLabel)
            put("detailsLabel", activity.stringResource(MR.strings.action_show_errors))
            put(
                "traceBase64",
                android.util.Base64.encodeToString(
                    formatted.stackTrace.toByteArray(Charsets.UTF_8),
                    android.util.Base64.NO_WRAP,
                ),
            )
        }
        val html = NovelWebViewDocumentBuilder.assembleErrorDocument(
            documentId,
            config,
            ThemeUtils.getThemeTokens(activity, preferences, preferences.novelTheme.get()),
        )
        val (backgroundColor, _) = getThemeColors(preferences.novelTheme.get())
        webView.setBackgroundColor(backgroundColor)
        container.setBackgroundColor(backgroundColor)
        rearmAutoScrollOnErrorPage = isAutoScrolling
        expectingBridgeDocumentStart = true
        webView.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)
    }
    override fun moveToPage(page: ReaderPage) {
    }

    fun openFindInPage(onResult: (Int, Int, Boolean) -> Unit) {
        webView.setFindListener(
            WebView.FindListener { activeMatchOrdinal, numberOfMatches, isDoneCounting ->
                onResult(activeMatchOrdinal, numberOfMatches, isDoneCounting)
            },
        )
    }

    fun findInPage(query: String) {
        activeFindQuery = query
        if (query.isEmpty()) {
            webView.clearMatches()
        } else {
            webView.findAllAsync(query)
        }
    }

    fun findNext(forward: Boolean) {
        if (activeFindQuery.isNotEmpty()) {
            webView.findNext(forward)
        }
    }

    fun closeFindInPage() {
        activeFindQuery = ""
        webView.clearMatches()
        webView.setFindListener(null)
    }

    private fun refreshFindInPage() {
        if (activeFindQuery.isNotEmpty()) {
            webView.findAllAsync(activeFindQuery)
        }
    }

    override fun handleKeyEvent(event: KeyEvent): Boolean {
        val isUp = event.action == KeyEvent.ACTION_UP

        when (event.keyCode) {
            KeyEvent.KEYCODE_MENU -> {
                if (isUp) activity.toggleMenu()
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_UP,
            -> {
                if (!preferences.novelVolumeKeysScroll.get()) return false
                if (!isUp) {
                    var direction = if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) 1 else -1
                    if (pagedController.enabled && preferences.novelVolumeKeysInverted.get()) direction = -direction
                    val distance = preferences.novelVolumeKeysScrollDistance.get().coerceIn(
                        ReaderPreferences.VOLUME_KEY_SCROLL_DISTANCE_MIN,
                        ReaderPreferences.VOLUME_KEY_SCROLL_DISTANCE_MAX,
                    )
                    pageScrollBy(direction, distance / 100.0)
                }
                return true
            }
            KeyEvent.KEYCODE_SPACE -> {
                if (!isUp) {
                    if (pagedController.enabled) {
                        pageScrollBy(if (event.isShiftPressed) -1 else 1)
                    } else if (event.isShiftPressed) {
                        webView.pageUp(false)
                    } else {
                        webView.pageDown(false)
                    }
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                if (!isUp) {
                    if (pagedController.enabled) pageScrollBy(-1) else webView.pageUp(false)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                if (!isUp) {
                    if (pagedController.enabled) pageScrollBy(1) else webView.pageDown(false)
                }
                return true
            }
        }
        return false
    }

    fun toggleEditMode(isEditing: Boolean, save: Boolean = true) {
        if (!isEditing && !save) {
            this.isEditingMode = false
            executeReaderCommand(readerCommand("edit.discard"))
            executeReaderCommand(NovelWebViewReadingCommands.blurSelection())
            webView.clearFocus()
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(webView.windowToken, 0)
            activity.viewModel.reloadChapter(fromSource = false)
            return
        }

        if (isEditing) styler.setBionicReading(false)
        this.isEditingMode = isEditing
        executeReaderCommand(readerCommand(if (isEditing) "edit.enter" else "edit.exit"))
        patchReaderState(buildJsonObject { put("editing", isEditing) })
        updateChapterMetaJs()
        if (isEditing) styler.injectReaderUi()

        if (isEditing) {
            val restoreRatio = lastSavedProgress
            isRestoringScroll = true
            val token = ++scrollRestoreToken
            webView.post {
                activity.window.decorView.clearFocus()
                webView.requestFocus()
                webView.requestFocusFromTouch()
                val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(webView, 0)
                webView.postDelayed({ imm?.showSoftInput(webView, 0) }, 120)
            }
            webView.postDelayed({
                if (token != scrollRestoreToken) return@postDelayed
                executeReaderCommand(
                    readerCommand(
                        "scroll.seekVertical",
                        buildJsonObject {
                            put("percent", (restoreRatio * 100).toInt().coerceIn(0, 100))
                        },
                    ),
                )
                liftRestoreGuard(token)
            }, 220)
        } else {
            isRestoringScroll = true
            val token = ++scrollRestoreToken
            executeReaderCommand(NovelWebViewReadingCommands.blurSelection())
            webView.clearFocus()
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(webView.windowToken, 0)
            liftRestoreGuard(token)
        }
    }
    override fun handleGenericMotionEvent(event: MotionEvent): Boolean = false

    private fun setJsLoadingNext() = executeReaderCommand(
        readerCommand("chapter.setLoadingNext", buildJsonObject { put("loading", false) }),
    )

    private fun setJsNoMoreChapters(value: Boolean) = executeReaderCommand(
        readerCommand("chapter.setNoMore", buildJsonObject { put("value", value) }),
    )

    private fun markNextDocumentNavigationBoundary() {
        nextRequiresDocumentNavigation = true
        setJsNoMoreChapters(true)
    }

    private fun liftRestoreGuard(token: Int) {
        if (token != scrollRestoreToken) return
        isRestoringScroll = false
        resetChapterTracking()
    }

    private fun resetChapterTracking() {
        executeReaderCommand(readerCommand("chapter.resetTracking"))
    }

    private suspend fun awaitPageText(page: ReaderPage, loader: PageLoader, timeoutMs: Long): Boolean =
        NovelPageLoader.awaitPageText("NovelWebViewViewer", page, loader, timeoutMs, scope)

    private suspend fun appendContentImmediate(
        chapter: ReaderChapter,
        page: ReaderPage,
    ): Boolean {
        if (isDestroyed) return false

        val rawContent = page.text
        if (rawContent.isNullOrBlank()) {
            showNextChapterError(Exception(activity.stringResource(TDMR.strings.novel_error_empty_chapter)))
            return false
        }

        val chapterId = chapter.chapter.id ?: return false

        val prepared = prepareChapterContent(chapter, page, rawContent, isAppend = true)
        if (prepared.directives.isVideo || prepared.directives.noPrefetch) {
            markNextDocumentNavigationBoundary()
            return false
        }

        return withContext(Dispatchers.Main) {
            if (isDestroyed || !isInfiniteScrollEnabled()) return@withContext false
            // Queue add and DOM insert share one guard so the same chapter cannot be inserted twice.
            if (!loadedChapterIds.contains(chapterId)) {
                chapterQueue.append(chapter)
                readerBridge.updateChapters(loadedChapterIds)
                appendHtmlContent(
                    prepared.processed,
                    chapterId,
                    chapter.chapter.name,
                    chapter.chapter.chapter_number,
                    chapter.chapter.url,
                    prepared.direction,
                    prepared.language,
                )
            }
            if (prepared.directives.noCache) page.text = null
            true
        }
    }

    /** Native speech progress keeps read-ahead running even when WebView frames are suspended. */
    private fun preFetchNextChapterForTts() {
        if (!ttsController.isTtsAutoPlay || !isInfiniteScrollEnabled() || !webChapterContentReady ||
            webChapterIsError || isLoadingNext || ttsChapterJob?.isActive == true ||
            reachedNovelEnd || nextRequiresDocumentNavigation
        ) {
            return
        }
        val index = ttsController.ttsPlaybackChapterIndex
        if (loadedChapters.getOrNull(index + 1) != null) return
        val anchor = loadedChapters.getOrNull(index) ?: currentChapters?.currChapter ?: return
        val anchorId = anchor.chapter.id ?: return
        val progress = ttsController.ttsCurrentChunkIndex.toFloat() / ttsController.ttsChunks.size.coerceAtLeast(1)
        val threshold = preferences.novelAutoLoadNextChapterAt.get().coerceIn(0, 100) / 100f
        handoffState.prefetch(anchorId, progress, threshold) {
            try {
                val cached = withTimeout(30_000L) {
                    val chapter =
                        activity.viewModel.prepareNextChapterForInfiniteScroll(anchor) ?: return@withTimeout null
                    val page = chapter.pages?.firstOrNull() ?: return@withTimeout null
                    val loader = chapter.pageLoader ?: return@withTimeout null
                    if (!awaitPageText(page, loader, 30_000)) return@withTimeout null
                    chapter to page
                } ?: return@prefetch null
                if (activity.isTranslationEnabled() && translationPreferences.autoTranslateNextChapter().get()) {
                    activity.viewModel.translateChapterAhead(cached.first, cached.first.chapter.id!!)
                }
                cached
            } catch (e: TimeoutCancellationException) {
                logcat(LogPriority.WARN) { "TTS chapter prefetch timed out" }
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN) { "TTS chapter prefetch failed: ${e.javaClass.simpleName}" }
                null
            }
        }
    }

    private fun cancelTtsChapterPreparation() {
        handoffState.cancel()
        ttsChapterJob?.cancel()
        ttsChapterJob = null
    }

    private fun startNextChapterAppend() {
        if (isDestroyed || isLoadingNext || nextLoadRequiresManualRetry || reachedNovelEnd ||
            nextRequiresDocumentNavigation || !isInfiniteScrollEnabled() || !webChapterContentReady || webChapterIsError
        ) {
            return
        }
        isLoadingNext = true
        appendJob = scope.launch {
            try {
                appendNextChapterIfAvailable()
            } finally {
                isLoadingNext = false
                setJsLoadingNext()
            }
        }
    }

    private fun showNextChapterError(error: Throwable) {
        nextLoadRequiresManualRetry = true
        inlineFeedback.showInlineError(error, chapterId = loadedChapters.lastOrNull()?.chapter?.id) {
            nextLoadRequiresManualRetry = false
            startNextChapterAppend()
        }
    }

    /** All append callers, including TTS, preserve the current document on a load failure. */
    private suspend fun appendNextChapterIfAvailable(): Boolean = try {
        val appended = appendNextChapter()
        if (appended && nextLoadRequiresManualRetry) {
            nextLoadRequiresManualRetry = false
            inlineFeedback.clear()
        }
        appended
    } catch (e: TimeoutCancellationException) {
        showNextChapterError(
            java.util.concurrent.TimeoutException(
                activity.stringResource(TDMR.strings.novel_error_timeout_next_chapter),
            )
                .initCause(e),
        )
        false
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        showNextChapterError(e)
        false
    }

    private suspend fun appendNextChapter(): Boolean {
        val anchor = loadedChapters.lastOrNull() ?: currentChapters?.currChapter ?: run {
            throw IllegalStateException(activity.stringResource(TDMR.strings.novel_error_no_anchor_chapter))
        }
        val cached = anchor.chapter.id?.let { handoffState.take(it) }
        if (cached != null) {
            val (preparedChapter, page) = cached
            val nextId = preparedChapter.chapter.id ?: return false
            if (!loadedChapterIds.contains(nextId)) {
                logcat(LogPriority.DEBUG) {
                    "NovelWebViewViewer: using pre-fetched chapter $nextId (${preparedChapter.chapter.name})"
                }
                try {
                    if (!appendContentImmediate(preparedChapter, page)) {
                        return false
                    }
                    logcat(LogPriority.INFO) {
                        "NovelWebViewViewer: Successfully appended pre-fetched chapter ${preparedChapter.chapter.name}"
                    }
                } finally {
                    setJsLoadingNext()
                }
            }
            // Already loaded counts as success; the caller still advances TTS onto it.
            return true
        }

        logcat(LogPriority.DEBUG) {
            "NovelWebViewViewer: appendNext starting from anchor=${anchor.chapter.id}/${anchor.chapter.name}"
        }

        val preparedChapter = withTimeout(30_000L) {
            activity.viewModel.prepareNextChapterForInfiniteScroll(anchor)
        } ?: run {
            logcat(LogPriority.WARN) { "NovelWebViewViewer: No next chapter available after ${anchor.chapter.name}" }
            if (activity.viewModel.hasNextPagedPage(anchor)) {
                throw IllegalStateException(activity.stringResource(TDMR.strings.novel_error_couldnt_load_next_chapter))
            } else {
                // Surface once, then latch so the scroll handler stops re-triggering at the last chapter.
                if (!reachedNovelEnd) {
                    inlineFeedback.showInfo(
                        activity.stringResource(MR.strings.transition_no_next),
                        chapterId = anchor.chapter.id,
                    )
                }
                reachedNovelEnd = true
                setJsNoMoreChapters(true)
            }
            return false
        }
        val nextId = preparedChapter.chapter.id ?: run {
            logcat(LogPriority.ERROR) { "NovelWebViewViewer: prepared next chapter has null id" }
            throw IllegalStateException(activity.stringResource(TDMR.strings.novel_error_chapter_no_id))
        }
        logcat(LogPriority.DEBUG) { "NovelWebViewViewer: prepared next=$nextId/${preparedChapter.chapter.name}" }

        if (loadedChapterIds.contains(nextId)) {
            logcat(LogPriority.DEBUG) { "NovelWebViewViewer: next chapter $nextId already loaded, skipping" }
            return true
        }

        val page = preparedChapter.pages?.firstOrNull() ?: run {
            logcat(LogPriority.ERROR) { "NovelWebViewViewer: No page in prepared next chapter" }
            throw (preparedChapter.state as? ReaderChapter.State.Error)?.error
                ?: IllegalStateException(activity.stringResource(TDMR.strings.novel_error_no_page_in_next_chapter))
        }
        val loader = page.chapter.pageLoader ?: run {
            logcat(LogPriority.ERROR) { "NovelWebViewViewer: No page loader for next chapter" }
            throw IllegalStateException(activity.stringResource(TDMR.strings.novel_error_no_loader_next_chapter))
        }

        try {
            logcat(LogPriority.DEBUG) {
                "NovelWebViewViewer: loading page for next chapter $nextId, state=${page.status}"
            }
            val loaded = try {
                awaitPageText(page = page, loader = loader, timeoutMs = 30_000)
            } catch (e: TimeoutCancellationException) {
                logcat(LogPriority.ERROR) { "NovelWebViewViewer: Timed out loading next chapter page after 30s" }
                throw java.util.concurrent.TimeoutException(
                    activity.stringResource(TDMR.strings.novel_error_timeout_next_chapter),
                ).initCause(e)
            }

            if (!loaded) {
                throw (page.status as? Page.State.Error)?.error
                    ?: IllegalStateException(activity.stringResource(TDMR.strings.novel_error_empty_chapter))
            }

            logcat(LogPriority.DEBUG) {
                "NovelWebViewViewer: appending content for chapter $nextId ts=${System.currentTimeMillis()} ttsCurrentChunkIndex=${ttsController.ttsCurrentChunkIndex} ttsResumeChunkIndex=${ttsController.ttsResumeChunkIndex} ttsPlaybackChapterIndex=${ttsController.ttsPlaybackChapterIndex} ttsPlaybackChapterId=${ttsController.ttsPlaybackChapterId}"
            }
            if (!appendContentImmediate(preparedChapter, page)) {
                return false
            }
            logcat(LogPriority.INFO) {
                "NovelWebViewViewer: Successfully appended next chapter ${preparedChapter.chapter.name}"
            }
            nextRequiresDocumentNavigation = false
            return true
        } finally {
            setJsLoadingNext()
        }
    }

    fun scrollToTop() {
        if (pagedController.enabled) {
            pagedController.seekUnit(0)
            return
        }
        webView.scrollTo(0, 0)
    }

    private fun pageScrollBy(direction: Int, fraction: Double = 0.75) {
        if (pagedController.enabled) {
            val effect = pagedController.effectiveEffect()
            when (effect) {
                NovelPageEffect.NONE -> pagedController.moveVisualUnit(direction)
                else -> startTimedPageTurn(direction, effect)
            }
            return
        }
        executeReaderCommand(NovelWebViewReadingCommands.scrollByViewport(direction, fraction.toFloat()))
    }

    private fun startTimedPageTurn(delta: Int, effect: NovelPageEffect) {
        curlController.startTimed(delta, curlReadingDirection(), pagedController.isDoubleSpread(), effect)
    }

    private fun requestCurlTarget(delta: Int, onReady: (Boolean) -> Unit) {
        val turnDelta = if (delta < 0) -1 else 1
        curlPreviewMoved = false
        executeReaderCommand(NovelWebViewReadingCommands.prepareCurl(turnDelta)) { moved ->
            curlPreviewMoved = moved == "true"
            if (curlPreviewMoved) awaitWebViewFrame { onReady(true) } else onReady(false)
        }
    }

    private fun isRtlPageFlow(): Boolean =
        preferences.novelReadingLayout.get() == NovelReadingLayout.VERTICAL ||
            currentDocumentDirection == NovelContentDirection.RTL

    private fun curlReadingDirection() = if (isRtlPageFlow()) {
        NovelPageCurlReadingDirection.RTL
    } else {
        NovelPageCurlReadingDirection.LTR
    }

    private fun finishCurlPreview(onFinished: () -> Unit = {}) {
        executeReaderCommand(NovelWebViewReadingCommands.finishCurl(commit = true)) { awaitWebViewFrame(onFinished) }
    }

    private fun rollbackCurlPreview(onRestored: () -> Unit = {}) {
        if (!curlPreviewMoved) {
            onRestored()
            return
        }
        curlPreviewMoved = false
        executeReaderCommand(NovelWebViewReadingCommands.ROLLBACK_CURL) {
            awaitWebViewFrame(onRestored)
        }
    }

    private fun awaitWebViewFrame(onReady: () -> Unit) {
        finishAfterVisualState(
            awaitVisualState = { ready ->
                webView.postVisualStateCallback(
                    System.nanoTime(),
                    object : WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) = ready()
                    },
                )
            },
            postFrame = { ready -> webView.postOnAnimation { ready() } },
            finish = onReady,
        )
    }

    fun toggleAutoScroll() {
        if (isAutoScrolling) stopAutoScroll() else startAutoScroll()
    }

    private fun startAutoScroll() {
        isAutoScrolling = true
        autoScrollStartAttempt = 0
        issueAutoScrollStart(++autoScrollSession)
    }

    private fun issueAutoScrollStart(session: Int) {
        if (pagedController.enabled) {
            val delayMs = preferences.novelAutoPageIntervalSeconds.get().coerceIn(2, 60) * 1_000L
            webView.postDelayed({
                if (isDestroyed || !isAutoScrolling || session != autoScrollSession) return@postDelayed
                if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    issueAutoScrollStart(session)
                    return@postDelayed
                }
                val position = pagedController.position.value
                if (reachedNovelEnd && position != null && position.unitIndex == position.unitCount - 1) {
                    stopAutoScroll()
                    return@postDelayed
                }
                pageScrollBy(1)
                issueAutoScrollStart(session)
            }, delayMs)
            return
        }
        // Pref is half-steps (speed x2); level is 1.0..10.0 in 0.5 increments.
        val pxPerSec = (preferences.novelAutoScrollLevel() * 20).toInt()
        val attempt = ++autoScrollStartAttempt
        executeReaderCommand(NovelWebViewReadingCommands.startVerticalAutoScroll(pxPerSec))
        webView.postDelayed({
            if (!isAutoScrolling || session != autoScrollSession || attempt != autoScrollStartAttempt) {
                return@postDelayed
            }
            executeReaderCommand(NovelWebViewReadingCommands.autoScrollStatus()) { running ->
                if (!isAutoScrolling || session != autoScrollSession || attempt != autoScrollStartAttempt) {
                    return@executeReaderCommand
                }
                if (running != "true") {
                    if (autoScrollStartAttempt < AUTO_SCROLL_MAX_START_ATTEMPTS) {
                        issueAutoScrollStart(session)
                    } else {
                        isAutoScrolling = false
                        logcat(LogPriority.WARN) { "NovelWebViewViewer: autoscroll failed to start, giving up" }
                    }
                }
            }
        }, AUTO_SCROLL_START_VERIFY_MS)
    }

    fun stopAutoScroll() {
        isAutoScrolling = false
        ++autoScrollSession
        executeReaderCommand(NovelWebViewReadingCommands.STOP_AUTO_SCROLL)
    }

    fun isAutoScrollActive(): Boolean = isAutoScrolling

    fun onTrimMemory() = curlController.destroy()

    fun getProgressPercent(): Int = NovelProgress.progressToPercent(lastSavedProgress)

    fun isPagedMode(): Boolean = pagedController.enabled

    fun setPagedUnit(unitIndex: Int) {
        stopAutoScroll()
        pagedController.seekUnit(unitIndex)
    }

    fun setProgressPercent(percent: Int) {
        val progress = percent.coerceIn(0, 100)
        lastSavedProgress = progress / 100f
        awaitingFirstScrollSample = false
        lastUserSeekAt = System.currentTimeMillis()

        if (pagedController.enabled) {
            pagedController.seekPercent(progress)
            return
        }

        executeReaderCommand(NovelWebViewReadingCommands.seekVertical(progress))
    }

    /**
     * Drops the loaded-chapter queue so the next [setChapters] re-renders the chapter instead of
     * taking the already-loaded early return.
     */
    fun invalidateLoadedChapters() {
        cancelTtsChapterPreparation()
        chapterQueue.clear()
        readerBridge.updateChapters(emptyList())
    }

    fun reloadChapter() {
        val chapters = currentChapters ?: return
        invalidateLoadedChapters()
        setChapters(chapters)
    }

    private fun ensureTtsInitialized() {
        ttsController.ensureInitialized()
    }

    fun setTtsEnabled(enabled: Boolean) {
        if (!enabled && isTtsActive()) stopTts(preserveChapterLoad = true)
        styler.setTtsEnabled(enabled)
    }

    fun startTts() {
        if (!isTtsEnabled) return
        if (isVideoChapter()) {
            stopTts()
            return
        }
        ensureTtsInitialized()

        if (!ttsController.ttsInitialized) {
            logcat(LogPriority.WARN) { "TTS (WebView): Not initialized yet, waiting..." }
            ttsController.pendingStartRequest = TtsController.StartRequest.NORMAL
            dispatchTtsState()
            return
        }

        ttsController.pendingStartRequest = null
        ttsController.isTtsAutoPlay = true
        dispatchTtsState()
        if (!webChapterContentReady) {
            pendingTtsAutoStartOnLoad = true
            return
        }
        val (chapterIdx, chapterId) = getTtsChapterContext()
        executeReaderCommand(NovelWebViewTtsDomScripts.extractText(chapterId)) { result ->
            if (!isTtsEnabled || !ttsController.isTtsAutoPlay || getTtsChapterContext().second != chapterId) {
                return@executeReaderCommand
            }
            val text = unescapeJsResult(result ?: return@executeReaderCommand)

            if (text.isNotBlank() && text != "null") {
                logcat(LogPriority.DEBUG) { "TTS (WebView): Starting to speak ${text.length} characters" }
                ttsController.speak(text, chapterIdx, chapterId)

                preFetchNextChapterForTts()
                dispatchTtsState()
            } else {
                logcat(LogPriority.WARN) { "TTS (WebView): No text to speak" }
            }
        }
    }

    fun stopTts(preserveChapterLoad: Boolean = false) {
        logcat(LogPriority.DEBUG) {
            "TTS (WebView): stopTts called ts=${System.currentTimeMillis()} currentChapterIndex=$currentChapterIndex, ttsCurrentChunkIndex=${ttsController.ttsCurrentChunkIndex}, ttsResumeChunkIndex=${ttsController.ttsResumeChunkIndex}, ttsPlaybackChapterIndex=${ttsController.ttsPlaybackChapterIndex}, ttsPlaybackChapterId=${ttsController.ttsPlaybackChapterId}"
        }
        pendingTtsAutoStartOnLoad = false
        pendingTtsParagraphIndex = null
        // Drop a pending real-load signal so a stale onPageFinished after stop can't inject; leave a
        // committed READY/ERROR document intact.
        if (!preserveChapterLoad && docState == DocState.LOADING_REAL) docState = DocState.LOADING
        ttsController.stop()
        cancelTtsChapterPreparation()
        dispatchTtsState()
        // An active append owns the loading latch and releases it when it completes.
        if (appendJob?.isActive != true) {
            isLoadingNext = false
            setJsLoadingNext()
        }
        // Don't clear the end-of-novel latch if it's already set: loadNextChapterForTts() calls
        // stopTts() right after appendNextChapterIfAvailable() sets reachedNovelEnd on a genuine
        // "no next chapter" result, and resetting it here would let the next scroll event re-fetch
        // and re-show the "No next chapter available" error a second time.
        if (!reachedNovelEnd) {
            setJsNoMoreChapters(false)
        }
    }

    fun pauseTts() {
        logcat(LogPriority.DEBUG) {
            "TTS (WebView): pauseTts called ts=${System.currentTimeMillis()} currentChapterIndex=$currentChapterIndex, ttsCurrentChunkIndex=${ttsController.ttsCurrentChunkIndex}, ttsResumeChunkIndex=${ttsController.ttsResumeChunkIndex}, ttsPlaybackChapterIndex=${ttsController.ttsPlaybackChapterIndex}, ttsPlaybackChapterId=${ttsController.ttsPlaybackChapterId}"
        }
        ttsController.pause()
        dispatchTtsState()
    }

    fun resumeTts() {
        if (!isTtsEnabled) return
        ttsController.resume()
        dispatchTtsState()
    }

    fun ttsNextParagraph() {
        stepTtsParagraph(1)
    }

    fun ttsPreviousParagraph() {
        stepTtsParagraph(-1)
    }

    private fun stepTtsParagraph(delta: Int) {
        if (!isTtsEnabled) return
        ttsController.stepParagraph(delta) { startTtsFromViewport() }
        dispatchTtsState()
    }

    private fun getTtsChapterContext(): Pair<Int, Long?> {
        val activeChapter = getCurrentReaderChapter()
            ?: currentPage?.chapter
            ?: return Pair(currentChapterIndex, null)
        return Pair(
            currentChapterIndex,
            activeChapter.chapter.id ?: currentPage?.chapter?.chapter?.id,
        )
    }

    fun isTtsPaused(): Boolean = ttsController.isPaused()

    fun isTtsSpeaking(): Boolean = ttsController.isSpeaking()

    /**
     * High-level "TTS session active" flag for the background-notification
     * sync. Includes a pending document handoff so resetting the speech queue
     * while the next chapter loads cannot tear down the foreground service.
     */
    fun isTtsActive(): Boolean = currentTtsState() != TtsPlaybackState.STOPPED

    /** (paragraphIndex, paragraphCount) for the media notification's "paragraph N of M". */
    fun getTtsParagraphProgress(): Pair<Int, Int> = ttsController.getParagraphProgress()

    /** Media-notification seek-bar drag: jump straight to [paragraphIndex]. */
    fun seekTtsToParagraph(paragraphIndex: Int) {
        if (!isTtsEnabled) return
        ttsController.seekToParagraph(paragraphIndex)
        dispatchTtsState()
    }

    fun startTtsFromViewport() {
        if (!isTtsEnabled) return
        if (isVideoChapter()) {
            stopTts()
            return
        }
        ensureTtsInitialized()

        if (!ttsController.ttsInitialized) {
            logcat(LogPriority.WARN) { "TTS (WebView): Not initialized yet" }
            pendingTtsParagraphIndex = null
            ttsController.pendingStartRequest = TtsController.StartRequest.VIEWPORT
            dispatchTtsState()
            return
        }

        ttsController.pendingStartRequest = null
        if (!webChapterContentReady) {
            // Still loading; defer so onPageFinished re-runs the viewport start once content is in.
            pendingTtsParagraphIndex = null
            ttsController.pendingStartRequest = TtsController.StartRequest.VIEWPORT
            dispatchTtsState()
            return
        }
        val chapterId = getTtsChapterContext().second
        executeReaderCommand(
            NovelWebViewTtsDomScripts.firstVisibleParagraph(chapterId),
        ) { rawIndex ->
            if (rawIndex == null) return@executeReaderCommand
            val firstVisibleParagraphIndex = rawIndex.trim('"').toIntOrNull() ?: 0
            startTtsAtParagraph(firstVisibleParagraphIndex)
        }
    }

    /**
     * Resolves the element the TTS icon was dropped on to an index in the list TTS actually reads,
     * then starts there. The reader UI only tags the element: it has its own notion of a readable
     * block, and resolving the index there produced one that did not line up with playback.
     *
     * A drop target the reader counts but TTS does not - a `<p>` holding an image, a `<pre>` - walks
     * up to the nearest block that TTS does count, which is the one that will be spoken.
     */
    private fun startTtsAtTaggedParagraph() {
        val chapterId = getTtsChapterContext().second
        executeReaderCommand(NovelWebViewTtsDomScripts.taggedParagraph(chapterId)) { result ->
            if (result == null) return@executeReaderCommand
            val index = result.trim().trim('"').toIntOrNull() ?: -1
            if (index >= 0) startTtsAtParagraph(index)
        }
    }

    private fun startTtsAtParagraph(index: Int) {
        if (!isTtsEnabled) return
        if (isVideoChapter()) {
            stopTts()
            return
        }
        ensureTtsInitialized()

        if (!ttsController.ttsInitialized) {
            pendingTtsParagraphIndex = index.coerceAtLeast(0)
            ttsController.pendingStartRequest = TtsController.StartRequest.VIEWPORT
            dispatchTtsState()
            return
        }

        if (!webChapterContentReady) {
            pendingTtsParagraphIndex = index.coerceAtLeast(0)
            ttsController.pendingStartRequest = TtsController.StartRequest.VIEWPORT
            dispatchTtsState()
            return
        }

        pendingTtsParagraphIndex = null
        ttsController.pendingStartRequest = null
        ttsController.isTtsAutoPlay = true
        if (!ttsController.isPaused()) dispatchTtsState()
        val (chapterIdx, chapterId) = getTtsChapterContext()
        executeReaderCommand(NovelWebViewTtsDomScripts.extractText(chapterId)) { result ->
            if (!isTtsEnabled || !ttsController.isTtsAutoPlay || getTtsChapterContext().second != chapterId) {
                return@executeReaderCommand
            }
            val text = unescapeJsResult(result ?: return@executeReaderCommand)
            if (text.isBlank() || text == "null") {
                logcat(LogPriority.WARN) { "TTS (WebView): No text available for selected paragraph" }
                stopTts()
                return@executeReaderCommand
            }
            ttsController.ttsViewportParagraphIndex = index.coerceAtLeast(0)
            ttsController.hasViewportStartOverride = true
            ttsController.speak(text, chapterIdx, chapterId)
            preFetchNextChapterForTts()
            dispatchTtsState()
        }
    }

    /**
     * Get the currently selected text from the WebView
     */
    fun getSelectedText(): String? {
        return pendingSelectedText
    }

    /**
     * Get the current chapter name for quote context
     */
    fun getCurrentChapterName(): String? {
        val loaded = loadedChapters.getOrNull(currentChapterIndex) ?: return null
        return loaded.chapter.name
    }

    /**
     * Clear text selection in the WebView
     */
    fun clearTextSelection() {
        executeReaderCommand(NovelWebViewReadingCommands.clearSelection())
    }

    /**
     * Handle the "Remember" action from text selection menu
     */
    private fun onRememberSelectedText(actionMode: ActionMode? = null) {
        executeReaderCommand(NovelWebViewReadingCommands.readSelectionAnchor()) { result ->
            actionMode?.finish()
            val value = result?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            val selectedText = value?.string("text")?.trim()?.ifEmpty { null }
            val paragraphIndex = value?.int("paragraphIndex")?.takeIf { it >= 0 }
            if (!selectedText.isNullOrBlank()) {
                pendingSelectedText = selectedText
                pendingParagraphIndex = paragraphIndex
                activity.onRememberSelectedText()
                clearTextSelection()
            } else {
                activity.toast(activity.stringResource(TDMR.strings.reader_no_text_selected))
            }
        }
    }
}

/** Who owns the current touch, as claimed page-side by `the reader gesture module`. */
internal enum class ReaderGestureTarget {
    /** Media, embedded content, links, form controls, reader chrome — or nothing claimed at all. */
    BLOCKED,

    /** Inert reader surface: prose, background, the document itself. */
    SURFACE,

    /** An inline image with no interactive ancestor. */
    IMAGE,
    ;

    companion object {
        /** Unknown wire values fail closed, so a stray claim can never unlock reader actions. */
        fun fromWire(owner: String?): ReaderGestureTarget = when (owner) {
            "surface" -> SURFACE
            "image" -> IMAGE
            else -> BLOCKED
        }
    }
}

internal enum class ReaderTapAction { NONE, TOGGLE_MENU, TAP_ZONES }

// `navigationModeNovel` is the only switch over tap zones: its disabled mode resolves to
// DisabledNavigation, whose getAction maps every point to MENU. Do not gate this on a second
// preference - that shadows the tap-zone setting and makes every zone toggle the menu instead.
internal fun ReaderGestureTarget.tapAction(isVideoChapter: Boolean): ReaderTapAction =
    when (this) {
        ReaderGestureTarget.BLOCKED -> ReaderTapAction.NONE
        ReaderGestureTarget.IMAGE -> ReaderTapAction.TOGGLE_MENU
        // A video chapter has no scrollable prose, so its background always means "show chrome".
        ReaderGestureTarget.SURFACE -> when {
            isVideoChapter -> ReaderTapAction.TOGGLE_MENU
            else -> ReaderTapAction.TAP_ZONES
        }
    }

/** Only the inert reader surface may become a chapter swipe; a seek drag must not change chapter. */
internal fun ReaderGestureTarget.allowsChapterSwipe(): Boolean = this == ReaderGestureTarget.SURFACE
