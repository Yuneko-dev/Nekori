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
import android.webkit.JavascriptInterface
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
import androidx.annotation.Keep
import androidx.lifecycle.Lifecycle
import com.hippo.unifile.UniFile
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
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.handleNovelFlingGesture
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.localized
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.CHAPTER_DIVIDER_CLASS
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.CHAPTER_ID_ATTR
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.CHAPTER_TAG_NAME
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.TSUNDOKU_OBJECT_NAME
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
        const val ATTR_DATA_EDITABLE = "data-tsundoku-editable"
        const val ID_EDIT_MODE_STYLE = "edit-mode-style"
        const val SEEK_ECHO_SUPPRESS_MS = 350L
        const val AUTO_SCROLL_START_VERIFY_MS = 400L
        val IMAGE_URL_REGEX = Regex("\\.(?:avif|gif|jpe?g|png|svg|webp)$", RegexOption.IGNORE_CASE)
        const val AUTO_SCROLL_MAX_START_ATTEMPTS = 3
    }

    private val container = FrameLayout(activity)
    private val _ttsPlaybackState = MutableStateFlow(TtsPlaybackState.STOPPED)
    val ttsPlaybackState: StateFlow<TtsPlaybackState> = _ttsPlaybackState.asStateFlow()
    private lateinit var webView: WebView
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
            labels = NovelWebViewSummaryController.Labels(
                title = activity.stringResource(TDMR.strings.chapter_summary_title),
                loading = activity.stringResource(TDMR.strings.chapter_summary_loading),
                regenerate = activity.stringResource(TDMR.strings.action_regenerate),
                close = activity.stringResource(MR.strings.action_close),
                cancel = activity.stringResource(MR.strings.action_cancel),
                cancelled = activity.stringResource(TDMR.strings.chapter_summary_cancelled),
                contentUnavailable = activity.stringResource(TDMR.strings.chapter_summary_no_content),
            ),
            evaluateJs = { js, callback -> evaluateJavascriptSafe(js, callback) },
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
    private var currentDocumentNoPrefetch = false
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

    // Documents without reader-gestures.js (loading skeleton, error page) keep the pre-classifier
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

    // Suppresses auto-append for NEXT_LOAD_RETRY_COOLDOWN_MS after a failure; the JS load guard
    // clears each finally, so without this a chapter that keeps timing out re-fires every frame.
    private var lastNextLoadFailedAt = 0L

    // True while a delayed JS-latch release is queued for the current cooldown, so the JS load latch
    // is held (not re-fired every scroll frame) and released exactly once when the cooldown ends.
    private var cooldownReleaseScheduled = false

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

    private var handoffState: TtsHandoffState<Pair<ReaderChapter, ReaderPage>> = TtsHandoffState.Idle

    private val prefetchCompletedSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

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
        preferences.novelReadingLayout.get() == NovelReadingLayout.PAGED && !currentDocumentIsVideo &&
            ((activity.viewModel.getSource() as? JsSource)?.allowsPagedReading ?: true)

    private val ttsController: TtsController

    private lateinit var styler: NovelWebViewStyler
    private lateinit var pagedController: NovelWebViewPagedController
    private lateinit var curlController: NovelPageCurlController
    private lateinit var curlView: NovelPageCurlView
    private var curlPreviewMoved = false

    private val inlineFeedback by lazy {
        NovelWebViewInlineFeedback(
            scope = scope,
            evaluateJs = { js -> evaluateJavascriptSafe(js, null) },
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
                            pageScrollBy(if (currentDocumentDirection == NovelContentDirection.RTL) 1 else -1)
                        } else {
                            loadedChapters.getOrNull(currentChapterIndex - 1)?.chapter?.id
                                ?.let { chapterId -> scope.launch { scrollToLoadedChapter(chapterId) } }
                        }
                    },
                    onNext = {
                        if (pagedController.enabled) {
                            pageScrollBy(if (currentDocumentDirection == NovelContentDirection.RTL) -1 else 1)
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
                                currentDocumentDirection == NovelContentDirection.RTL
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
                                currentDocumentDirection == NovelContentDirection.RTL
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

                override fun onHighlightChunk(chunkIndex: Int, chunk: String, startOffset: Int, paragraphIndex: Int) {
                    applyTtsHighlight(chunkIndex, paragraphIndex)
                    saveTtsProgressForChunk(chunkIndex)
                }

                override fun onClearHighlights() {
                    clearWebViewTtsHighlight()
                    dispatchTtsState()
                }

                override fun onLastChunkDone() {
                    if (!isTtsEnabled) return
                    val nextAlreadyLoaded = isInfiniteScrollEnabled() &&
                        loadedChapters.getOrNull(ttsController.ttsPlaybackChapterIndex + 1) != null
                    if (nextAlreadyLoaded) {
                        advanceTtsToNextLoadedChapter()
                    } else {
                        loadNextChapterForTts(ttsController.ttsPlaybackChapterIndex)
                    }
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
        // Brand-new-user one-shot: surface the nav layout on first reader open.
        if (config.forceNavigationOverlay && !activity.tapZonesShownInSession) {
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
        evaluateJavascriptSafe(
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
        evaluateJavascriptSafe(NovelWebViewTtsDomScripts.CLEAR_HIGHLIGHT)
    }

    private fun loadNextChapterForTts(_anchorChapterIndex: Int = ttsController.ttsPlaybackChapterIndex) {
        logcat(LogPriority.DEBUG) {
            "TTS (WebView): Auto-loading next chapter ts=${System.currentTimeMillis()} ttsPlaybackChapterIndex=${ttsController.ttsPlaybackChapterIndex} ttsPlaybackChapterId=${ttsController.ttsPlaybackChapterId}"
        }

        scope.launch {
            if (isInfiniteScrollEnabled()) {
                val appended = withTimeoutOrNull(30_000L) { appendNextChapterIfAvailable() }
                if (appended == true) {
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

    private fun advanceTtsToNextLoadedChapter() {
        val currentIdx = ttsController.ttsPlaybackChapterIndex
        val nextIdx = currentIdx + 1
        val nextChapter = loadedChapters.getOrNull(nextIdx) ?: return
        val nextChapterId = nextChapter.chapter.id ?: return

        scope.launch {
            if (!scrollToLoadedChapter(nextChapterId)) {
                stopTts()
                return@launch
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
                    ) ?: return false
                    navigateToEpubChapter(targetUrl)
                    return true
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): WebResourceResponse? {
                    val url = request?.url?.toString() ?: return null
                    assetLoader.intercept(url)?.let { return it }
                    styler.interceptFont(url)?.let { return it }
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
                    protectedMediaPlaybackArmed = false
                    // The new document has not installed reader-gestures.js yet.
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
                    evaluateJavascriptSafe("document.getElementById('lnreader-compat-config') !== null") { result ->
                        if (result == "true" && docState == DocState.LOADING_REAL) finishRealChapterLoad()
                    }
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

            addJavascriptInterface(this@NovelWebViewViewer.WebViewInterface(), "Android")

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

        styler = NovelWebViewStyler(
            context = activity,
            preferences = preferences,
            webView = webView,
            container = container,
            evaluateJs = { js -> evaluateJavascriptSafe(js, null) },
        )
        pagedController = NovelWebViewPagedController(
            context = activity,
            webView = webView,
            preferences = preferences,
            evaluateJs = { js -> evaluateJavascriptSafe(js, null) },
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
                styler.injectScript(isAppend = isAppend, reapplyChangedOnly = true) { buildTsundokuScript() }
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
        val savedProgress = page.chapter.chapter.last_page_read
        val isRead = page.chapter.chapter.read

        val shouldRestore = if (!isRead) {
            savedProgress > 0 && savedProgress <= 100
        } else {
            libraryPreferences.novelReadProgress100.get() && savedProgress > 0 && savedProgress <= 100
        }
        if (pagedController.enabled) {
            val percent = if (shouldRestore) savedProgress.coerceIn(0, 100) else 0
            lastSavedProgress = percent / 100f
            activity.onNovelProgressChanged(lastSavedProgress)
            isRestoringScroll = true
            val token = ++scrollRestoreToken
            evaluateJavascriptSafe(
                """
                requestAnimationFrame(function() {
                    requestAnimationFrame(function() {
                        window.$TSUNDOKU_OBJECT_NAME?.runtime?.readerLayout?.seekPercent?.($percent);
                        window.Android?.onScrollRestoreComplete?.($token);
                    });
                });
                """.trimIndent(),
            )
            webView.postDelayed({ liftRestoreGuard(token) }, 3000)
            return
        }
        if (shouldRestore) {
            val progress = savedProgress / 100f
            lastSavedProgress = progress
            activity.onNovelProgressChanged(progress)
            isRestoringScroll = true
            val token = ++scrollRestoreToken

            // Apply the saved ratio once the content has a scrollable range: immediately if laid
            // out, else a ResizeObserver waits for the body height. onScrollRestoreComplete lifts
            // the guard when done.
            evaluateJavascriptSafe(NovelWebViewReadingCommands.restoreVertical(progress, token))
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
        webView.removeJavascriptInterface("Android")
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

    private fun evaluateJavascriptSafe(js: String, callback: ((String) -> Unit)? = null) {
        if (isDestroyed) return
        activity.runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            try {
                webView.evaluateJavascript(js, callback)
            } catch (t: Throwable) {
                // WebView may already be destroyed; avoid crashing.
                logcat(LogPriority.WARN) { "NovelWebViewViewer: evaluateJavascript ignored (${t.message})" }
            }
        }
    }

    private fun finishRealChapterLoad() {
        docState = DocState.READY

        styler.injectScript { buildTsundokuScript() }
        // A fresh DOM needs the current menu flag before custom reader scripts run.
        pushReaderChrome()
        if (isVideoChapter()) {
            pagedController.disable()
            pendingTtsAutoStartOnLoad = false
            ttsController.pendingStartRequest = null
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
            if (isInfiniteScrollEnabled()) styler.injectScopedChapterAnchors()
            styler.injectScrollTracking(isInfiniteScrollEnabled())
            styler.injectReaderUi()
            restoreScrollPosition()
            syncShortChapterProgressIfNeeded()
            if (isEditingMode) toggleEditMode(true)
        }
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
            activity.saveNovelProgress(page, progressValue)
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

    private fun syncShortChapterProgressIfNeeded() {
        val page = currentPage ?: return
        if (!shouldAutoMarkShortChapter(page)) return
        if (page.status != Page.State.Ready || page.text.isNullOrBlank()) return

        evaluateJavascriptSafe(
            """
            (function() {
                function checkIfShortChapter() {
                    var docHeight = Math.max(
                        document.documentElement.scrollHeight,
                        document.body ? document.body.scrollHeight : 0
                    );
                    var viewport = window.innerHeight || document.documentElement.clientHeight;
                    return docHeight - viewport <= 0;
                }
                var called = false;
                function tryMarkShort() {
                    if (!called && checkIfShortChapter()) {
                        called = true;
                        Android.markChapterAsShort();
                    }
                }
                var resizeObserver = new ResizeObserver(function() {
                    tryMarkShort();
                    if (called) resizeObserver.disconnect();
                });
                resizeObserver.observe(document.body);
                setTimeout(function() {
                    tryMarkShort();
                    resizeObserver.disconnect();
                }, 500);
            })();
            """.trimIndent(),
            null,
        )
    }

    override fun getView(): View = container

    fun reloadWithTranslation() {
        val page = currentPage ?: return
        val chapter = currentChapters?.currChapter ?: return
        val content = page.text ?: run {
            activity.viewModel.reloadChapter(fromSource = true)
            return
        }

        contentJob?.cancel()
        contentJob = scope.launch {
            if (activity.isTranslationEnabled()) loadingIndicator?.show()
            val prepared = prepareChapterContent(chapter, page, content, isAppend = false)
            loadingIndicator?.hide()
            loadHtmlContent(
                prepared.processed,
                chapter,
                prepared.directives,
                prepared.direction,
                prepared.language,
            )
            if (prepared.directives.noCache) page.text = null
        }
    }

    override fun setChapters(chapters: ViewerChapters) {
        val page = chapters.currChapter.pages?.firstOrNull() ?: return
        val chapterId = chapters.currChapter.chapter.id ?: return
        loadJob?.cancel()

        if (currentChapters?.currChapter?.chapter?.id != chapterId) {
            launchedVideoChapterId = null
        }

        currentPage = page
        currentChapters = chapters

        if (loadedChapterIds.contains(chapterId)) {
            logcat(LogPriority.DEBUG) { "NovelWebViewViewer: Chapter $chapterId already loaded, skipping" }
            val index = chapterQueue.indexOf(chapterId)
            if (index >= 0) {
                currentChapterIndex = index
            }
            return
        }

        ttsController.stop()

        if (!isInfiniteScrollEnabled() || loadedChapterIds.isEmpty()) {
            chapterQueue.clear()
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
    ) {
        val rawContent = page.text
        if (rawContent.isNullOrBlank()) {
            displayError(Exception(activity.stringResource(TDMR.strings.novel_error_empty_chapter)))
            return
        }

        val chapterId = chapter.chapter.id ?: return

        contentJob?.cancel()
        // An in-flight append targets the DOM this base load is about to replace; cancelling it
        // avoids splicing a stale chapter's content onto the newly loaded one when it resumes.
        appendJob?.cancel()
        // Gate infinite-scroll appends until this base chapter's DOM is committed.
        docState = DocState.LOADING
        val job = scope.launch {
            if (activity.isTranslationEnabled()) {
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
                if (prepared.directives.noCache) page.text = null
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
        val chapterId = chapter.chapter.id ?: -1L
        val cfg = ContentConfig.from(
            preferences,
            RenderTarget.WEB_VIEW,
            chapter.chapter.url,
            chapter.chapter.name,
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
            val directives = NovelWebViewChapterDirectives.parse(rawContent)
            var processed = contentPipeline.process(rawContent, cfg, translator)
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
        val js = NovelWebViewChapterDomScripts.append(
            chapter = scriptChapter(chapterId, chapterName, chapterNumber, chapterUrl, direction, language),
            processed = processed,
        )

        dispatchLoadingChapter(true)
        evaluateJavascriptSafe(js) {
            styler.injectScript(isAppend = true) { buildTsundokuScript() }
            dispatchLoadingChapter(false)
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

        val chapterModel = chapter?.chapter
        val chapterId = chapterModel?.id ?: -1L
        val chapterPath = chapterModel?.url.orEmpty()

        val stylePayload = styler.buildPayload()
        webView.setBackgroundColor(stylePayload.backgroundColor)
        container.setBackgroundColor(stylePayload.backgroundColor)

        invalidateChapterRecovery()
        chapterQueue.clear()
        currentChapterIndex = 0
        currentDocumentIsVideo = directives.isVideo
        currentDocumentNoPrefetch = directives.noPrefetch
        currentDocumentDirection = direction
        currentLocalVideo = directives.localVideo?.let { fileName ->
            val loader = chapter?.pageLoader as? DownloadPageLoader
            loader?.findDownloadedFile(fileName)?.let { chapterId to it }
        }

        // Inputs are gathered on Main (touch viewer state), but the heavy work - the image-URL
        // regex scan and the Jsoup parse + full-document string build - runs off the main thread.
        // For large chapters this was a multi-MB alloc + DOM parse on the UI thread (frame skips).
        val input = NovelWebViewDocumentBuilder.DocumentInput(
            processed = processed,
            chapter = chapter,
            style = stylePayload,
            themeTokens = ThemeUtils.getThemeTokens(activity, preferences, preferences.novelTheme.get()),
            tsundokuScript = buildTsundokuScript(),
            pluginJavaScript = styler.initialPluginJavaScript(),
            infiniteScrollEnabled = isInfiniteScrollEnabled(),
            pagedLayoutEnabled = isPagedLayoutEnabled(),
            chapterDirection = direction,
            chapterLanguage = language,
            blockMedia = preferences.novelBlockMedia.get(),
            compatConfigJson = buildCompatConfig(chapter).encode(),
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
        val baseUrl = resolveWebViewBaseUrl(chapterPath)
        protectedMediaPlaybackOrigin = baseUrl?.let(Uri::parse)?.let {
            protectedMediaOrigin(it.scheme, it.host, it.port)
        }
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

    private fun buildCompatConfig(chapter: ReaderChapter?): LnReaderCompatConfig {
        val manga = activity.viewModel.manga
        val current = chapter?.chapter
        val next = currentChapters?.nextChapter?.chapter
        return LnReaderCompatConfig(
            novel = LnReaderCompatConfig.Novel(
                id = manga?.id ?: -1L,
                name = manga?.title.orEmpty(),
                path = manga?.url.orEmpty(),
            ),
            chapter = LnReaderCompatConfig.Chapter(
                id = current?.id ?: -1L,
                name = current?.name.orEmpty(),
                path = current?.url.orEmpty(),
                progress = current?.last_page_read?.coerceIn(0, 100) ?: 0,
            ),
            nextChapter = next?.let {
                LnReaderCompatConfig.Chapter(
                    id = it.id ?: -1L,
                    name = it.name,
                    path = it.url.orEmpty(),
                    progress = it.last_page_read.coerceIn(0, 100),
                )
            },
            strings = mapOf(
                "finished" to activity.stringResource(
                    TDMR.strings.reader_chapter_finished,
                    current?.name.orEmpty(),
                ),
                "nextChapter" to activity.stringResource(
                    TDMR.strings.reader_next_chapter,
                    next?.name.orEmpty(),
                ),
                "noNextChapter" to activity.stringResource(MR.strings.transition_no_next),
                "videoResumeTitle" to activity.stringResource(TDMR.strings.video_resume_title),
                "videoResumeQuestion" to activity.stringResource(TDMR.strings.video_resume_question),
                "videoResumeContinue" to activity.stringResource(TDMR.strings.video_resume_continue),
                "videoResumeRestart" to activity.stringResource(TDMR.strings.video_resume_restart),
                "videoNextUp" to activity.stringResource(TDMR.strings.video_next_up),
                "videoNextPlay" to activity.stringResource(TDMR.strings.video_next_play),
                "videoSkipIntro" to activity.stringResource(TDMR.strings.video_skip_intro),
                "close" to activity.stringResource(MR.strings.action_close),
            ),
            proxyEndpoint = proxyServer?.endpoint,
        )
    }

    private fun resolveWebViewBaseUrl(chapterUrl: String?): String? {
        val source = activity.viewModel.getSource()
        val sourceBaseUrl = when (source) {
            is JsSource -> source.baseUrl.takeIf(String::isNotBlank)
            is eu.kanade.tachiyomi.source.online.HttpSource -> source.baseUrl.takeIf(String::isNotBlank)
            else -> null
        }
        return NovelWebViewChapterMeta.resolveWebViewBaseUrl(chapterUrl, resolvedMangaUrl(), sourceBaseUrl)
            ?.let { networkHelper.domainForwarding.rewrite(it, fromJsPlugin = source is JsSource) }
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

    private fun buildTsundokuScript(): String {
        val readerChromeVisible = activity.isReaderChromeVisible()
        val context = NovelWebViewChapterMeta.TsundokuScriptContext(
            novelUrl = resolvedMangaUrl(),
            currentChapter = getCurrentTsundokuChapter(),
            chaptersInOrder = if (loadedChapters.isNotEmpty()) {
                loadedChapters
            } else {
                currentChapters?.currChapter?.let { listOf(it) }.orEmpty()
            },
            isEditingMode = isEditingMode,
            isInfiniteScroll = isInfiniteScrollEnabled(),
            textSelectionBlocked = !preferences.novelTextSelectable.get(),
            forcedLowercase = preferences.novelForceTextLowercase.get(),
            menuVisible = readerChromeVisible,
            immersive = !readerChromeVisible,
            ttsState = currentTtsState().wireValue,
            loadingChapter = !webChapterContentReady,
        )
        return NovelWebViewChapterMeta.buildTsundokuScript(context)
    }

    private fun getCurrentTsundokuChapter(): ReaderChapter? =
        loadedChapters.getOrNull(currentChapterIndex) ?: currentChapters?.currChapter

    /** Summarizes the chapter currently in view, or scrolls to the summary it already has. */
    fun requestChapterSummary() {
        val chapterId = getCurrentTsundokuChapter()?.chapter?.id ?: return
        summaryController.request(chapterId)
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
                val stillVisible = getCurrentTsundokuChapter()?.chapter?.id == chapterId
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
                        getCurrentTsundokuChapter()?.chapter?.id == pendingId &&
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
        if (!html.isNullOrBlank() && NovelWebViewChapterDirectives.parse(html).noCache) {
            page.text = null
        }
        return html
    }

    private suspend fun awaitRecoveredChapterPage(chapterId: Long, chapter: ReaderChapter): ReaderPage? {
        if (getCurrentTsundokuChapter()?.chapter?.id != chapterId) return null
        requestChapterRecovery(chapterId)
        var recovery = chapterRecoveryJob
        while (recovery != null) {
            recovery.join()
            chapter.pages?.firstOrNull()?.let { return it }
            if (getCurrentTsundokuChapter()?.chapter?.id != chapterId) return null
            val nextRecovery = chapterRecoveryJob
            if (nextRecovery == null || nextRecovery === recovery) return null
            recovery = nextRecovery
        }
        return null
    }

    private fun updateChapterMetaJs() {
        val js = buildTsundokuScript()
        evaluateJavascriptSafe("(function(){$js})();", null)
    }

    private fun currentTtsState(): TtsPlaybackState = when {
        ttsController.isPaused() -> TtsPlaybackState.PAUSED
        ttsController.isTtsAutoPlay || ttsController.isSpeaking() || ttsController.isStarting() ->
            TtsPlaybackState.PLAYING
        else -> TtsPlaybackState.STOPPED
    }

    // Updates the runtime state via [assignments] (JS statements against `t.runtime`) and fires a
    // CustomEvent on `window` so novel-source plugins and user snippets can react. See the EVENT_*
    // and *_KEY constants in NovelWebViewChapterMeta. No-ops safely when the WebView isn't ready.
    private fun dispatchTsundokuEvent(eventName: String, assignments: String, detailJson: String) {
        val obj = NovelWebViewChapterMeta.TSUNDOKU_OBJECT_NAME
        evaluateJavascriptSafe(
            """
            (function(){
              var t = window.$obj = window.$obj || {};
              t.runtime = t.runtime || {};
              $assignments
              try { window.dispatchEvent(new CustomEvent('$eventName', { detail: $detailJson })); } catch (e) {}
            })();
            """.trimIndent(),
            null,
        )
    }

    fun onMenuVisibilityChanged(visible: Boolean) {
        val chromeVisible = visible || activity.isFindInPageOpen()
        chromeMenuVisible = chromeVisible
        dispatchTsundokuEvent(
            NovelWebViewChapterMeta.EVENT_MENU_VISIBILITY,
            "t.runtime.${NovelWebViewChapterMeta.TSUNDOKU_MENU_VISIBLE_KEY} = $chromeVisible; " +
                "t.runtime.${NovelWebViewChapterMeta.TSUNDOKU_IMMERSIVE_KEY} = ${!chromeVisible};",
            "{ menuVisible: $chromeVisible, immersive: ${!chromeVisible} }",
        )
    }

    fun onChapterNavigate(direction: String) {
        dispatchTsundokuEvent(
            NovelWebViewChapterMeta.EVENT_CHAPTER_NAVIGATE,
            "",
            "{ direction: '$direction' }",
        )
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
                        webView.evaluateJavascript(NovelWebViewReadingCommands.revealChapter(chapterId)) { result ->
                            if (continuation.isActive) {
                                continuation.resume(result == "true")
                            }
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
            true
        }
    }

    private fun dispatchLoadingChapter(loading: Boolean) {
        dispatchTsundokuEvent(
            NovelWebViewChapterMeta.EVENT_CHAPTER_LOADING,
            "t.runtime.${NovelWebViewChapterMeta.TSUNDOKU_LOADING_CHAPTER_KEY} = $loading;",
            "{ loading: $loading }",
        )
    }

    private fun dispatchTtsState() {
        val state = currentTtsState()
        _ttsPlaybackState.value = state
        dispatchTsundokuEvent(
            NovelWebViewChapterMeta.EVENT_TTS_STATE,
            "t.runtime.${NovelWebViewChapterMeta.TSUNDOKU_TTS_STATE_KEY} = '${state.wireValue}';",
            "{ state: '${state.wireValue}' }",
        )
    }

    // Re-applies the current menu flag to a fresh DOM without changing page dimensions.
    private fun pushReaderChrome() {
        val js = NovelWebViewJsAssets.loadWith(
            activity,
            "reader-chrome.js",
            mapOf(
                "OBJECT" to NovelWebViewChapterMeta.TSUNDOKU_OBJECT_NAME,
                "MENU_KEY" to NovelWebViewChapterMeta.TSUNDOKU_MENU_VISIBLE_KEY,
                "MENU_VISIBLE" to chromeMenuVisible.toString(),
                "EVENT" to NovelWebViewChapterMeta.EVENT_MENU_VISIBILITY,
            ),
        )
        evaluateJavascriptSafe(js, null)
    }

    private fun showLoadingIndicator(message: String = activity.stringResource(MR.strings.loading)) {
        activity.closeFindInPage(this)

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
        webView.loadDataWithBaseURL(null, loadingHtml, "text/html", "UTF-8", null)
    }

    private fun displayError(error: Throwable) {
        activity.closeFindInPage(this)

        val fmt = ErrorFormatter.format(error)
        logcat(LogPriority.ERROR) { "NovelWebViewViewer: Chapter load failed\n${fmt.stackTrace}" }

        // No real chapter DOM is coming for this load, so onPageFinished won't reach the READY
        // transition. ERROR keeps webChapterContentReady true (so a failed load can't block
        // infinite-scroll appends forever) while marking the body un-appendable.
        docState = DocState.ERROR

        val (backgroundColor, textColor) = getThemeColors(preferences.novelTheme.get())
        val bgColorHex = ThemeUtils.colorToHex(backgroundColor)
        val textColorHex = ThemeUtils.colorToHex(textColor)

        val escapedCategory = HtmlUtils.escapeHtml(fmt.category.localized(activity))
        val escapedSummary = HtmlUtils.escapeHtml(fmt.summary)
        val escapedTrace = HtmlUtils.escapeHtml(fmt.stackTrace)
        // Base64-encode the trace so it can be safely passed to the Android JS bridge
        // without worrying about special characters breaking the JS string literal.
        val base64Trace = android.util.Base64.encodeToString(
            fmt.stackTrace.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP,
        )

        val errorHtml = """
            <!DOCTYPE html>
            <html>
            <head>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
              body { margin: 0; padding: 24px 16px; background: $bgColorHex; color: $textColorHex; font-family: sans-serif; }
              .err { max-width: 600px; margin: 0 auto; text-align: center; padding-top: 10vh; }
              .category { color: #ff5555; font-size: 18px; font-weight: bold; margin-bottom: 12px; }
              .summary { color: #888; font-size: 14px; margin-bottom: 24px; word-break: break-word; }
              .copy-btn { background: transparent; color: $textColorHex; border: 1px solid #555; border-radius: 8px; padding: 10px 20px; font-size: 14px; cursor: pointer; margin-bottom: 20px; }
              details { text-align: left; margin-top: 4px; }
              summary { cursor: pointer; color: #777; font-size: 13px; padding: 8px 0; user-select: none; }
              pre { background: rgba(0,0,0,0.25); color: #bbb; padding: 12px; border-radius: 6px; font-size: 11px; white-space: pre-wrap; word-break: break-all; max-height: 280px; overflow-y: auto; margin: 0; }
            </style>
            </head>
            <body>
            <div class="err">
              <div class="category">$escapedCategory</div>
              <div class="summary">$escapedSummary</div>
              <button class="copy-btn" onclick="copyErr()">Copy error details</button>
              <details>
                <summary>Technical details</summary>
                <pre>$escapedTrace</pre>
              </details>
            </div>
            <script>
            function copyErr() {
              if (window.Android && window.Android.copyToClipboard) {
                window.Android.copyToClipboard('$base64Trace');
              }
            }
            </script>
            </body>
            </html>
        """.trimIndent()

        rearmAutoScrollOnErrorPage = isAutoScrolling
        webView.loadDataWithBaseURL(null, errorHtml, "text/html", "UTF-8", null)
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
                    val direction = if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) 1 else -1
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
            webView.evaluateJavascript(
                "(function() { window.getSelection().removeAllRanges(); document.activeElement.blur(); })();",
                null,
            )
            webView.clearFocus()
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(webView.windowToken, 0)

            // Reload chapter to discard edits
            activity.viewModel.reloadChapter(fromSource = false)
            return
        }

        if (isEditing) {
            // Bionic spans are presentation only; remove them before content becomes editable so
            // they cannot interfere with the caret or leak into saved chapter HTML.
            styler.setBionicReading(false)
        }
        this.isEditingMode = isEditing
        styler.injectScript { buildTsundokuScript() }
        updateChapterMetaJs()
        if (isEditing) styler.injectReaderUi()

        if (isEditing) {
            // Focusing a contenteditable for the keyboard scrolls Chromium to the top; snapshot the
            // ratio, restore it after focus settles, and gate onScrollProgress meanwhile.
            val restoreRatio = lastSavedProgress
            isRestoringScroll = true
            val token = ++scrollRestoreToken
            webView.post {
                activity.window.decorView.clearFocus()
                webView.requestFocus()
                webView.requestFocusFromTouch()
                val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(webView, 0)
                webView.postDelayed({
                    imm?.showSoftInput(webView, 0)
                }, 120)
            }
            webView.postDelayed({
                if (token != scrollRestoreToken) return@postDelayed
                evaluateJavascriptSafe(
                    """
                    (function() {
                        var target = $restoreRatio;
                        var docHeight = Math.max(
                            document.documentElement.scrollHeight,
                            document.body ? document.body.scrollHeight : 0
                        );
                        var viewport = window.innerHeight || document.documentElement.clientHeight;
                        var range = docHeight - viewport;
                        if (range > 0) window.scrollTo(0, range * target);
                    })();
                    """.trimIndent(),
                )
                liftRestoreGuard(token)
            }, 220)
        } else {
            // Invalidate a pending entry-side restore callback so it can't fire after we've
            // already left edit mode.
            isRestoringScroll = true
            val token = ++scrollRestoreToken
            webView.evaluateJavascript(
                "(function() { window.getSelection().removeAllRanges(); document.activeElement.blur(); })();",
                null,
            )
            webView.clearFocus()
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(webView.windowToken, 0)
            liftRestoreGuard(token)
        }

        val script = """
            (function() {
                function enableEdit() {
                    document.designMode = 'off';
                    var styleId = '${ID_EDIT_MODE_STYLE}';
                    if ('$isEditing' === 'true') {
                        if (!document.getElementById(styleId)) {
                            var style = document.createElement('style');
                            style.id = styleId;
                            style.innerHTML = '${CHAPTER_TAG_NAME}, #LNReader-chapter, [${ATTR_DATA_EDITABLE}="1"] { -webkit-user-select: text !important; user-select: text !important; pointer-events: auto !important; -webkit-tap-highlight-color: transparent; outline: none; } ' +
                                'body { padding-bottom: max(220px, 38vh) !important; }';
                            document.head.appendChild(style);
                        }

                        var editTargets = document.querySelectorAll('${CHAPTER_TAG_NAME}');
                        if (editTargets.length === 0) {
                            var chapterRoot = document.getElementById('LNReader-chapter');
                            if (!chapterRoot) return;
                            chapterRoot.setAttribute('contenteditable', 'true');
                            chapterRoot.setAttribute('${ATTR_DATA_EDITABLE}', '1');
                            chapterRoot.setAttribute('tabindex', '0');
                        } else {
                            for (var i = 0; i < editTargets.length; i++) {
                                editTargets[i].setAttribute('contenteditable', 'true');
                                editTargets[i].setAttribute('${ATTR_DATA_EDITABLE}', '1');
                                editTargets[i].setAttribute('tabindex', '0');
                            }
                        }

                        window.$TSUNDOKU_OBJECT_NAME = window.$TSUNDOKU_OBJECT_NAME || {};
                        window.$TSUNDOKU_OBJECT_NAME.runtime = window.$TSUNDOKU_OBJECT_NAME.runtime || {};
                        if (!window.$TSUNDOKU_OBJECT_NAME.runtime.editInputBound) {
                            window.$TSUNDOKU_OBJECT_NAME.runtime.editInputBound = true;
                            var existingListener = window.$TSUNDOKU_OBJECT_NAME.runtime.inputListener;
                            if (existingListener) {
                                document.removeEventListener('input', existingListener);
                            }
                            var inputListener = function(e) {
                                if (window.Android && window.Android.onContentEdited) {
                                    window.Android.onContentEdited();
                                }
                            };
                            document.addEventListener('input', inputListener);
                            window.$TSUNDOKU_OBJECT_NAME.runtime.inputListener = inputListener;
                        }
                    } else {
                        var style = document.getElementById(styleId);
                        if (style) {
                            style.parentNode.removeChild(style);
                        }

                        var editableNodes = document.querySelectorAll('[data-tsundoku-editable="1"]');
                        for (var j = 0; j < editableNodes.length; j++) {
                            editableNodes[j].removeAttribute('contenteditable');
                            editableNodes[j].removeAttribute('${ATTR_DATA_EDITABLE}');
                            editableNodes[j].removeAttribute('tabindex');
                        }

                        var contents = [];
                        var nodes = document.querySelectorAll('${CHAPTER_TAG_NAME}');
                        if (nodes.length > 0) {
                            for (var i = 0; i < nodes.length; i++) {
                                var html = nodes[i].innerHTML;
                                var chapterId = nodes[i].getAttribute('${CHAPTER_ID_ATTR}');
                                contents.push({id: chapterId, content: html});
                            }
                        } else {
                            var chapterRoot = document.getElementById('LNReader-chapter');
                            if (!chapterRoot) return;
                            var currentId = '${currentChapters?.currChapter?.chapter?.id ?: -1}';
                            contents.push({id: currentId, content: chapterRoot.innerHTML});
                        }
                        if (window.Android && window.Android.onSaveEditedContent) {
                            window.Android.onSaveEditedContent(JSON.stringify(contents));
                        }
                    }
                }

                if (document.readyState === 'complete') {
                    enableEdit();
                } else {
                    window.addEventListener('load', enableEdit);
                }
            })();
        """.trimIndent()

        webView.evaluateJavascript(script) {
            if (!isEditing) styler.injectReaderUi()
        }
    }

    override fun handleGenericMotionEvent(event: MotionEvent): Boolean = false

    @Keep
    @Suppress("unused")
    inner class WebViewInterface {
        /**
         * Arms protected-media grants for the DASH flow until the document changes. Android WebView's
         * Widevine needs the device DRM identifier even at L3 — denying the permission leaves ClearKey
         * only — and that identifier is a permanent, unresettable handle on the device. Incognito
         * promises the plugin site learns nothing durable about this session, so DRM playback loses
         * rather than incognito.
         *
         * The arm covers every permission request in the document, not one: see onPermissionRequest.
         */
        @JavascriptInterface
        fun requestProtectedMediaPlayback(): Boolean {
            val incognito = getIncognitoState.await(activity.viewModel.getSource()?.id)
            protectedMediaPlaybackArmed = !incognito && protectedMediaPlaybackOrigin != null
            return protectedMediaPlaybackArmed
        }

        @JavascriptInterface
        fun onReaderMessage(message: String) {
            val parsed = LnReaderMessage.parse(message) ?: return
            activity.runOnUiThread {
                when (parsed) {
                    is LnReaderMessage.Save -> {
                        if (!isVideoChapter()) return@runOnUiThread
                        val page = currentPage ?: return@runOnUiThread
                        lastSavedProgress = parsed.progress / 100f
                        lastPersistedPercent = parsed.progress
                        awaitingFirstScrollSample = false
                        activity.saveNovelProgress(page, parsed.progress)
                        activity.onNovelProgressChanged(lastSavedProgress)
                    }
                    is LnReaderMessage.Refetch -> activity.viewModel.reloadChapter(fromSource = true)
                    is LnReaderMessage.Next -> activity.loadNextChapter()
                    is LnReaderMessage.ShowError -> {
                        inlineFeedback.showInlineError(parsed.message)
                    }
                }
            }
        }

        @JavascriptInterface
        fun onContentEdited() {
            activity.runOnUiThread {
                activity.viewModel.setHasUnsavedChanges(true)
            }
        }

        @JavascriptInterface
        fun onSaveEditedContent(json: String) {
            logcat(LogPriority.DEBUG) { "NovelWebViewViewer: onSaveEditedContent(length=${json.length})" }
            activity.runOnUiThread {
                activity.viewModel.saveEditedChapterContent(json)
            }
        }

        @JavascriptInterface
        fun onScrollProgress(progress: Float) {
            activity.runOnUiThread {
                if (isRestoringScroll) return@runOnUiThread
                awaitingFirstScrollSample = false
                lastSavedProgress = progress
                if (NovelProgress.progressToPercent(progress) == lastPersistedPercent) return@runOnUiThread
                saveProgress()
            }
        }

        @JavascriptInterface
        fun onScrollUpdate(progress: Float) {
            activity.runOnUiThread {
                if (isRestoringScroll) return@runOnUiThread
                // Don't let a delayed echo from a slider seek overwrite the finger's live position,
                // nor let it clobber lastSavedProgress with a stale in-flight value.
                if (System.currentTimeMillis() - lastUserSeekAt < SEEK_ECHO_SUPPRESS_MS) return@runOnUiThread
                awaitingFirstScrollSample = false
                lastSavedProgress = progress
                activity.onNovelProgressChanged(progress)
            }
        }

        @JavascriptInterface
        fun onChapterScrollUpdate(chapterId: String, progress: Float) {
            activity.runOnUiThread {
                if (isRestoringScroll) return@runOnUiThread
                // Fired edge-triggered by the JS (only on a real change). Resolve by stable chapter
                // id, not index: DOM boundaries and chapterQueue briefly disagree after a prepend.
                val id = chapterId.toLongOrNull() ?: return@runOnUiThread
                val chapterIndex = loadedChapters.indexOfFirst { it.chapter.id == id }
                val chapter = loadedChapters.getOrNull(chapterIndex) ?: return@runOnUiThread
                val newPage = chapter.pages?.firstOrNull()
                val oldIndex = currentChapterIndex
                val identityChanged = chapterIndex != oldIndex
                val pageChanged = currentPage?.chapter?.chapter?.id != id
                val titleChanged = activity.viewModel.state.value.novelVisibleChapter?.id != id

                if (identityChanged) {
                    // Moving forward marks the outgoing chapter (and any skipped) read, per-chapter
                    // progress never snaps to 100 in the DOM so onScrollProgress can't.
                    NovelProgress.forwardChaptersToMarkRead(oldIndex, chapterIndex, loadedChapters.size)
                        .forEach { idx ->
                            loadedChapters.getOrNull(idx)?.let { completedChapter ->
                                activity.viewModel.saveNovelProgress(completedChapter, 100)
                                logcat(LogPriority.DEBUG) {
                                    "NovelWebViewViewer: Marking chapter index $idx as 100% (moved forward)"
                                }
                            }
                        }
                    currentChapterIndex = chapterIndex
                }

                if (identityChanged || titleChanged) {
                    activity.viewModel.setNovelVisibleChapter(chapter.chapter)
                    updateChapterMetaJs()
                }

                if (newPage == null) {
                    // The DOM can outlive ViewerChapters' curr/prev/next reference window. Never
                    // keep the previous page under the new visible ID: progress and summaries
                    // would then be attributed to the wrong chapter while this one is reloaded.
                    currentPage = null
                    lastSavedProgress = if (chapterIndex > oldIndex) 0f else 1f
                    lastPersistedPercent = -1
                    awaitingFirstScrollSample = true
                    requestChapterRecovery(id)
                    return@runOnUiThread
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
        }

        @JavascriptInterface
        fun onPagePositionChanged(
            chapterId: String,
            unitIndex: Int,
            unitCount: Int,
            firstPage: Int,
            lastPage: Int,
            totalPages: Int,
        ) {
            val id = chapterId.toLongOrNull() ?: return
            activity.runOnUiThread {
                val previous = pagedController.position.value
                pagedController.onPosition(id, unitIndex, unitCount, firstPage, lastPage, totalPages)
                if (previous != null &&
                    (previous.chapterId != id || previous.unitIndex != unitIndex)
                ) {
                    activity.onNovelVisualPageChanged()
                }
            }
        }

        // The summary card's own buttons. Cancel, close and regenerate are the only actions; anything
        // else the page sends is ignored rather than trusted.
        @JavascriptInterface
        fun onChapterSummaryAction(chapterId: String, action: String) {
            val id = chapterId.toLongOrNull() ?: return
            activity.runOnUiThread { summaryController.onAction(id, action) }
        }

        @JavascriptInterface
        fun onScrollRestoreComplete(token: Int) {
            // Only the latest restore may lift the guard; ignore stale completions.
            activity.runOnUiThread {
                if (token != scrollRestoreToken) return@runOnUiThread
                liftRestoreGuard(token)
                refreshFindInPage()
            }
        }

        @JavascriptInterface
        fun onInfiniteScrollAppendComplete(@Suppress("UNUSED_PARAMETER") chapterId: Long) {
            // TTS handoff is driven directly from loadNextChapterForTts; this foreground callback
            // only refreshes an active native find after the appended DOM has settled.
            activity.runOnUiThread { refreshFindInPage() }
        }

        @JavascriptInterface
        fun claimReaderGesture(owner: String) {
            // Deliberately not posted to the UI thread: a queued runnable could land after the
            // gesture callback that reads it.
            gestureTarget = ReaderGestureTarget.fromWire(owner)
        }

        @JavascriptInterface
        fun onReaderGesturesReady() {
            pageOwnsGestures = true
        }

        @JavascriptInterface
        fun toggleTts() {
            activity.runOnUiThread {
                if (!isTtsEnabled) return@runOnUiThread
                when {
                    ttsController.isPaused() -> resumeTts()
                    ttsController.isSpeaking() -> pauseTts()
                    isTtsActive() -> stopTts()
                    else -> startTtsFromViewport()
                }
            }
        }

        @JavascriptInterface
        fun startTtsAtParagraph(index: Int) {
            activity.runOnUiThread {
                if (!isTtsEnabled) return@runOnUiThread
                this@NovelWebViewViewer.startTtsAtParagraph(index.coerceAtLeast(0))
            }
        }

        @JavascriptInterface
        fun startTtsAtHoveredParagraph() {
            activity.runOnUiThread {
                if (!isTtsEnabled) return@runOnUiThread
                this@NovelWebViewViewer.startTtsAtTaggedParagraph()
            }
        }

        @JavascriptInterface
        fun loadNextChapter() {
            activity.runOnUiThread {
                logcat(LogPriority.DEBUG) {
                    "NovelWebViewViewer: loadNextChapter triggered, infiniteScroll=${isInfiniteScrollEnabled()}, isLoadingNext=$isLoadingNext, loadedCount=${loadedChapterIds.size}"
                }
                if (isInfiniteScrollEnabled() && !webChapterContentReady) {
                    setJsLoadingNext()
                    return@runOnUiThread
                }
                if (isInfiniteScrollEnabled() && webChapterIsError) {
                    setJsLoadingNext()
                    return@runOnUiThread
                }
                if (!isInfiniteScrollEnabled()) {
                    activity.loadNextChapter()
                } else if (nextRequiresDocumentNavigation) {
                    activity.loadNextChapter()
                } else if (reachedNovelEnd) {
                    setJsNoMoreChapters(true)
                } else if (ttsController.isTtsAutoPlay) {
                    // Don't append while TTS is active, pre-fetch so the next chapter is ready when
                    // TTS calls appendNextChapterIfAvailable.
                    if (handoffState.isIdle) {
                        scope.launch { preFetchNextChapterForTts() }
                    }
                } else if (System.currentTimeMillis() - lastNextLoadFailedAt <
                    NovelProgress.NEXT_LOAD_RETRY_COOLDOWN_MS
                ) {
                    // Keep the JS load latch held (JS set it before this call) so it stops re-firing
                    // loadNextChapter every scroll frame during the cooldown; schedule a single
                    // release for when the cooldown expires so it can't become permanent.
                    if (!cooldownReleaseScheduled) {
                        cooldownReleaseScheduled = true
                        val remaining = NovelProgress.NEXT_LOAD_RETRY_COOLDOWN_MS -
                            (System.currentTimeMillis() - lastNextLoadFailedAt)
                        webView.postDelayed({
                            cooldownReleaseScheduled = false
                            setJsLoadingNext()
                        }, remaining.coerceAtLeast(0))
                    }
                    logcat(LogPriority.DEBUG) { "NovelWebViewViewer: loadNextChapter ignored, in failure cooldown" }
                } else if (!isLoadingNext) {
                    isLoadingNext = true
                    appendJob = scope.launch {
                        try {
                            val ok = appendNextChapterIfAvailable()
                            lastNextLoadFailedAt = if (ok) 0L else System.currentTimeMillis()
                        } finally {
                            isLoadingNext = false
                            setJsLoadingNext()
                        }
                    }
                } else {
                    logcat(LogPriority.WARN) {
                        "NovelWebViewViewer: loadNextChapter ignored (infiniteScroll=${isInfiniteScrollEnabled()}, isLoadingNext=$isLoadingNext)"
                    }
                }
            }
        }

        @JavascriptInterface
        fun markChapterAsShort() {
            activity.runOnUiThread {
                lastSavedProgress = 1f
                saveProgress()
                activity.onNovelProgressChanged(1f)
                logcat(LogPriority.DEBUG) { "NovelWebViewViewer: Chapter marked as short (fits in viewport)" }

                // Chapter fits in viewport → no scroll events fire → threshold never reached.
                // Trigger infinite scroll append manually.
                if (isInfiniteScrollEnabled() && !isLoadingNext && !ttsController.isTtsAutoPlay &&
                    !webChapterIsError
                ) {
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
            }
        }

        @JavascriptInterface
        fun copyToClipboard(base64Text: String) {
            activity.runOnUiThread {
                val text = try {
                    android.util.Base64.decode(base64Text, android.util.Base64.DEFAULT)
                        .toString(Charsets.UTF_8)
                } catch (_: Exception) {
                    base64Text
                }
                val cm = activity.getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("error", text))
                activity.toast(activity.stringResource(TDMR.strings.novel_error_copied))
            }
        }

        @JavascriptInterface
        fun playLocalVideo() {
            activity.runOnUiThread { launchLocalVideo(force = true) }
        }

        @JavascriptInterface
        fun requestNextChapter() {
            loadNextChapter()
        }

        @JavascriptInterface
        fun requestPrevChapter() {
            activity.runOnUiThread { activity.loadPreviousChapter() }
        }

        @JavascriptInterface
        fun requestStartTts() {
            activity.runOnUiThread { startTts() }
        }

        @JavascriptInterface
        fun requestPauseTts() {
            activity.runOnUiThread { pauseTts() }
        }

        @JavascriptInterface
        fun requestResumeTts() {
            activity.runOnUiThread { resumeTts() }
        }

        @JavascriptInterface
        fun requestStopTts() {
            activity.runOnUiThread { stopTts() }
        }

        @JavascriptInterface
        fun requestSetProgress(percent: Int) {
            activity.runOnUiThread { setProgressPercent(percent) }
        }
    }

    private fun setJsLoadingNext() = evaluateJavascriptSafe(
        "(function(){ if (window.$TSUNDOKU_OBJECT_NAME && window.$TSUNDOKU_OBJECT_NAME.runtime && window.$TSUNDOKU_OBJECT_NAME.runtime.setLoadingNext) window.$TSUNDOKU_OBJECT_NAME.runtime.setLoadingNext(false); })();",
        null,
    )

    private fun setJsNoMoreChapters(value: Boolean) = evaluateJavascriptSafe(
        "(function(){ if (window.$TSUNDOKU_OBJECT_NAME && window.$TSUNDOKU_OBJECT_NAME.runtime && window.$TSUNDOKU_OBJECT_NAME.runtime.setNoMoreChapters) window.$TSUNDOKU_OBJECT_NAME.runtime.setNoMoreChapters($value); })();",
        null,
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
        evaluateJavascriptSafe(
            "(function(){ if (window.$TSUNDOKU_OBJECT_NAME && window.$TSUNDOKU_OBJECT_NAME.runtime && window.$TSUNDOKU_OBJECT_NAME.runtime.resetChapterTracking) window.$TSUNDOKU_OBJECT_NAME.runtime.resetChapterTracking(); })();",
            null,
        )
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
            displayError(Exception(activity.stringResource(TDMR.strings.novel_error_empty_chapter)))
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

    /**
     * Fetch and cache the next chapter without appending to the DOM.
     * Called when the JS scroll threshold fires during TTS auto-play so the chapter is
     * immediately available when TTS finishes the current one.
     */
    private suspend fun preFetchNextChapterForTts() {
        if (!handoffState.isIdle) return
        val anchor = loadedChapters.lastOrNull() ?: currentChapters?.currChapter ?: return
        val preparedChapter = activity.viewModel.prepareNextChapterForInfiniteScroll(anchor) ?: return
        val nextId = preparedChapter.chapter.id ?: return
        if (loadedChapterIds.contains(nextId)) return

        val page = preparedChapter.pages?.firstOrNull() ?: return
        val loader = page.chapter.pageLoader ?: return

        handoffState = TtsHandoffState.PreFetching(anchorChapterId = anchor.chapter.id)
        logcat(LogPriority.DEBUG) { "TTS (WebView): Pre-fetching next chapter ${preparedChapter.chapter.name}" }
        try {
            val loaded = awaitPageText(page = page, loader = loader, timeoutMs = 30_000)
            if (loaded) {
                withContext(Dispatchers.Main) {
                    if (handoffState.isPreFetching) {
                        handoffState = TtsHandoffState.Cached(Pair(preparedChapter, page))
                        prefetchCompletedSignal.tryEmit(Unit)
                        logcat(LogPriority.DEBUG) {
                            "TTS (WebView): Cached next chapter ${preparedChapter.chapter.name}"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN) { "TTS (WebView): Pre-fetch failed: ${e.message}" }
        } finally {
            // Drop back to Idle if we never reached Cached (e.g. load failed).
            if (handoffState.isPreFetching) handoffState = TtsHandoffState.Idle
        }
    }

    /** Append the next chapter to the WebView, using the TTS prefetch when available. */
    private suspend fun appendNextChapterIfAvailable(): Boolean {
        val cached = handoffState.cachedOrNull
        if (cached != null) {
            handoffState = TtsHandoffState.Idle
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

        // Coalesce with an in-flight TTS pre-fetch instead of starting a duplicate fetch.
        if (handoffState.isPreFetching) {
            logcat(LogPriority.DEBUG) { "NovelWebViewViewer: TTS append waiting on in-flight pre-fetch" }
            withTimeoutOrNull(5_000L) { prefetchCompletedSignal.first() }
            if (handoffState.cachedOrNull != null) {
                // Cache populated while we waited - recurse to take the cache path.
                return appendNextChapterIfAvailable()
            }
            // Timed out: the prefetch is still running but we're proceeding with a
            // cold fetch. Clear PreFetching now so the racing prefetch coroutine
            // cannot later set handoffState = Cached for a chapter we're about to
            // load here - that stale entry would confuse the *next* TTS handoff.
            handoffState = TtsHandoffState.Idle
        }

        val anchor = loadedChapters.lastOrNull() ?: currentChapters?.currChapter ?: run {
            logcat(LogPriority.ERROR) {
                "NovelWebViewViewer: appendNext failed, no anchor chapter (loadedCount=${loadedChapters.size})"
            }
            inlineFeedback.showInlineError("No anchor chapter for infinite scroll")
            return false
        }
        logcat(LogPriority.DEBUG) {
            "NovelWebViewViewer: appendNext starting from anchor=${anchor.chapter.id}/${anchor.chapter.name}"
        }

        val preparedChapter = activity.viewModel.prepareNextChapterForInfiniteScroll(anchor) ?: run {
            logcat(LogPriority.WARN) { "NovelWebViewViewer: No next chapter available after ${anchor.chapter.name}" }
            if (activity.viewModel.hasNextPagedPage(anchor)) {
                inlineFeedback.showInlineError("Unable to load next page")
            } else {
                // Surface once, then latch so the scroll handler stops re-triggering at the last chapter.
                if (!reachedNovelEnd) {
                    inlineFeedback.showInlineError(activity.stringResource(MR.strings.transition_no_next))
                }
                reachedNovelEnd = true
                setJsNoMoreChapters(true)
            }
            return false
        }
        val nextId = preparedChapter.chapter.id ?: run {
            logcat(LogPriority.ERROR) { "NovelWebViewViewer: prepared next chapter has null id" }
            inlineFeedback.showInlineError("Chapter has no id")
            return false
        }
        logcat(LogPriority.DEBUG) { "NovelWebViewViewer: prepared next=$nextId/${preparedChapter.chapter.name}" }

        if (loadedChapterIds.contains(nextId)) {
            logcat(LogPriority.DEBUG) { "NovelWebViewViewer: next chapter $nextId already loaded, skipping" }
            return true
        }

        val page = preparedChapter.pages?.firstOrNull() ?: run {
            logcat(LogPriority.ERROR) { "NovelWebViewViewer: No page in prepared next chapter" }
            inlineFeedback.showInlineError("No page in next chapter")
            return false
        }
        val loader = page.chapter.pageLoader ?: run {
            logcat(LogPriority.ERROR) { "NovelWebViewViewer: No page loader for next chapter" }
            inlineFeedback.showInlineError("No loader for next chapter")
            return false
        }

        try {
            logcat(LogPriority.DEBUG) {
                "NovelWebViewViewer: loading page for next chapter $nextId, state=${page.status}"
            }
            val loaded = try {
                awaitPageText(page = page, loader = loader, timeoutMs = 30_000)
            } catch (_: TimeoutCancellationException) {
                logcat(LogPriority.ERROR) { "NovelWebViewViewer: Timed out loading next chapter page after 30s" }
                inlineFeedback.showInlineError("Timeout loading next chapter")
                false
            } catch (_: CancellationException) {
                logcat(LogPriority.DEBUG) { "NovelWebViewViewer: appendNext cancelled" }
                false
            } catch (e: Exception) {
                logcat(LogPriority.ERROR) { "NovelWebViewViewer: Error loading next chapter page: ${e.message}" }
                inlineFeedback.showInlineError(
                    "Error: ${e.message ?: activity.stringResource(MR.strings.unknown_error)}",
                )
                false
            }

            if (!loaded) return false

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
        val sign = if (direction < 0) "-" else ""
        evaluateJavascriptSafe(
            "window.scrollBy({ top: $sign(window.innerHeight * $fraction), behavior: 'smooth' });",
        )
    }

    private fun startTimedPageTurn(delta: Int, effect: NovelPageEffect) {
        curlController.startTimed(delta, curlReadingDirection(), pagedController.isDoubleSpread(), effect)
    }

    private fun requestCurlTarget(delta: Int, onReady: (Boolean) -> Unit) {
        val turnDelta = if (delta < 0) -1 else 1
        curlPreviewMoved = false
        evaluateJavascriptSafe(NovelWebViewReadingCommands.prepareCurl(turnDelta)) { moved ->
            curlPreviewMoved = moved == "true"
            if (curlPreviewMoved) awaitWebViewFrame { onReady(true) } else onReady(false)
        }
    }

    private fun curlReadingDirection() = if (currentDocumentDirection == NovelContentDirection.RTL) {
        NovelPageCurlReadingDirection.RTL
    } else {
        NovelPageCurlReadingDirection.LTR
    }

    private fun finishCurlPreview(onFinished: () -> Unit = {}) {
        evaluateJavascriptSafe(
            "window.$TSUNDOKU_OBJECT_NAME?.runtime?.readerLayout?.finishSilentTurn?.(false, true);",
        ) { awaitWebViewFrame(onFinished) }
    }

    private fun rollbackCurlPreview(onRestored: () -> Unit = {}) {
        if (!curlPreviewMoved) {
            onRestored()
            return
        }
        curlPreviewMoved = false
        evaluateJavascriptSafe(
            NovelWebViewReadingCommands.ROLLBACK_CURL,
        ) {
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
        evaluateJavascriptSafe(NovelWebViewReadingCommands.startVerticalAutoScroll(pxPerSec), null)
        webView.postDelayed({
            if (!isAutoScrolling || session != autoScrollSession || attempt != autoScrollStartAttempt) {
                return@postDelayed
            }
            evaluateJavascriptSafe(
                "(function(){ return !!(window.__tdAutoScroll && window.__tdAutoScroll.running); })();",
            ) { running ->
                if (!isAutoScrolling || session != autoScrollSession || attempt != autoScrollStartAttempt) {
                    return@evaluateJavascriptSafe
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
        evaluateJavascriptSafe(NovelWebViewReadingCommands.STOP_AUTO_SCROLL, null)
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

        evaluateJavascriptSafe(NovelWebViewReadingCommands.seekVertical(progress), null)
    }

    /**
     * Drops the loaded-chapter queue so the next [setChapters] re-renders the chapter instead of
     * taking the already-loaded early return.
     */
    fun invalidateLoadedChapters() = chapterQueue.clear()

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
        evaluateJavascriptSafe(NovelWebViewTtsDomScripts.extractText(chapterId)) { result ->
            if (!isTtsEnabled) return@evaluateJavascriptSafe
            val text = unescapeJsResult(result)

            if (text.isNotBlank() && text != "null") {
                logcat(LogPriority.DEBUG) { "TTS (WebView): Starting to speak ${text.length} characters" }
                ttsController.speak(text, chapterIdx, chapterId)

                if (isInfiniteScrollEnabled() &&
                    handoffState.isIdle &&
                    loadedChapters.getOrNull(currentChapterIndex + 1) == null
                ) {
                    scope.launch { preFetchNextChapterForTts() }
                }
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
        handoffState = TtsHandoffState.Idle
        dispatchTtsState()
        // The loadNextChapter TTS branch skips the JS-latch release, so after a threshold hit during
        // playback runtime.loadingNext stays true and scroll-driven appending never re-fires. Clear
        // it so infinite scroll resumes once TTS is off, but not while a real append is in flight:
        // that append still owns the latch and will release it, and clobbering it here would let a
        // second scroll launch a duplicate append.
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
        val activeChapter = getCurrentTsundokuChapter()
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
     * sync. Stays `true` across the brief stop/restart gap inside
     * `stepParagraph` so the periodic sync doesn't tear down the foreground
     * service mid-step.
     */
    fun isTtsActive(): Boolean =
        ttsController.isTtsAutoPlay || ttsController.isSpeaking() ||
            ttsController.isPaused() || ttsController.isStarting()

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
        evaluateJavascriptSafe(
            NovelWebViewTtsDomScripts.firstVisibleParagraph(chapterId),
        ) { rawIndex ->
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
        evaluateJavascriptSafe(NovelWebViewTtsDomScripts.taggedParagraph(chapterId)) { result ->
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
        evaluateJavascriptSafe(NovelWebViewTtsDomScripts.extractText(chapterId)) { result ->
            if (!isTtsEnabled) return@evaluateJavascriptSafe
            val text = unescapeJsResult(result)
            if (text.isBlank() || text == "null") {
                logcat(LogPriority.WARN) { "TTS (WebView): No text available for selected paragraph" }
                stopTts()
                return@evaluateJavascriptSafe
            }
            ttsController.ttsViewportParagraphIndex = index.coerceAtLeast(0)
            ttsController.hasViewportStartOverride = true
            ttsController.speak(text, chapterIdx, chapterId)
            dispatchTtsState()
        }
    }

    /**
     * Get the currently selected text from the WebView
     */
    fun getSelectedText(): String? {
        var selectedText: String? = null
        evaluateJavascriptSafe(
            """
            (function() {
                var selection = window.getSelection();
                if (selection && selection.toString().trim()) {
                    return selection.toString().trim();
                }
                return null;
            })();
            """.trimIndent(),
        ) { result ->
            selectedText = unescapeJsResult(result)
        }
        return selectedText
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
        evaluateJavascriptSafe(
            """
            (function() {
                var selection = window.getSelection();
                if (selection) {
                    selection.removeAllRanges();
                }
            })();
            """.trimIndent(),
            null,
        )
    }

    /**
     * Handle the "Remember" action from text selection menu
     */
    private fun onRememberSelectedText(actionMode: ActionMode? = null) {
        evaluateJavascriptSafe(
            """
        (function() {
            var sel = window.getSelection();
            if (!sel || sel.rangeCount === 0) return null;
            var text = sel.toString().trim();
            if (!text) return null;
            var range = sel.getRangeAt(0);
            var node = range.startContainer;
            if (node && node.nodeType === 3) node = node.parentNode;
            var para = -1;
            try {
                var chapterEl = (node && node.closest) ? node.closest('tsundoku-chapter') : null;
                if (!chapterEl) chapterEl = document.getElementById('LNReader-chapter');
                if (chapterEl) {
                    var plain = chapterEl.querySelector('[data-tsundoku-plain-text]');
                    if (plain) {
                        var pre = document.createRange();
                        pre.selectNodeContents(plain);
                        pre.setEnd(range.startContainer, range.startOffset);
                        var lines = pre.toString().split('\n');
                        var count = 0;
                        for (var i = 0; i < lines.length - 1; i++) {
                            if (lines[i].trim().length > 0) count++;
                        }
                        para = count;
                    } else {
                        var blocks = chapterEl.querySelectorAll('p, li, blockquote, h1, h2, h3, h4, h5, h6, pre');
                        for (var j = 0; j < blocks.length; j++) {
                            if (blocks[j].contains(node)) { para = j; break; }
                        }
                    }
                }
            } catch (e) {
                para = -1;
            }
            return para + '\n' + text;
        })();
            """.trimIndent(),
        ) { result ->
            activity.runOnUiThread {
                actionMode?.finish() // finish AFTER JS has read the selection
                val raw = if (result != "null") unescapeJsResult(result) else null
                val newlineIdx = raw?.indexOf('\n') ?: -1
                val paragraphIndex = raw?.takeIf { newlineIdx >= 0 }
                    ?.substring(0, newlineIdx)
                    ?.toIntOrNull()
                    ?.takeIf { it >= 0 }
                val selectedText = when {
                    raw == null -> null
                    newlineIdx >= 0 -> raw.substring(newlineIdx + 1).trim().ifEmpty { null }
                    else -> raw.trim().ifEmpty { null }
                }

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
}

/** Who owns the current touch, as claimed page-side by `reader-gestures.js`. */
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
