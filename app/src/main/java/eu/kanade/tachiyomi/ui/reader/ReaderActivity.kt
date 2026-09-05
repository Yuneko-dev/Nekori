package eu.kanade.tachiyomi.ui.reader

import android.annotation.SuppressLint
import android.app.assist.AssistContent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.View.LAYER_TYPE_HARDWARE
import android.view.WindowManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.net.toUri
import androidx.core.transition.doOnEnd
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.transition.platform.MaterialContainerTransform
import com.hippo.unifile.UniFile
import eu.kanade.core.util.ifSourcesLoaded
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.reader.DisplayRefreshHost
import eu.kanade.presentation.reader.EstimatedStatusBarHeight
import eu.kanade.presentation.reader.NovelChapterDrawer
import eu.kanade.presentation.reader.NovelStatusBar
import eu.kanade.presentation.reader.OrientationSelectDialog
import eu.kanade.presentation.reader.ReaderContentOverlay
import eu.kanade.presentation.reader.TranslationLanguageSelectDialog
import eu.kanade.presentation.reader.appbars.BottomBarEditorSheet
import eu.kanade.presentation.reader.appbars.BottomBarItem
import eu.kanade.presentation.reader.appbars.NovelReaderAppBars
import eu.kanade.presentation.reader.appbars.QuotesSheet
import eu.kanade.presentation.reader.appbars.bottomBarItemInfo
import eu.kanade.presentation.reader.appbars.isAvailable
import eu.kanade.presentation.reader.appbars.novelPagedProgressLabel
import eu.kanade.presentation.reader.deserializeStatusBarOrder
import eu.kanade.presentation.reader.settings.ReaderSettingsDialog
import eu.kanade.presentation.util.formatChapterNumber
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.databinding.ReaderActivityBinding
import eu.kanade.tachiyomi.discord.DiscordRpcManager
import eu.kanade.tachiyomi.jsplugin.source.JsSource
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.service.TtsPlaybackService
import eu.kanade.tachiyomi.ui.reader.setting.NovelPagePosition
import eu.kanade.tachiyomi.ui.reader.setting.NovelReadingLayout
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsViewModel
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderProgressIndicator
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.ThemeUtils
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewViewer
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.system.isNightMode
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.view.setComposeContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import tachiyomi.core.common.Constants
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import androidx.compose.ui.graphics.Color as ComposeColor

class ReaderActivity : BaseActivity() {

    private data class DiscordReaderActivity(
        val sourceId: Long,
        val novelName: String,
        val chapter: Chapter,
        val cover: String?,
    )

    companion object {
        fun newIntent(context: Context, mangaId: Long?, chapterId: Long?): Intent {
            return Intent(context, ReaderActivity::class.java).apply {
                putExtra("manga", mangaId)
                putExtra("chapter", chapterId)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
    }

    private val readerPreferences = Injekt.get<ReaderPreferences>()
    private val preferences = Injekt.get<BasePreferences>()

    lateinit var binding: ReaderActivityBinding

    val viewModel by viewModels<ReaderViewModel>()
    private var assistUrl: String? = null

    /**
     * Configuration at reader level, like background color or forced orientation.
     */
    private var config: ReaderConfig? = null

    private var menuToggleToast: Toast? = null
    private val displayRefreshHost = DisplayRefreshHost()

    // Registered eagerly (before the viewer exists) so a WebView file chooser has a launcher to call.
    private var webViewFileChooserCallback: ValueCallback<Array<Uri>>? = null
    private val webViewFileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        webViewFileChooserCallback?.onReceiveValue(uris)
        webViewFileChooserCallback = null
    }

    fun launchWebViewFileChooser(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams,
    ): Boolean {
        webViewFileChooserCallback?.onReceiveValue(null)
        webViewFileChooserCallback = callback
        return try {
            webViewFileChooserLauncher.launch(params.createIntent())
            true
        } catch (e: Exception) {
            webViewFileChooserCallback = null
            callback.onReceiveValue(null)
            false
        }
    }

    private val windowInsetsController by lazy { WindowInsetsControllerCompat(window, window.decorView) }

    private var loadingIndicator: ReaderProgressIndicator? = null
    private var ttsNotificationSyncJob: Job? = null

    private val ttsNotificationControlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != TtsPlaybackService.ACTION_CONTROL) return

            when (intent.getStringExtra(TtsPlaybackService.EXTRA_COMMAND)) {
                TtsPlaybackService.COMMAND_PLAY -> resumeTtsFromNotification()
                TtsPlaybackService.COMMAND_PAUSE -> pauseTtsFromNotification()
                TtsPlaybackService.COMMAND_PREV_PARAGRAPH -> stepTtsParagraph(isNext = false)
                TtsPlaybackService.COMMAND_NEXT_PARAGRAPH -> stepTtsParagraph(isNext = true)
                TtsPlaybackService.COMMAND_STOP -> stopTtsFromNotification()
            }
        }
    }

    var isScrollingThroughPages = false
        private set

    /**
     * Has the tap-zone preview overlay been shown at least once in this
     * activity instance? Set to `true` after the first viewer construction
     * shows the overlay; subsequent viewer constructions (e.g. switching the
     * novel rendering mode between TextView and WebView mid-session) check
     * this flag and skip the on-start auto-display.
     *
     * Per-activity-instance — automatically reset on `onCreate`.
     */
    var tapZonesShownInSession = false

    // Quotes functionality
    private var showQuotesSheet by mutableStateOf(false)
    private var findInPageState by mutableStateOf<NovelFindInPageState?>(null)
    private var findInPageViewer: NovelWebViewViewer? = null
    private val consumedFindShortcutKeys = mutableSetOf<Int>()

    internal fun isFindInPageOpen(): Boolean = findInPageState != null

    internal fun isReaderChromeVisible(): Boolean =
        viewModel.state.value.menuVisible || isFindInPageOpen()

    internal fun dismissFindInPageIme() {
        windowInsetsController.hide(WindowInsetsCompat.Type.ime())
    }

    private fun openFindInPage() {
        val viewer = viewModel.state.value.viewer as? NovelWebViewViewer ?: return
        if (findInPageViewer !== viewer) {
            findInPageViewer?.closeFindInPage()
            findInPageViewer = viewer
            findInPageState = NovelFindInPageState(focusRequestId = 1)
            viewer.openFindInPage { activeMatchOrdinal, numberOfMatches, isDoneCounting ->
                runOnUiThread {
                    if (findInPageViewer === viewer) {
                        findInPageState = findInPageState?.withResult(
                            activeMatchOrdinal = activeMatchOrdinal,
                            numberOfMatches = numberOfMatches,
                            isDoneCounting = isDoneCounting,
                        )
                    }
                }
            }
        } else {
            findInPageState = findInPageState?.let {
                it.copy(focusRequestId = it.focusRequestId + 1)
            }
        }
    }

    private fun updateFindInPageQuery(query: String) {
        findInPageState = findInPageState?.withQuery(query)
        findInPageViewer?.findInPage(query)
    }

    private fun navigateFindInPage(forward: Boolean) {
        if (findInPageState?.hasMatches == true) {
            findInPageViewer?.findNext(forward)
        }
    }

    internal fun closeFindInPage(expectedViewer: NovelWebViewViewer? = null) {
        val owner = findInPageViewer
        if (expectedViewer != null && owner !== expectedViewer) {
            expectedViewer.closeFindInPage()
            return
        }
        if (owner == null) return

        findInPageViewer = null
        findInPageState = null
        owner.closeFindInPage()
        dismissFindInPageIme()
    }

    /**
     * Called when the activity is created. Initializes the presenter and configuration.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        registerSecureActivity(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                OVERRIDE_TRANSITION_OPEN,
                R.anim.shared_axis_x_push_enter,
                R.anim.shared_axis_x_push_exit,
            )
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.shared_axis_x_push_enter, R.anim.shared_axis_x_push_exit)
        }

        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        super.onCreate(savedInstanceState)

        binding = ReaderActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.setComposeOverlay()

        ContextCompat.registerReceiver(
            this,
            ttsNotificationControlReceiver,
            IntentFilter(TtsPlaybackService.ACTION_CONTROL),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        if (!viewModel.hasValidArgs) {
            finish()
            return
        }

        NotificationReceiver.dismissNotification(
            this,
            viewModel.mangaId.hashCode(),
            Notifications.ID_NEW_CHAPTERS,
        )

        config = ReaderConfig()
        setMenuVisibility(viewModel.state.value.menuVisible)

        // Finish when incognito mode is disabled
        preferences.incognitoMode.changes()
            .drop(1)
            .onEach { if (!it) finish() }
            .launchIn(lifecycleScope)

        viewModel.state
            .map { it.initError }
            .distinctUntilChanged()
            .filterNotNull()
            .onEach(::setInitialChapterError)
            .launchIn(lifecycleScope)

        viewModel.state
            .map { it.manga }
            .distinctUntilChanged()
            .filterNotNull()
            .onEach { updateViewer() }
            .launchIn(lifecycleScope)

        viewModel.state
            .map { it.viewerChapters }
            .distinctUntilChanged()
            .filterNotNull()
            .onEach(::setChapters)
            .launchIn(lifecycleScope)

        val discordRpc = Injekt.get<DiscordRpcManager>()
        viewModel.state
            .map { state ->
                val manga = state.manga ?: return@map null
                val chapter = state.novelVisibleChapter
                    ?: state.currentChapter?.chapter
                    ?: return@map null
                DiscordReaderActivity(
                    sourceId = manga.source,
                    novelName = manga.title,
                    chapter = chapter,
                    cover = manga.thumbnailUrl,
                )
            }
            .distinctUntilChanged()
            .filterNotNull()
            .onEach { activity ->
                val chapterUrl = withIOContext { viewModel.getChapterUrl(activity.chapter) }
                discordRpc.showChapter(
                    sourceId = activity.sourceId,
                    novelName = activity.novelName,
                    chapterName = activity.chapter.name,
                    cover = activity.cover,
                    chapterUrl = chapterUrl,
                )
            }
            .launchIn(lifecycleScope)

        viewModel.eventFlow
            .onEach { event ->
                when (event) {
                    ReaderViewModel.Event.ReloadViewerChapters -> {
                        viewModel.state.value.viewerChapters?.let(::setChapters)
                    }
                    ReaderViewModel.Event.ReloadWithTranslation -> {
                        // Force reload content with new translation state
                        reloadContentWithTranslation()
                    }
                    ReaderViewModel.Event.PageChanged -> {
                        displayRefreshHost.flash()
                    }
                    is ReaderViewModel.Event.SetOrientation -> {
                        setOrientation(event.orientation)
                    }
                }
            }
            .launchIn(lifecycleScope)

        readerPreferences.novelTtsBackgroundPlayback.changes()
            .drop(1)
            .onEach { enabled ->
                if (enabled) {
                    val state = currentNovelTtsState()
                    if (state?.active == true) {
                        startTtsNotificationSync()
                        syncBackgroundTtsState()
                    }
                } else {
                    stopBackgroundTtsIfRunning()
                }
            }
            .launchIn(lifecycleScope)
    }

    private fun ReaderActivityBinding.setComposeOverlay(): Unit = composeOverlay.setComposeContent {
        val state by viewModel.state.collectAsState()
        val autoTranslateEnabled by readerPreferences.autoTranslate.collectAsState()
        val novelStatusBarEnabled by readerPreferences.novelStatusBarEnabled.collectAsState()
        val novelStatusBarShowTime by readerPreferences.novelStatusBarShowTime.collectAsState()
        val novelStatusBarShowBattery by readerPreferences.novelStatusBarShowBattery.collectAsState()
        val novelStatusBarShowChapterNumber by readerPreferences.novelStatusBarShowChapterNumber.collectAsState()
        val novelStatusBarShowChapterTitle by readerPreferences.novelStatusBarShowChapterTitle.collectAsState()
        val novelStatusBarShowProgress by readerPreferences.novelStatusBarShowProgress.collectAsState()
        val novelStatusBarPosition by readerPreferences.novelStatusBarPosition.collectAsState()
        val novelStatusBarSize by readerPreferences.novelStatusBarSize.collectAsState()
        val novelStatusBarShowCharging by readerPreferences.novelStatusBarShowCharging.collectAsState()
        val novelStatusBarOrderRaw by readerPreferences.novelStatusBarOrder.collectAsState()
        val novelTheme by readerPreferences.novelTheme.collectAsState()
        val novelBgColorInt by readerPreferences.novelBackgroundColor.collectAsState()
        val novelFontColorInt by readerPreferences.novelFontColor.collectAsState()
        var statusBarCollapsed by remember { mutableStateOf(false) }
        val density = LocalDensity.current
        var statusBarHeightPx by remember {
            mutableIntStateOf(with(density) { EstimatedStatusBarHeight.roundToPx() })
        }
        val chapterDrawerState = rememberDrawerState(DrawerValue.Closed)
        val chapterDrawerScope = rememberCoroutineScope()
        var chapterDrawerSnapshot by remember { mutableStateOf<ReaderChapterDrawerSnapshot?>(null) }
        var chapterDrawerOpenSessionId by remember { mutableLongStateOf(0L) }
        var chapterDrawerOpening by remember { mutableStateOf(false) }
        var chapterDrawerSelectionInProgress by remember { mutableStateOf(false) }
        val settingsViewModel = remember {
            ReaderSettingsViewModel(
                readerState = viewModel.state,
                onChangeOrientation = viewModel::setMangaOrientationType,
                resolveNovelThemeColors = {
                    ThemeUtils.getThemeColors(this@ReaderActivity, readerPreferences, it)
                },
            )
        }

        val isNovelViewer = state.viewer is NovelWebViewViewer
        val isPagedReading = (state.viewer as? NovelWebViewViewer)?.isPagedMode() == true
        val statusPagePositionFlow = remember(state.viewer) {
            (state.viewer as? NovelWebViewViewer)?.pagePosition ?: flowOf<NovelPagePosition?>(null)
        }
        val statusPagePosition by statusPagePositionFlow.collectAsState(initial = null)
        val findInPageOpen = findInPageState != null
        val readerChromeVisible = state.menuVisible || findInPageOpen
        val statusBarAtBottomEdge = novelStatusBarPosition != "top"
        val visibleChapterId = state.novelVisibleChapter?.id ?: state.currentChapter?.chapter?.id

        LaunchedEffect(chapterDrawerState.currentValue, visibleChapterId) {
            if (chapterDrawerState.isOpen && !chapterDrawerSelectionInProgress && visibleChapterId != null) {
                val currentSnapshot = chapterDrawerSnapshot
                chapterDrawerSnapshot = if (currentSnapshot?.items?.any { it.id == visibleChapterId } == true) {
                    currentSnapshot.copy(currentChapterId = visibleChapterId)
                } else {
                    viewModel.getChapterDrawerSnapshot(visibleChapterId)
                }
            } else if (chapterDrawerState.currentValue == DrawerValue.Closed) {
                chapterDrawerSnapshot = null
            }
        }

        val openChapterDrawer: () -> Unit = {
            if (
                !chapterDrawerOpening &&
                !chapterDrawerSelectionInProgress &&
                chapterDrawerState.currentValue == DrawerValue.Closed &&
                visibleChapterId != null &&
                state.viewer is NovelWebViewViewer
            ) {
                chapterDrawerOpening = true
                chapterDrawerScope.launch {
                    try {
                        val snapshot = viewModel.getChapterDrawerSnapshot(visibleChapterId)
                        if (snapshot != null) {
                            chapterDrawerSnapshot = snapshot
                            chapterDrawerOpenSessionId++
                            setMenuVisibility(false)
                            val drawerReady = withTimeoutOrNull(1_000L) {
                                snapshotFlow { chapterDrawerState.currentOffset }
                                    .first { !it.isNaN() }
                            } != null
                            if (!drawerReady) {
                                chapterDrawerSnapshot = null
                                return@launch
                            }
                            chapterDrawerState.open()
                        }
                    } finally {
                        chapterDrawerOpening = false
                        if (
                            chapterDrawerState.currentValue == DrawerValue.Closed &&
                            chapterDrawerState.targetValue == DrawerValue.Closed
                        ) {
                            chapterDrawerSnapshot = null
                        }
                    }
                }
            }
        }

        val dismissChapterDrawer: () -> Unit = {
            chapterDrawerScope.launch { chapterDrawerState.close() }
        }

        val selectChapterFromDrawer: (Long) -> Unit = { targetChapterId ->
            val snapshot = chapterDrawerSnapshot
            val currentChapterId = snapshot?.currentChapterId
            if (!chapterDrawerSelectionInProgress && snapshot != null && currentChapterId != null) {
                if (targetChapterId != currentChapterId) {
                    val request = viewModel.beginChapterNavigation(ReaderNavigationSource.USER)
                    if (request != null) {
                        val currentIndex = snapshot.currentIndex
                        val targetIndex = snapshot.items.indexOfFirst { it.id == targetChapterId }
                        val direction = if (targetIndex > currentIndex) "next" else "prev"
                        val viewer = state.viewer as? NovelWebViewViewer
                        chapterDrawerSelectionInProgress = true
                        chapterDrawerScope.launch {
                            try {
                                viewer?.stopAutoScroll()
                                stopNovelTtsForManualNav()
                                viewer?.flushProgress()
                                if (viewModel.loadChapterById(targetChapterId, request)) {
                                    chapterDrawerSnapshot = chapterDrawerSnapshot?.copy(
                                        currentChapterId = targetChapterId,
                                    )
                                    viewer?.scrollToLoadedChapter(targetChapterId)
                                    viewer?.onChapterNavigate(direction)
                                }
                            } finally {
                                viewModel.finishChapterNavigation(request)
                                chapterDrawerSelectionInProgress = false
                            }
                        }
                    }
                }
            }
        }

        // Pad viewer_container by the status bar's height on its docked edge so content never renders
        // under it. Reserved while enabled (not on menu visibility) so menu toggles don't resize the
        // WebView and jump its scroll.
        val statusBarReservePx = if (isNovelViewer && novelStatusBarEnabled) statusBarHeightPx else 0
        LaunchedEffect(statusBarReservePx, statusBarAtBottomEdge) {
            val top = if (statusBarAtBottomEdge) 0 else statusBarReservePx
            val bottom = if (statusBarAtBottomEdge) statusBarReservePx else 0
            val vc = binding.viewerContainer
            if (vc.paddingTop != top || vc.paddingBottom != bottom) vc.setPadding(0, top, 0, bottom)
        }

        NovelChapterDrawer(
            drawerState = chapterDrawerState,
            snapshot = chapterDrawerSnapshot,
            openSessionId = chapterDrawerOpenSessionId,
            selectionInProgress = chapterDrawerSelectionInProgress,
            onDismissRequest = dismissChapterDrawer,
            onChapterSelected = selectChapterFromDrawer,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                val isNovelMode = state.viewer is NovelWebViewViewer
                ContentOverlay(state = state)

                val statusBarAtBottom = novelStatusBarPosition != "top"
                val ttsOverlayBottomPadding = if (
                    isNovelMode && novelStatusBarEnabled && statusBarAtBottom && !readerChromeVisible
                ) {
                    with(density) { statusBarHeightPx.toDp() }
                } else {
                    0.dp
                }

                AppBars(
                    state = state,
                    onOpenChapterDrawer = openChapterDrawer,
                    ttsOverlayBottomPadding = ttsOverlayBottomPadding,
                )

                if (isNovelMode && !readerChromeVisible && novelStatusBarEnabled) {
                    val chapter = state.novelVisibleChapter ?: state.currentChapter?.chapter
                    val showChapterSegment = novelStatusBarShowChapterNumber || novelStatusBarShowChapterTitle
                    val chapterText: String? = chapter?.takeIf { showChapterSegment }?.let { ch ->
                        val numStr = if (novelStatusBarShowChapterNumber && ch.chapter_number >= 0f) {
                            "Ch. ${formatChapterNumber(ch.chapter_number.toDouble())}"
                        } else {
                            null
                        }
                        val nameStr = if (novelStatusBarShowChapterTitle) ch.name.ifEmpty { null } else null
                        when {
                            numStr != null && nameStr != null -> "$numStr: $nameStr"
                            numStr != null -> numStr
                            else -> nameStr
                        }
                    }
                    val (bgInt, textInt) = remember(novelTheme, novelBgColorInt, novelFontColorInt) {
                        ThemeUtils.getThemeColors(this@ReaderActivity, readerPreferences, novelTheme)
                    }
                    val readerBgColor = ComposeColor(bgInt)
                    val readerTextColor = ComposeColor(textInt)
                    val statusBarOrder = remember(novelStatusBarOrderRaw) {
                        novelStatusBarOrderRaw.deserializeStatusBarOrder()
                    }
                    val progressText = if (isPagedReading) {
                        statusPagePosition?.let { novelPagedProgressLabel(it) }
                    } else {
                        "${state.novelProgressPercent}%"
                    }
                    NovelStatusBar(
                        chapterText = chapterText,
                        progressText = progressText,
                        order = statusBarOrder,
                        showTime = novelStatusBarShowTime,
                        showChapter = showChapterSegment,
                        showProgress = novelStatusBarShowProgress,
                        showBattery = novelStatusBarShowBattery,
                        showCharging = novelStatusBarShowCharging,
                        backgroundColor = readerBgColor,
                        textColor = readerTextColor,
                        isCollapsed = statusBarCollapsed,
                        onToggleCollapse = { statusBarCollapsed = !statusBarCollapsed },
                        size = novelStatusBarSize,
                        onHeightChanged = { statusBarHeightPx = it },
                        modifier = Modifier
                            .align(if (statusBarAtBottom) Alignment.BottomCenter else Alignment.TopCenter)
                            .then(if (statusBarAtBottom) Modifier else Modifier.statusBarsPadding()),
                    )
                }
            }
        }

        val onDismissRequest = viewModel::closeDialog
        when (state.dialog) {
            is ReaderViewModel.Dialog.Settings -> {
                ReaderSettingsDialog(
                    onDismissRequest = onDismissRequest,
                    onShowMenus = { setMenuVisibility(true) },
                    onHideMenus = { setMenuVisibility(false) },
                    screenModel = settingsViewModel,
                    isNovelMode = state.viewer is NovelWebViewViewer,
                )
            }
            is ReaderViewModel.Dialog.OrientationModeSelect -> {
                OrientationSelectDialog(
                    onDismissRequest = onDismissRequest,
                    viewModel = settingsViewModel,
                    onChange = { stringRes ->
                        menuToggleToast?.cancel()
                        menuToggleToast = toast(stringRes)
                    },
                )
            }
            is ReaderViewModel.Dialog.TranslationLanguageSelect -> {
                TranslationLanguageSelectDialog(
                    onDismissRequest = onDismissRequest,
                    currentLanguage = viewModel.getTargetTranslationLanguage(),
                    autoTranslateEnabled = autoTranslateEnabled,
                    onToggleAutoTranslate = { enabled ->
                        readerPreferences.autoTranslate.set(enabled)
                    },
                    onSelectLanguage = { languageCode ->
                        viewModel.setTargetTranslationLanguage(languageCode)
                        // Optionally trigger translation with new language
                        if (!viewModel.state.value.isTranslating) {
                            viewModel.toggleTranslation()
                        } else {
                            // Retrigger translation with new language
                            viewModel.toggleTranslation() // Turn off
                            viewModel.toggleTranslation() // Turn on with new language
                        }
                    },
                    onRetranslate = {
                        onDismissRequest()
                        viewModel.retranslateCurrentChapter()
                    },
                    onOpenSettings = {
                        onDismissRequest()
                        startActivity(
                            Intent(this@ReaderActivity, MainActivity::class.java).apply {
                                action = Intent.ACTION_APPLICATION_PREFERENCES
                                putExtra(
                                    MainActivity.EXTRA_SETTINGS_DESTINATION,
                                    eu.kanade.tachiyomi.ui.setting.SettingsScreen.Destination.Translation.id,
                                )
                                putExtra(MainActivity.EXTRA_FINISH_SETTINGS_ON_EXIT, true)
                            },
                        )
                    },
                )
            }
            null -> {}
        }

        // Quotes sheet
        val quotesState = remember { mutableStateOf(viewModel.getQuotes()) }
        val showQuotesState = remember { mutableStateOf(false) }

        LaunchedEffect(showQuotesSheet) {
            showQuotesState.value = showQuotesSheet
            if (showQuotesSheet) {
                quotesState.value = viewModel.getQuotes()
            }
        }

        // Handle back button when quotes sheet is open
        androidx.activity.compose.BackHandler(enabled = showQuotesSheet) {
            showQuotesSheet = false
        }

        if (showQuotesState.value) {
            QuotesSheet(
                quotes = quotesState.value,
                novelTitle = state.manga?.title.orEmpty(),
                onDismiss = {
                    showQuotesSheet = false
                },
                onQuoteClick = { quote ->
                    logcat(LogPriority.DEBUG) { "Quote clicked: ${quote.content.take(50)}..." }
                },
                onQuoteDelete = { quote ->
                    logcat(LogPriority.DEBUG) { "Quote deleted: ${quote.content.take(50)}..." }
                    viewModel.deleteQuote(quote)
                    quotesState.value = viewModel.getQuotes()
                },
                onQuoteUpdate = { quote ->
                    logcat(LogPriority.DEBUG) { "Quote updated: ${quote.content.take(50)}..." }
                    viewModel.updateQuote(quote)
                    quotesState.value = viewModel.getQuotes()
                },
                onQuoteAdd = { content ->
                    logcat(LogPriority.DEBUG) { "Quote added: ${content.take(50)}..." }
                    viewModel.saveQuote(content, "")
                    quotesState.value = viewModel.getQuotes()
                },
                onQuoteReorder = { reorderedQuotes ->
                    logcat(LogPriority.DEBUG) { "Quotes reordered: ${reorderedQuotes.size} quotes" }
                    viewModel.reorderQuotes(reorderedQuotes)
                    quotesState.value = viewModel.getQuotes()
                },
            )
        }
    }

    /**
     * Called when the activity is destroyed. Cleans up the viewer, configuration and any view.
     */
    override fun onDestroy() {
        stopBackgroundTtsIfRunning()
        ttsNotificationSyncJob?.cancel()
        unregisterReceiver(ttsNotificationControlReceiver)
        super.onDestroy()
        viewModel.state.value.viewer?.destroy()
        config = null
        menuToggleToast?.cancel()
    }

    override fun onPause() {
        (viewModel.state.value.viewer as? NovelWebViewViewer)?.flushProgress()

        lifecycleScope.launchNonCancellable {
            viewModel.updateHistory()
        }

        if (!isChangingConfigurations && !readerPreferences.novelTtsBackgroundPlayback.get()) {
            stopAnyActiveNovelTts()
            stopBackgroundTtsIfRunning()
        }

        super.onPause()
    }

    /**
     * Set menu visibility again on activity resume to apply immersive mode again if needed.
     * Helps with rotations.
     */
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { viewModel.restartReadTimerSynced() }
        setMenuVisibility(viewModel.state.value.menuVisible)
        if (readerPreferences.novelTtsBackgroundPlayback.get() &&
            currentNovelTtsState()?.active == true
        ) {
            startTtsNotificationSync()
            syncBackgroundTtsState()
        }
    }

    /**
     * The manifest declares these config changes so rotation no longer recreates the activity, keeping
     * a novel viewer (TTS engine, WebView state, scroll position) alive across an orientation change.
     * We re-assert immersive/menu since onResume won't run. Non-novel viewers recreate() as before.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val state = viewModel.state.value
        val viewer = state.viewer
        // viewer is null until updateViewer() runs, which happens asynchronously after manga
        // loads. Rotating in that window shouldn't fall through to recreate() for a novel entry,
        // so fall back to the manga's type while the viewer hasn't been created yet.
        val isNovel = viewer is NovelWebViewViewer ||
            (viewer == null && state.manga?.isNovel == true)
        if (isNovel) {
            setMenuVisibility(state.menuVisible)
        } else {
            recreate()
        }
    }

    /**
     * Called when the window focus changes. It sets the menu visibility to the last known state
     * to apply immersive mode again if needed.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            setMenuVisibility(viewModel.state.value.menuVisible)
        }
    }

    override fun onProvideAssistContent(outContent: AssistContent) {
        super.onProvideAssistContent(outContent)
        assistUrl?.let { outContent.webUri = it.toUri() }
    }

    /**
     * Called when the user clicks the back key or the button on the toolbar. The call is
     * delegated to the presenter.
     */
    override fun finish() {
        viewModel.onActivityFinish()
        super.finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                OVERRIDE_TRANSITION_CLOSE,
                R.anim.shared_axis_x_pop_enter,
                R.anim.shared_axis_x_pop_exit,
            )
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.shared_axis_x_pop_enter, R.anim.shared_axis_x_pop_exit)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (isFindInPageOpen()) return super.onKeyUp(keyCode, event)

        if (keyCode == KeyEvent.KEYCODE_N) {
            loadNextChapter()
            return true
        } else if (keyCode == KeyEvent.KEYCODE_P) {
            loadPreviousChapter()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /**
     * Dispatches a key event. If the viewer doesn't handle it, call the default implementation.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (handleFindInPageShortcut(event)) return true
        if (isFindInPageOpen()) return super.dispatchKeyEvent(event)

        val handled = viewModel.state.value.viewer?.handleKeyEvent(event) ?: false
        return handled || super.dispatchKeyEvent(event)
    }

    private fun handleFindInPageShortcut(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP && consumedFindShortcutKeys.remove(event.keyCode)) {
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false

        val isEnterKey = event.keyCode == KeyEvent.KEYCODE_ENTER ||
            event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        val action = when {
            event.keyCode == KeyEvent.KEYCODE_F &&
                event.hasModifiers(KeyEvent.META_CTRL_ON) -> FindShortcutAction.OPEN
            event.keyCode == KeyEvent.KEYCODE_G &&
                event.hasModifiers(KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON) ->
                FindShortcutAction.PREVIOUS
            event.keyCode == KeyEvent.KEYCODE_G &&
                event.hasModifiers(KeyEvent.META_CTRL_ON) -> FindShortcutAction.NEXT
            event.keyCode == KeyEvent.KEYCODE_F3 &&
                event.hasModifiers(KeyEvent.META_SHIFT_ON) -> FindShortcutAction.PREVIOUS
            event.keyCode == KeyEvent.KEYCODE_F3 && event.hasNoModifiers() -> FindShortcutAction.NEXT
            isFindInPageOpen() &&
                isEnterKey &&
                event.hasModifiers(KeyEvent.META_SHIFT_ON) -> FindShortcutAction.PREVIOUS
            isFindInPageOpen() &&
                isEnterKey &&
                event.hasNoModifiers() -> FindShortcutAction.NEXT
            isFindInPageOpen() &&
                event.keyCode == KeyEvent.KEYCODE_ESCAPE &&
                event.hasNoModifiers() -> FindShortcutAction.CLOSE
            else -> null
        } ?: return false

        consumedFindShortcutKeys += event.keyCode
        if (event.repeatCount == 0) {
            when (action) {
                FindShortcutAction.OPEN -> openFindInPage()
                FindShortcutAction.NEXT -> {
                    if (isFindInPageOpen()) navigateFindInPage(forward = true) else openFindInPage()
                }
                FindShortcutAction.PREVIOUS -> {
                    if (isFindInPageOpen()) navigateFindInPage(forward = false) else openFindInPage()
                }
                FindShortcutAction.CLOSE -> closeFindInPage()
            }
        }
        return true
    }

    private enum class FindShortcutAction {
        OPEN,
        NEXT,
        PREVIOUS,
        CLOSE,
    }

    /**
     * Dispatches a generic motion event. If the viewer doesn't handle it, call the default
     * implementation.
     */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val handled = viewModel.state.value.viewer?.handleGenericMotionEvent(event) ?: false
        return handled || super.dispatchGenericMotionEvent(event)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        (viewModel.state.value.viewer as? NovelWebViewViewer)?.onTrimMemory()
    }

    @Composable
    private fun ContentOverlay(state: ReaderViewModel.State) {
        val flashOnPageChange by readerPreferences.flashOnPageChange.collectAsState()

        val colorOverlayEnabled by readerPreferences.colorFilter.collectAsState()
        val colorOverlay by readerPreferences.colorFilterValue.collectAsState()
        val colorOverlayMode by readerPreferences.colorFilterMode.collectAsState()
        val colorOverlayBlendMode = remember(colorOverlayMode) {
            ReaderPreferences.ColorFilterMode.getOrNull(colorOverlayMode)?.second
        }

        ReaderContentOverlay(
            brightness = state.brightnessOverlayValue,
            color = colorOverlay.takeIf { colorOverlayEnabled },
            colorBlendMode = colorOverlayBlendMode,
        )

        if (flashOnPageChange) {
            DisplayRefreshHost(hostState = displayRefreshHost)
        }
    }

    @Composable
    fun AppBars(
        state: ReaderViewModel.State,
        onOpenChapterDrawer: () -> Unit,
        ttsOverlayBottomPadding: Dp = 0.dp,
    ) {
        if (!ifSourcesLoaded()) {
            return
        }

        val source = viewModel.getSource()
        val hasWebViewSupport = source is HttpSource || source is JsSource
        val isNovelViewer = state.viewer is NovelWebViewViewer

        if (isNovelViewer) {
            val novelViewer = state.viewer
            var isAutoScrolling by remember { mutableStateOf(false) }

            val onScrollToTop: () -> Unit = novelViewer::scrollToTop
            val onToggleAutoScroll: () -> Unit = {
                novelViewer.toggleAutoScroll()
                isAutoScrolling = novelViewer.isAutoScrollActive()
            }

            // Get novel progress for slider - use state from ViewModel for real-time updates
            val showProgressSlider by readerPreferences.novelShowProgressSlider.collectAsState()
            val showVerticalProgressSlider by readerPreferences.novelVerticalScrollbar.collectAsState()
            val verticalProgressSliderPosition by readerPreferences.novelVerticalScrollbarPosition.collectAsState()
            val verticalProgressSliderSize by readerPreferences.novelVerticalProgressSliderSize.collectAsState()
            val progressSliderMode = when {
                !showProgressSlider -> "none"
                showVerticalProgressSlider && verticalProgressSliderPosition == "left" -> "vertical_left"
                showVerticalProgressSlider && verticalProgressSliderPosition == "right" -> "vertical_right"
                else -> "horizontal"
            }

            // Use state.novelProgressPercent for slider value, which is updated via onNovelProgressChanged callback
            val novelProgressFromState = state.novelProgressPercent
            val pagedProgress by novelViewer.pagePosition.collectAsState()

            val ttsPlaybackState by novelViewer.ttsPlaybackState.collectAsState()
            val isTtsActive = ttsPlaybackState != NovelWebViewViewer.TtsPlaybackState.STOPPED
            val isTtsPaused = ttsPlaybackState == NovelWebViewViewer.TtsPlaybackState.PAUSED
            val ttsEnabled by readerPreferences.novelTtsEnabled.collectAsState()
            val storedTtsControlsVisible by readerPreferences.novelTtsControlsVisible.collectAsState()
            val ttsControlsVisible = ttsEnabled && storedTtsControlsVisible

            LaunchedEffect(novelViewer, ttsEnabled) {
                novelViewer.setTtsEnabled(ttsEnabled)
                if (!ttsEnabled) {
                    readerPreferences.novelTtsControlsVisible.set(false)
                    stopBackgroundTtsIfRunning()
                    stopTtsNotificationSync()
                }
            }

            // Also sync from viewer when menu becomes visible (for initial sync)
            LaunchedEffect(state.menuVisible) {
                if (state.menuVisible) {
                    viewModel.updateNovelProgressPercent(novelViewer.getProgressPercent())
                }
            }

            // Format chapter title based on preference
            val chapterTitleDisplay by readerPreferences.novelChapterTitleDisplay.collectAsState()
            val formattedChapterTitle = remember(state.currentChapter, state.novelVisibleChapter, chapterTitleDisplay) {
                val chapter = state.novelVisibleChapter ?: state.currentChapter?.chapter
                if (chapter == null) {
                    null
                } else {
                    when (chapterTitleDisplay) {
                        1 -> { // Number only
                            if (chapter.chapter_number >= 0) {
                                "Chapter ${chapter.chapter_number.let {
                                    if (it == it.toLong().toFloat()) it.toLong().toString() else it.toString()
                                }}"
                            } else {
                                chapter.name // Fallback to name if no valid number
                            }
                        }
                        2 -> { // Both name and number
                            if (chapter.chapter_number >= 0) {
                                val numStr = chapter.chapter_number.let {
                                    if (it ==
                                        it.toLong().toFloat()
                                    ) {
                                        it.toLong().toString()
                                    } else {
                                        it.toString()
                                    }
                                }
                                "Ch. $numStr: ${chapter.name}"
                            } else {
                                chapter.name // Fallback to name if no valid number
                            }
                        }
                        else -> chapter.name // 0 = Name only (default)
                    }
                }
            }

            val bottomBarItems by viewModel.bottomBarItems.collectAsState()
            var showBottomBarEditor by remember { mutableStateOf(false) }
            var showEditSaveDialog by remember { mutableStateOf(false) }
            var isEditing by remember { mutableStateOf(false) }

            NovelReaderAppBars(
                visible = state.menuVisible,
                findInPageState = findInPageState,
                onFindInPage = ::openFindInPage,
                onFindQueryChange = ::updateFindInPageQuery,
                onFindPrevious = { navigateFindInPage(forward = false) },
                onFindNext = { navigateFindInPage(forward = true) },
                onCloseFindInPage = { closeFindInPage() },

                novelTitle = state.manga?.title,
                chapterTitle = formattedChapterTitle,
                navigateUp = onBackPressedDispatcher::onBackPressed,
                onClickTopAppBar = ::openMangaScreen,
                bookmarked = state.bookmarked,
                onToggleBookmarked = viewModel::toggleChapterBookmark,
                onOpenInWebView = ::openChapterInWebView.takeIf { hasWebViewSupport },
                onOpenInBrowser = ::openChapterInBrowser.takeIf { hasWebViewSupport },
                onShare = ::shareChapter.takeIf { hasWebViewSupport },
                onReloadLocal = { viewModel.reloadChapter(fromSource = false) },
                onReloadSource = { viewModel.reloadChapter(fromSource = true) },
                onEditBottomBar = { showBottomBarEditor = true },

                showProgressSlider = showProgressSlider,
                progressSliderMode = progressSliderMode,
                verticalProgressSliderSize = verticalProgressSliderSize,
                currentProgress = novelProgressFromState,
                onProgressChange = { newProgress ->
                    viewModel.updateNovelProgressPercent(newProgress)
                    // A seek is an explicit position choice; a running autoscroll would immediately
                    // scroll away from it, so stop it first and reflect that in the toggle state.
                    if (novelViewer.isAutoScrollActive()) {
                        novelViewer.stopAutoScroll()
                        isAutoScrolling = false
                    }
                    novelViewer.setProgressPercent(newProgress)
                },
                isPaged = novelViewer.isPagedMode(),
                pagedProgress = pagedProgress,
                onPagedProgressChange = novelViewer::setPagedUnit,

                onNextChapter = {
                    loadNextChapter()
                    // Sync slider after navigation
                    lifecycleScope.launch {
                        delay(100)
                        val progress = (viewModel.state.value.viewer as? NovelWebViewViewer)
                            ?.getProgressPercent() ?: 0
                        viewModel.updateNovelProgressPercent(progress)
                    }
                },
                enabledNext = state.viewerChapters?.nextChapter != null,
                onPreviousChapter = {
                    loadPreviousChapter()
                    // Sync slider after navigation
                    lifecycleScope.launch {
                        delay(100)
                        val progress = (viewModel.state.value.viewer as? NovelWebViewViewer)
                            ?.getProgressPercent() ?: 0
                        viewModel.updateNovelProgressPercent(progress)
                    }
                },
                enabledPrevious = state.viewerChapters?.prevChapter != null,
                onOpenChapterDrawer = onOpenChapterDrawer,

                orientation = ReaderOrientation.fromPreference(
                    viewModel.getMangaOrientation(resolveDefault = false),
                ),
                onClickOrientation = viewModel::openOrientationModeSelectDialog,
                onClickSettings = viewModel::openSettingsDialog,
                onScrollToTop = onScrollToTop,
                isAutoScrolling = isAutoScrolling,
                onToggleAutoScroll = onToggleAutoScroll,
                isTranslating = state.isTranslating,
                translationMasterEnabled = state.translationMasterEnabled,
                translationStatus = state.translationStatus,
                translationProgress = state.translationProgress,
                onToggleTranslation = viewModel::toggleTranslation,
                onLongPressTranslation = viewModel::openTranslationLanguageDialog,
                onRetranslate = if (state.isTranslating) viewModel::retranslateCurrentChapter else null,
                // Only the WebView viewer can host the card, so the action is absent elsewhere
                // rather than present and inert.
                onSummarizeChapter = { novelViewer.requestChapterSummary() },
                isTtsActive = isTtsActive,
                isTtsPaused = isTtsPaused,
                ttsEnabled = ttsEnabled,
                ttsControlsVisible = ttsControlsVisible,
                onToggleTtsControls = toggleTtsControls@{
                    if (!ttsEnabled) return@toggleTtsControls
                    val nowVisible = !ttsControlsVisible
                    readerPreferences.novelTtsControlsVisible.set(nowVisible)
                    if (nowVisible) {
                        if (!isTtsActive && readerPreferences.novelTtsAutoStartOnPanelOpen.get()) {
                            startBackgroundTtsIfEnabled()
                            novelViewer.startTts()
                            syncBackgroundTtsState()
                        }
                    } else {
                        stopBackgroundTtsIfRunning()
                        novelViewer.stopTts()
                        stopTtsNotificationSync()
                    }
                },
                onToggleTts = {
                    if (novelViewer.isTtsSpeaking()) {
                        novelViewer.pauseTts()
                        syncBackgroundTtsState()
                    } else if (novelViewer.isTtsPaused()) {
                        novelViewer.resumeTts()
                        startBackgroundTtsIfEnabled()
                        syncBackgroundTtsState()
                    } else {
                        startBackgroundTtsIfEnabled()
                        novelViewer.startTts()
                        syncBackgroundTtsState()
                    }
                },
                onLongPressTts = {
                    // Force stop without hiding panel
                    stopBackgroundTtsIfRunning()
                    novelViewer.stopTts()
                    stopTtsNotificationSync()
                },
                onTtsStartFromViewport = {
                    startBackgroundTtsIfEnabled()
                    novelViewer.startTtsFromViewport()
                    syncBackgroundTtsState()
                },
                onTtsPreviousParagraph = { stepTtsParagraph(isNext = false) },
                onTtsNextParagraph = { stepTtsParagraph(isNext = true) },

                isEditing = isEditing,
                onToggleEdit = {
                    if (isEditing) {
                        if (state.hasUnsavedChanges) {
                            showEditSaveDialog = true
                        } else {
                            isEditing = false
                            novelViewer.toggleEditMode(isEditing = false, save = false)
                        }
                    } else {
                        isEditing = true
                        novelViewer.toggleEditMode(true)
                    }
                },

                isWebView = true,
                bottomBarItems = bottomBarItems,
                onQuotes = ::onQuotesClicked,
                ttsOverlayBottomPadding = ttsOverlayBottomPadding,
            )

            androidx.activity.compose.BackHandler(enabled = isEditing && state.hasUnsavedChanges) {
                showEditSaveDialog = true
            }

            val imeVisible = WindowInsets.isImeVisible
            androidx.activity.compose.BackHandler(enabled = findInPageState != null) {
                if (imeVisible) {
                    dismissFindInPageIme()
                } else {
                    closeFindInPage()
                }
            }

            if (showEditSaveDialog) {
                AlertDialog(
                    onDismissRequest = { showEditSaveDialog = false },
                    title = {
                        Text(
                            tachiyomi.presentation.core.i18n.stringResource(
                                tachiyomi.i18n.novel.TDMR.strings.prompt_save_changes,
                            ),
                        )
                    },
                    text = {
                        Text(
                            tachiyomi.presentation.core.i18n.stringResource(
                                tachiyomi.i18n.novel.TDMR.strings.prompt_save_changes_message,
                            ),
                        )
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            showEditSaveDialog = false
                            isEditing = false
                            novelViewer.toggleEditMode(isEditing = false, save = true)
                        }) {
                            Text(tachiyomi.presentation.core.i18n.stringResource(MR.strings.action_save))
                        }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            showEditSaveDialog = false
                            isEditing = false
                            novelViewer.toggleEditMode(isEditing = false, save = false)
                        }) {
                            Text(
                                tachiyomi.presentation.core.i18n.stringResource(
                                    tachiyomi.i18n.novel.TDMR.strings.action_discard,
                                ),
                            )
                        }
                    },
                )
            }

            if (showBottomBarEditor) {
                val legacyTtsItems = setOf(
                    BottomBarItem.TTS_PREV_PARAGRAPH,
                    BottomBarItem.TTS_NEXT_PARAGRAPH,
                    BottomBarItem.TTS_VIEWPORT,
                )
                BottomBarEditorSheet(
                    items = bottomBarItems.filter { it.item !in legacyTtsItems },
                    onItemsChange = { viewModel.saveBottomBarItems(it) },
                    onDismiss = { showBottomBarEditor = false },
                    itemInfo = { item ->
                        bottomBarItemInfo(
                            item = item,
                            orientation = ReaderOrientation.fromPreference(
                                viewModel.getMangaOrientation(resolveDefault = false),
                            ),
                            isAutoScrolling = isAutoScrolling,
                            isTtsActive = isTtsActive,
                            isTtsPaused = isTtsPaused,
                        )
                    },
                    itemEnabled = { it.isAvailable(ttsEnabled) },
                )
            }
        }
    }

    /**
     * Sets the visibility of the menu according to [visible].
     */
    private fun setMenuVisibility(visible: Boolean) {
        viewModel.showMenus(visible)
        updateSystemBarsVisibility(visible)
        (viewModel.state.value.viewer as? NovelWebViewViewer)?.onMenuVisibilityChanged(visible)
    }

    private fun updateSystemBarsVisibility(menuVisible: Boolean) {
        val videoFullscreen = (viewModel.state.value.viewer as? NovelWebViewViewer)?.isVideoFullscreen == true
        if (videoFullscreen || (!menuVisible && readerPreferences.fullscreen.get())) {
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    internal fun onWebViewVideoFullscreenChanged() {
        updateSystemBarsVisibility(viewModel.state.value.menuVisible)
    }

    private fun startBackgroundTtsIfEnabled() {
        if (!readerPreferences.novelTtsEnabled.get()) return
        if (readerPreferences.novelTtsBackgroundPlayback.get()) {
            // No placeholder notification: the caller's syncBackgroundTtsState() starts
            // the service with the real novel/chapter title.
            startTtsNotificationSync()
        }
    }

    private fun stopBackgroundTtsIfRunning() {
        TtsPlaybackService.stop(this)
        stopTtsNotificationSync()
    }

    private fun syncBackgroundTtsState() {
        if (!readerPreferences.novelTtsEnabled.get() || !readerPreferences.novelTtsBackgroundPlayback.get()) {
            stopBackgroundTtsIfRunning()
            return
        }

        val state = currentNovelTtsState() ?: return
        if (!state.active) {
            TtsPlaybackService.stop(this)
            stopTtsNotificationSync()
            return
        }

        TtsPlaybackService.syncState(
            context = this,
            isPaused = state.paused,
            progressPercent = state.progressPercent,
            novelTitle = state.novelTitle,
            chapterTitle = state.chapterTitle,
            mangaId = state.mangaId,
            chapterId = state.chapterId,
        )
    }

    private fun startTtsNotificationSync() {
        ttsNotificationSyncJob?.cancel()
        ttsNotificationSyncJob = lifecycleScope.launch {
            // First pass runs before the caller sets TTS state. Don't stop the service
            // until TTS has been active once: stopping it before startForeground() crashes
            // with ForegroundServiceDidNotStartInTimeException.
            var ttsWasActive = false
            while (isActive) {
                if (currentNovelTtsState()?.active == true) ttsWasActive = true
                if (ttsWasActive) syncBackgroundTtsState()
                delay(750)
            }
        }
    }

    private fun stopTtsNotificationSync() {
        ttsNotificationSyncJob?.cancel()
        ttsNotificationSyncJob = null
    }

    private data class NovelTtsState(
        val active: Boolean,
        val paused: Boolean,
        val progressPercent: Int,
        val novelTitle: String,
        val chapterTitle: String,
        val mangaId: Long,
        val chapterId: Long,
    )

    private fun currentNovelTtsState(): NovelTtsState? {
        val readerState = viewModel.state.value
        val novelTitle = readerState.manga?.title.orEmpty().ifBlank { "TTS playback" }
        val chapterTitle = readerState.novelVisibleChapter?.name ?: readerState.currentChapter?.chapter?.name.orEmpty()
        val mangaId = readerState.manga?.id ?: -1L
        val chapterId = readerState.currentChapter?.chapter?.id ?: -1L

        val viewer = viewModel.state.value.viewer as? NovelWebViewViewer ?: return null
        return NovelTtsState(
            active = viewer.isTtsActive(),
            paused = viewer.isTtsPaused(),
            progressPercent = viewer.getTtsProgressPercent(),
            novelTitle = novelTitle,
            chapterTitle = chapterTitle,
            mangaId = mangaId,
            chapterId = chapterId,
        )
    }

    private fun stopAnyActiveNovelTts() {
        (viewModel.state.value.viewer as? NovelWebViewViewer)?.let { viewer ->
            if (viewer.isTtsActive()) viewer.stopTts()
        }
    }

    private fun resumeTtsFromNotification() {
        if (!readerPreferences.novelTtsEnabled.get()) {
            stopTtsFromNotification()
            return
        }
        (viewModel.state.value.viewer as? NovelWebViewViewer)?.let { viewer ->
            if (viewer.isTtsPaused()) {
                viewer.resumeTts()
                startBackgroundTtsIfEnabled()
            }
        }
        syncBackgroundTtsState()
    }

    private fun pauseTtsFromNotification() {
        if (!readerPreferences.novelTtsEnabled.get()) return
        (viewModel.state.value.viewer as? NovelWebViewViewer)?.let { viewer ->
            if (viewer.isTtsSpeaking()) viewer.pauseTts()
        }
        syncBackgroundTtsState()
    }

    private fun stepTtsParagraph(isNext: Boolean) {
        if (!readerPreferences.novelTtsEnabled.get()) return
        val viewer = viewModel.state.value.viewer as? NovelWebViewViewer ?: return
        val step = if (isNext) viewer::ttsNextParagraph else viewer::ttsPreviousParagraph
        startBackgroundTtsIfEnabled()
        step()
        syncBackgroundTtsState()
    }

    private fun stopTtsFromNotification() {
        stopAnyActiveNovelTts()
        stopBackgroundTtsIfRunning()
    }

    /**
     * Called from the presenter when a manga is ready. Used to instantiate the appropriate viewer.
     */
    private fun updateViewer() {
        val prevViewer = viewModel.state.value.viewer

        // Manga metadata updates must not rebuild the WebView or reset scroll/TTS.
        //
        // The destroyed check is what makes this safe across an activity recreate. The viewer lives
        // in the ViewModel, which outlives the activity, but onDestroy() has already torn its
        // WebView down - so a surviving viewer of the right type can still be a spent one. Reusing
        // it leaves viewerContainer empty and the reader black. Test the object, not just its type.
        if (prevViewer is NovelWebViewViewer && !prevViewer.isDestroyed) {
            setOrientation(viewModel.getMangaOrientation())
            return
        }

        val newViewer = NovelWebViewViewer(this)

        if (window.sharedElementEnterTransition is MaterialContainerTransform) {
            // Wait until transition is complete to avoid crash on API 26
            window.sharedElementEnterTransition.doOnEnd {
                setOrientation(viewModel.getMangaOrientation())
            }
        } else {
            setOrientation(viewModel.getMangaOrientation())
        }

        // Destroy previous viewer if there was one
        if (prevViewer != null) {
            prevViewer.destroy()
            binding.viewerContainer.removeAllViews()
        }
        viewModel.onViewerLoaded(newViewer)
        updateViewerInset(readerPreferences.fullscreen.get(), readerPreferences.drawUnderCutout.get())
        binding.viewerContainer.addView(newViewer.getView())

        loadingIndicator = ReaderProgressIndicator(this)
        binding.readerContainer.addView(loadingIndicator)

        startPostponedEnterTransition()
    }

    private fun openMangaScreen() {
        viewModel.manga?.id?.let { id ->
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    action = Constants.SHORTCUT_MANGA
                    putExtra(Constants.MANGA_EXTRA, id)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
            )
        }
    }

    private fun openChapterInWebView() {
        val manga = viewModel.manga ?: return
        assistUrl?.let {
            val intent = WebViewActivity.newIntent(this@ReaderActivity, it, viewModel.getSource()?.id, manga.title)
            startActivity(intent)
        }
    }

    private fun openChapterInBrowser() {
        assistUrl?.let {
            openInBrowser(it.toUri(), forceDefaultBrowser = false)
        }
    }

    private fun shareChapter() {
        assistUrl?.let {
            val intent = it.toUri().toShareIntent(this, type = "text/plain")
            startActivity(intent)
        }
    }

    /**
     * Called from the presenter whenever a new [viewerChapters] have been set. It delegates the
     * method to the current viewer, but also set the subtitle on the toolbar, and
     * hides or disables the reader prev/next buttons if there's a prev or next chapter
     */
    @SuppressLint("RestrictedApi")
    private fun setChapters(viewerChapters: ViewerChapters) {
        binding.readerContainer.removeView(loadingIndicator)
        viewModel.state.value.viewer?.setChapters(viewerChapters)

        lifecycleScope.launchIO {
            viewModel.getChapterUrl()?.let { url ->
                assistUrl = url
            }
        }
    }

    /**
     * Called from the presenter if the initial load couldn't load the pages of the chapter. In
     * this case the activity is closed and a toast is shown to the user.
     */
    private fun setInitialChapterError(error: Throwable) {
        logcat(LogPriority.ERROR, error)
        finish()
        toast(error.message)
    }

    /**
     * Moves the viewer to the given page [index]. It does nothing if the viewer is null or the
     * page is not found.
     */
    private fun moveToPageIndex(index: Int) {
        val viewer = viewModel.state.value.viewer ?: return
        val currentChapter = viewModel.state.value.currentChapter ?: return
        val page = currentChapter.pages?.getOrNull(index) ?: return
        viewer.moveToPage(page)
    }

    /**
     * Tells the presenter to load the next chapter and mark it as active. The progress dialog
     * should be automatically shown.
     */
    internal fun loadNextChapter() {
        loadNextChapterInternal(ReaderNavigationSource.USER)
    }

    /**
     * Loads the next chapter for a TTS auto-advance without stopping TTS. The viewer
     * has set pendingTtsAutoStart and needs it to survive the chapter swap so playback
     * resumes; [loadNextChapter] would clear it via [stopNovelTtsForManualNav].
     */
    internal fun loadNextChapterForTtsHandoff() {
        loadNextChapterInternal(ReaderNavigationSource.AUTOMATIC)
    }

    private fun loadNextChapterInternal(source: ReaderNavigationSource) {
        val request = viewModel.beginChapterNavigation(source) ?: return
        if (source == ReaderNavigationSource.USER) {
            stopNovelTtsForManualNav()
        }
        lifecycleScope.launch {
            try {
                val committed = viewModel.loadNextChapter(request)
                if (!committed) return@launch
                (viewModel.state.value.viewer as? NovelWebViewViewer)?.onChapterNavigate("next")
                // Only reset to page 0 if NOT using infinite scroll for novel viewers
                val novelViewer = viewModel.state.value.viewer as? NovelWebViewViewer
                if (novelViewer?.isInfiniteScrollEnabled() == true) {
                    viewModel.state.value.viewerChapters?.currChapter?.chapter?.id?.let { chapterId ->
                        novelViewer.scrollToLoadedChapter(chapterId)
                    }
                } else {
                    moveToPageIndex(0)
                }
            } finally {
                viewModel.finishChapterNavigation(request)
            }
        }
    }

    /**
     * Tells the presenter to load the previous chapter and mark it as active. The progress dialog
     * should be automatically shown.
     */
    internal fun loadPreviousChapter() {
        val request = viewModel.beginChapterNavigation(ReaderNavigationSource.USER) ?: return
        stopNovelTtsForManualNav()
        lifecycleScope.launch {
            try {
                val committed = viewModel.loadPreviousChapter(request)
                if (!committed) return@launch
                (viewModel.state.value.viewer as? NovelWebViewViewer)?.onChapterNavigate("prev")
                // Only reset to page 0 if NOT using infinite scroll for novel viewers
                val novelViewer = viewModel.state.value.viewer as? NovelWebViewViewer
                if (novelViewer?.isInfiniteScrollEnabled() == true) {
                    viewModel.state.value.viewerChapters?.currChapter?.chapter?.id?.let { chapterId ->
                        novelViewer.scrollToLoadedChapter(chapterId)
                    }
                } else {
                    moveToPageIndex(0)
                }
            } finally {
                viewModel.finishChapterNavigation(request)
            }
        }
    }

    /**
     * Stops any in-flight TTS session before a user-driven prev/next chapter
     * navigation. TTS-internal handoffs (auto-advance) go through
     * `loadNextChapterForTts` instead of this code path, so it's safe to
     * unconditionally cut TTS here without disturbing automatic advancement.
     */
    private fun stopNovelTtsForManualNav() {
        (viewModel.state.value.viewer as? NovelWebViewViewer)?.stopTts()
    }

    /**
     * Called from the viewer whenever a [page] is marked as active. It updates the values of the
     * bottom menu and delegates the change to the presenter.
     */
    fun onPageSelected(page: ReaderPage) {
        viewModel.onPageSelected(page)
    }

    /**
     * Called from the novel viewer to save reading progress with a percentage.
     * Progress is stored as percentage (0-100) in last_page_read.
     */
    fun saveNovelProgress(page: ReaderPage, progressPercentage: Int) {
        viewModel.saveNovelProgress(page, progressPercentage)
    }

    /**
     * Called from the novel viewer when scroll progress changes.
     * Updates the progress slider in real-time.
     */
    fun onNovelProgressChanged(progress: Float) {
        val percentage = (progress * 100).roundToInt().coerceIn(0, 100)
        viewModel.updateNovelProgressPercent(percentage)
    }

    /**
     * Called from the viewer to toggle the visibility of the menu. It's implemented on the
     * viewer because each one implements its own touch and key events.
     */
    fun toggleMenu() {
        setMenuVisibility(!viewModel.state.value.menuVisible)
    }

    /**
     * Called from the viewer to show the menu.
     */
    fun showMenu() {
        if (!viewModel.state.value.menuVisible) {
            setMenuVisibility(true)
        }
    }

    /**
     * Check if translation mode is currently enabled.
     */
    fun isTranslationEnabled(): Boolean {
        return viewModel.state.value.isTranslating
    }

    /** Flashes the existing display-refresh overlay after a settled WebView page turn. */
    fun onNovelVisualPageChanged() {
        if (
            readerPreferences.novelReadingLayout.get() == NovelReadingLayout.PAGED &&
            readerPreferences.flashOnPageChange.get()
        ) {
            displayRefreshHost.flash()
        }
    }

    /** Whether a cached translation exists for [chapterId]; viewers use it to pick the loading label. */
    suspend fun hasCachedTranslation(chapterId: Long?): Boolean {
        if (chapterId == null || !isTranslationEnabled()) return false
        return try {
            viewModel.hasCachedTranslation(chapterId)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "hasCachedTranslation lookup failed" }
            false
        }
    }

    /**
     * Reload content with current translation state.
     * Called when translation is toggled to re-render existing content.
     */
    private fun reloadContentWithTranslation() {
        val state = viewModel.state.value
        val viewer = state.viewer

        (viewer as? NovelWebViewViewer)?.reloadWithTranslation()
            ?: state.viewerChapters?.let(::setChapters)
    }

    /**
     * Translate text content using the translation service.
     * Returns translated text if translation is enabled and successful,
     * otherwise returns original text.
     */
    suspend fun translateContentIfEnabled(content: String, chapterId: Long? = null): String {
        if (!isTranslationEnabled()) return content
        return try {
            viewModel.translateContent(content, chapterId)
        } catch (e: CancellationException) {
            logcat(LogPriority.DEBUG) { "Translation was cancelled" }
            content
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Translation failed" }
            runOnUiThread {
                viewModel.disableTranslation()
                toast(e.message ?: stringResource(TDMR.strings.reader_translation_failed))
            }
            content
        }
    }

    /**
     * Called when the quotes button is clicked.
     */
    fun onQuotesClicked() {
        showQuotesSheet = true
    }

    /**
     * Called when the "Remember" action is triggered.
     * Gets selected text from the current viewer and adds it as a quote.
     */
    fun onRememberSelectedText() {
        val viewer = viewModel.state.value.viewer as? NovelWebViewViewer
        val selectedText = viewer?.pendingSelectedText ?: viewer?.getSelectedText()
        val paragraphIndex = viewer?.pendingParagraphIndex
        val chapterName = viewer?.getCurrentChapterName()

        if (selectedText != null && chapterName != null) {
            viewModel.saveQuote(selectedText, chapterName, paragraphIndex)
            viewer.run {
                clearTextSelection()
                pendingSelectedText = null
                pendingParagraphIndex = null
            }
            toast(TDMR.strings.quotes_saved)
        } else {
            toast(TDMR.strings.reader_no_text_selected)
        }
    }

    /**
     * Forces the user preferred [orientation] on the activity.
     */
    private fun setOrientation(orientation: Int) {
        val newOrientation = ReaderOrientation.fromPreference(orientation)
        if (newOrientation.flag != requestedOrientation) {
            requestedOrientation = newOrientation.flag
        }
    }

    /**
     * Updates viewer inset depending on fullscreen reader preferences.
     */
    private fun updateViewerInset(fullscreen: Boolean, drawUnderCutout: Boolean) {
        if (!::binding.isInitialized) return
        val view = binding.viewerContainer

        view.applyInsetsPadding(ViewCompat.getRootWindowInsets(view), fullscreen, drawUnderCutout)
        ViewCompat.setOnApplyWindowInsetsListener(view) { view, windowInsets ->
            view.applyInsetsPadding(windowInsets, fullscreen, drawUnderCutout)
            windowInsets
        }
    }

    private fun View.applyInsetsPadding(
        windowInsets: WindowInsetsCompat?,
        fullscreen: Boolean,
        drawUnderCutout: Boolean,
    ) {
        val insets = when {
            !fullscreen -> windowInsets?.getInsets(WindowInsetsCompat.Type.systemBars())
            !drawUnderCutout -> windowInsets?.getInsets(WindowInsetsCompat.Type.displayCutout())
            else -> null
        }
            ?: Insets.NONE

        setPadding(insets.left, insets.top, insets.right, insets.bottom)
    }

    /**
     * Class that handles the user preferences of the reader.
     */
    private inner class ReaderConfig {

        private fun getCombinedPaint(grayscale: Boolean, invertedColors: Boolean): Paint {
            return Paint().apply {
                colorFilter = ColorMatrixColorFilter(
                    ColorMatrix().apply {
                        if (grayscale) {
                            setSaturation(0f)
                        }
                        if (invertedColors) {
                            postConcat(
                                ColorMatrix(
                                    floatArrayOf(
                                        -1f, 0f, 0f, 0f, 255f,
                                        0f, -1f, 0f, 0f, 255f,
                                        0f, 0f, -1f, 0f, 255f,
                                        0f, 0f, 0f, 1f, 0f,
                                    ),
                                ),
                            )
                        }
                    },
                )
            }
        }

        private val grayBackgroundColor = Color.rgb(0x20, 0x21, 0x25)

        private var brightnessJob: Job? = null

        /*
         * Initializes the reader subscriptions.
         */
        init {
            readerPreferences.readerTheme.changes()
                .onEach { theme ->
                    binding.readerContainer.setBackgroundColor(
                        when (theme) {
                            0 -> Color.WHITE
                            2 -> grayBackgroundColor
                            3 -> automaticBackgroundColor()
                            else -> Color.BLACK
                        },
                    )
                }
                .launchIn(lifecycleScope)

            readerPreferences.keepScreenOn.changes()
                .onEach(::setKeepScreenOn)
                .launchIn(lifecycleScope)

            readerPreferences.customBrightness.changes()
                .onEach(::setCustomBrightness)
                .launchIn(lifecycleScope)

            // Novel-specific brightness
            readerPreferences.novelCustomBrightness.changes()
                .onEach(::setNovelCustomBrightness)
                .launchIn(lifecycleScope)

            // Novel-specific keep screen on
            readerPreferences.novelKeepScreenOn.changes()
                .onEach { enabled ->
                    val viewer = viewModel.state.value.viewer
                    if (viewer is NovelWebViewViewer) {
                        setKeepScreenOn(enabled)
                    }
                }
                .launchIn(lifecycleScope)

            // Apply novel brightness and keep screen on when viewer changes to a novel viewer
            viewModel.state
                .map { it.viewer }
                .distinctUntilChanged()
                .filterNotNull()
                .onEach { viewer ->
                    if (viewer is NovelWebViewViewer) {
                        setNovelCustomBrightness(readerPreferences.novelCustomBrightness.get())
                        setKeepScreenOn(readerPreferences.novelKeepScreenOn.get())
                    } else {
                        // Switch back to manga reader settings for non-novel viewers. Must also
                        // resync brightness here, not just keep-screen-on: setNovelCustomBrightness
                        // above shares brightnessJob with setCustomBrightness, and if novel custom
                        // brightness was left active, leaving this branch to touch only
                        // keepScreenOn would leave that job running and applying novel-preference
                        // brightness to the new non-novel viewer.
                        setCustomBrightness(readerPreferences.customBrightness.get())
                        setKeepScreenOn(readerPreferences.keepScreenOn.get())
                    }
                }
                .launchIn(lifecycleScope)

            combine(
                readerPreferences.grayscale.changes(),
                readerPreferences.invertedColors.changes(),
            ) { grayscale, invertedColors -> grayscale to invertedColors }
                .onEach { (grayscale, invertedColors) ->
                    setLayerPaint(grayscale, invertedColors)
                }
                .launchIn(lifecycleScope)

            combine(
                readerPreferences.fullscreen.changes(),
                readerPreferences.drawUnderCutout.changes(),
            ) { fullscreen, drawUnderCutout -> fullscreen to drawUnderCutout }
                .onEach { (fullscreen, drawUnderCutout) ->
                    updateViewerInset(fullscreen, drawUnderCutout)
                }
                .launchIn(lifecycleScope)
        }

        /**
         * Picks background color for [ReaderActivity] based on light/dark theme preference
         */
        private fun automaticBackgroundColor(): Int {
            return if (baseContext.isNightMode()) {
                grayBackgroundColor
            } else {
                Color.WHITE
            }
        }

        /**
         * Sets the keep screen on mode according to [enabled].
         */
        private fun setKeepScreenOn(enabled: Boolean) {
            if (enabled) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        /**
         * Sets the custom brightness overlay according to [enabled].
         */
        private fun setCustomBrightness(enabled: Boolean) {
            // Skip if using novel viewer with its own brightness setting
            val viewer = viewModel.state.value.viewer
            if (viewer is NovelWebViewViewer) {
                return
            }
            brightnessJob?.cancel()
            brightnessJob = if (enabled) {
                readerPreferences.customBrightnessValue.changes()
                    .sample(0.1.seconds)
                    .onEach(::setCustomBrightnessValue)
                    .launchIn(lifecycleScope)
            } else {
                setCustomBrightnessValue(0)
                null
            }
        }

        /**
         * Sets the novel-specific custom brightness overlay according to [enabled].
         */
        private fun setNovelCustomBrightness(enabled: Boolean) {
            // Only apply if using novel viewer
            val viewer = viewModel.state.value.viewer
            if (viewer !is NovelWebViewViewer) {
                return
            }
            brightnessJob?.cancel()
            brightnessJob = if (enabled) {
                readerPreferences.novelCustomBrightnessValue.changes()
                    .sample(100)
                    .onEach(::setCustomBrightnessValue)
                    .launchIn(lifecycleScope)
            } else {
                setCustomBrightnessValue(0)
                null
            }
        }

        /**
         * Sets the brightness of the screen. Range is [-75, 100].
         * From -75 to -1 a semi-transparent black view is overlaid with the minimum brightness.
         * From 1 to 100 it sets that value as brightness.
         * 0 sets system brightness and hides the overlay.
         */
        private fun setCustomBrightnessValue(value: Int) {
            // Calculate and set reader brightness.
            val readerBrightness = when {
                value > 0 -> {
                    value / 100f
                }
                value < 0 -> {
                    0.01f
                }
                else -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
            window.attributes = window.attributes.apply { screenBrightness = readerBrightness }

            viewModel.setBrightnessOverlayValue(value)
        }
        private fun setLayerPaint(grayscale: Boolean, invertedColors: Boolean) {
            val paint = if (grayscale || invertedColors) getCombinedPaint(grayscale, invertedColors) else null
            binding.viewerContainer.setLayerType(LAYER_TYPE_HARDWARE, paint)
        }
    }
}
