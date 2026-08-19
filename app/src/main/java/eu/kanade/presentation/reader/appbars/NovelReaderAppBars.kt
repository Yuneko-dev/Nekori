package eu.kanade.presentation.reader.appbars

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.automirrored.outlined.NavigateBefore
import androidx.compose.material.icons.automirrored.outlined.NavigateNext
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.FastRewind
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.VerticalAlignTop
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.tachiyomi.ui.reader.NovelFindInPageState
import eu.kanade.tachiyomi.ui.reader.TranslationUiStatus
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.components.material.Slider
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

private val readerBarsSlideAnimationSpec = tween<IntOffset>(200)
private val readerBarsFadeAnimationSpec = tween<Float>(150)
private const val PROGRESS_SLIDER_MODE_HORIZONTAL = "horizontal"
private const val PROGRESS_SLIDER_MODE_VERTICAL_LEFT = "vertical_left"
private const val PROGRESS_SLIDER_MODE_VERTICAL_RIGHT = "vertical_right"
private const val VERTICAL_PROGRESS_SIZE_HALF = "half"
private val VERTICAL_PROGRESS_CONTAINER_WIDTH = 6.dp
private val VERTICAL_PROGRESS_EDGE_INSET = 3.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NovelReaderAppBars(
    visible: Boolean,
    findInPageState: NovelFindInPageState?,
    onFindInPage: () -> Unit,
    onFindQueryChange: (String) -> Unit,
    onFindPrevious: () -> Unit,
    onFindNext: () -> Unit,
    onCloseFindInPage: () -> Unit,

    // Top bar
    novelTitle: String?,
    chapterTitle: String?,
    navigateUp: () -> Unit,
    onClickTopAppBar: () -> Unit,
    bookmarked: Boolean,
    onToggleBookmarked: () -> Unit,
    onOpenInWebView: (() -> Unit)?,
    onOpenInBrowser: (() -> Unit)?,
    onShare: (() -> Unit)?,
    onReloadLocal: () -> Unit,
    onReloadSource: () -> Unit,
    onEditBottomBar: () -> Unit,

    // Progress slider
    showProgressSlider: Boolean,
    progressSliderMode: String,
    verticalProgressSliderSize: String,
    currentProgress: Int, // 0-100 percentage
    onProgressChange: (Int) -> Unit,

    // Bottom bar - navigation
    onNextChapter: () -> Unit,
    enabledNext: Boolean,
    onPreviousChapter: () -> Unit,
    enabledPrevious: Boolean,
    onOpenChapterDrawer: () -> Unit,

    // Bottom bar - actions
    orientation: ReaderOrientation,
    onClickOrientation: () -> Unit,
    onClickSettings: () -> Unit,
    onScrollToTop: () -> Unit,
    isAutoScrolling: Boolean,
    onToggleAutoScroll: () -> Unit,
    isTranslating: Boolean,
    translationMasterEnabled: Boolean,
    translationStatus: TranslationUiStatus,
    translationProgress: Float,
    onToggleTranslation: () -> Unit,
    onLongPressTranslation: () -> Unit,
    onRetranslate: (() -> Unit)? = null,
    onSummarizeChapter: (() -> Unit)? = null,
    isTtsActive: Boolean,
    isTtsPaused: Boolean,
    ttsEnabled: Boolean,
    ttsControlsVisible: Boolean,
    onToggleTtsControls: () -> Unit,
    onToggleTts: () -> Unit,
    onLongPressTts: () -> Unit,
    onTtsStartFromViewport: () -> Unit = {},
    onTtsPreviousParagraph: () -> Unit = {},
    onTtsNextParagraph: () -> Unit = {},

    isEditing: Boolean = false,
    onToggleEdit: () -> Unit = {},
    isWebView: Boolean = true,
    onQuotes: () -> Unit,

    // Toolbar customization
    bottomBarItems: List<BottomBarItemState>,

    // Extra bottom offset for the floating TTS overlay so it clears the status bar
    ttsOverlayBottomPadding: Dp = 0.dp,

    // Measured bar heights in px (0 while hidden), so the page can inset fixed elements clear of the
    // transient reader menu bars.
    onTopBarHeight: (Int) -> Unit = {},
    onBottomBarHeight: (Int) -> Unit = {},
) {
    val backgroundColor = MaterialTheme.colorScheme
        .surfaceColorAtElevation(3.dp)
        .copy(alpha = if (isSystemInDarkTheme()) 0.9f else 0.95f)

    // onSizeChanged doesn't fire a final 0 when AnimatedVisibility removes the bars, so clear the
    // reported heights here when the menu hides.
    val findInPageOpen = findInPageState != null

    LaunchedEffect(visible, findInPageOpen) {
        if (!visible && !findInPageOpen) {
            onTopBarHeight(0)
        }
        if (!visible || findInPageOpen) {
            onBottomBarHeight(0)
        }
    }

    Box(modifier = Modifier.fillMaxHeight()) {
        Column(modifier = Modifier.fillMaxHeight()) {
            AnimatedVisibility(
                visible = visible || findInPageOpen,
                enter = slideInVertically(initialOffsetY = { -it }, animationSpec = readerBarsSlideAnimationSpec) +
                    fadeIn(animationSpec = readerBarsFadeAnimationSpec),
                exit = slideOutVertically(targetOffsetY = { -it }, animationSpec = readerBarsSlideAnimationSpec) +
                    fadeOut(animationSpec = readerBarsFadeAnimationSpec),
            ) {
                if (findInPageState != null) {
                    NovelFindInPageBar(
                        state = findInPageState,
                        onQueryChange = onFindQueryChange,
                        onPrevious = onFindPrevious,
                        onNext = onFindNext,
                        onClose = onCloseFindInPage,
                        modifier = Modifier
                            .onSizeChanged { onTopBarHeight(it.height) }
                            .background(backgroundColor),
                    )
                } else {
                    NovelReaderTopBar(
                        modifier = Modifier
                            .onSizeChanged { onTopBarHeight(it.height) }
                            .background(backgroundColor)
                            .clickable(onClick = onClickTopAppBar),
                        novelTitle = novelTitle,
                        chapterTitle = chapterTitle,
                        navigateUp = navigateUp,
                        bookmarked = bookmarked,
                        onToggleBookmarked = onToggleBookmarked,
                        onFindInPage = onFindInPage,
                        onOpenInWebView = onOpenInWebView,
                        onOpenInBrowser = onOpenInBrowser,
                        onShare = onShare,
                        onReloadLocal = onReloadLocal,
                        onReloadSource = onReloadSource,
                        onEditBottomBar = onEditBottomBar,
                        onRetranslate = onRetranslate,
                        onSummarizeChapter = onSummarizeChapter,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (!findInPageOpen && visible && showProgressSlider &&
                    (
                        progressSliderMode == PROGRESS_SLIDER_MODE_VERTICAL_LEFT ||
                            progressSliderMode == PROGRESS_SLIDER_MODE_VERTICAL_RIGHT
                        )
                ) {
                    val alignment = if (progressSliderMode == PROGRESS_SLIDER_MODE_VERTICAL_LEFT) {
                        Alignment.CenterStart
                    } else {
                        Alignment.CenterEnd
                    }
                    val heightFraction = if (verticalProgressSliderSize == VERTICAL_PROGRESS_SIZE_HALF) {
                        0.5f
                    } else {
                        1f
                    }
                    NovelVerticalProgressSlider(
                        modifier = Modifier
                            .align(alignment)
                            .padding(horizontal = VERTICAL_PROGRESS_EDGE_INSET, vertical = MaterialTheme.padding.small)
                            .fillMaxHeight(heightFraction),
                        currentProgress = currentProgress,
                        onProgressChange = onProgressChange,
                        backgroundColor = backgroundColor,
                    )
                }

                if (!findInPageOpen && ttsEnabled && ttsControlsVisible) {
                    NovelTtsControlsOverlay(
                        isTtsActive = isTtsActive,
                        isTtsPaused = isTtsPaused,
                        onPauseResume = onToggleTts,
                        onPrevParagraph = onTtsPreviousParagraph,
                        onNextParagraph = onTtsNextParagraph,
                        onStartFromViewport = onTtsStartFromViewport,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = MaterialTheme.padding.small + ttsOverlayBottomPadding),
                    )
                }
            }

            AnimatedVisibility(
                visible = visible && !findInPageOpen,
                enter = slideInVertically(initialOffsetY = { it }, animationSpec = readerBarsSlideAnimationSpec) +
                    fadeIn(animationSpec = readerBarsFadeAnimationSpec),
                exit = slideOutVertically(targetOffsetY = { it }, animationSpec = readerBarsSlideAnimationSpec) +
                    fadeOut(animationSpec = readerBarsFadeAnimationSpec),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onSizeChanged { onBottomBarHeight(it.height) }
                        .background(backgroundColor)
                        .windowInsetsPadding(WindowInsets.navigationBars),
                ) {
                    if (showProgressSlider && progressSliderMode == PROGRESS_SLIDER_MODE_HORIZONTAL) {
                        NovelProgressSlider(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = MaterialTheme.padding.medium),
                            currentProgress = currentProgress,
                            onProgressChange = onProgressChange,
                            backgroundColor = backgroundColor,
                        )
                    }

                    NovelReaderBottomBar(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = MaterialTheme.padding.small),
                        items = bottomBarItems,
                        onOpenInWebView = onOpenInWebView,
                        onShare = onShare,
                        onNextChapter = onNextChapter,
                        enabledNext = enabledNext,
                        onPreviousChapter = onPreviousChapter,
                        enabledPrevious = enabledPrevious,
                        onOpenChapterDrawer = onOpenChapterDrawer,
                        orientation = orientation,
                        onClickOrientation = onClickOrientation,
                        onClickSettings = onClickSettings,
                        onScrollToTop = onScrollToTop,
                        isAutoScrolling = isAutoScrolling,
                        onToggleAutoScroll = onToggleAutoScroll,
                        isTranslating = isTranslating,
                        translationMasterEnabled = translationMasterEnabled,
                        translationStatus = translationStatus,
                        translationProgress = translationProgress,
                        onToggleTranslation = onToggleTranslation,
                        onLongPressTranslation = onLongPressTranslation,
                        isTtsActive = isTtsActive,
                        ttsEnabled = ttsEnabled,
                        ttsControlsVisible = ttsControlsVisible,
                        onToggleTtsControls = onToggleTtsControls,
                        onLongPressTts = onLongPressTts,
                        isEditing = isEditing,
                        isWebView = isWebView,
                        onToggleEdit = onToggleEdit,
                        onQuotes = onQuotes,
                    )
                }
            }
        }
    }
}

@Composable
private fun NovelFindInPageBar(
    state: NovelFindInPageState,
    onQueryChange: (String) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(state.focusRequestId) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    AppBar(
        modifier = modifier,
        backgroundColor = Color.Transparent,
        titleContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            if (state.hasMatches) onNext()
                        },
                    ),
                    singleLine = true,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (state.query.isEmpty()) {
                                Text(
                                    text = stringResource(TDMR.strings.action_find_in_chapter),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                            innerTextField()
                        }
                    },
                )
                Text(
                    text = state.statusText,
                    modifier = Modifier
                        .padding(start = MaterialTheme.padding.small)
                        .widthIn(min = 40.dp),
                    color = if (state.isNoMatch) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        actions = {
            IconButton(
                enabled = state.hasMatches,
                onClick = onPrevious,
            ) {
                Icon(
                    imageVector = Icons.Outlined.KeyboardArrowUp,
                    contentDescription = stringResource(TDMR.strings.find_previous_match),
                )
            }
            IconButton(
                enabled = state.hasMatches,
                onClick = onNext,
            ) {
                Icon(
                    imageVector = Icons.Outlined.KeyboardArrowDown,
                    contentDescription = stringResource(TDMR.strings.find_next_match),
                )
            }
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(MR.strings.action_close),
                )
            }
        },
    )
}

@Composable
private fun NovelReaderTopBar(
    novelTitle: String?,
    chapterTitle: String?,
    navigateUp: () -> Unit,
    bookmarked: Boolean,
    onToggleBookmarked: () -> Unit,
    onFindInPage: () -> Unit,
    onOpenInWebView: (() -> Unit)?,
    onOpenInBrowser: (() -> Unit)?,
    onShare: (() -> Unit)?,
    onReloadLocal: () -> Unit,
    onReloadSource: () -> Unit,
    onEditBottomBar: () -> Unit,
    onRetranslate: (() -> Unit)? = null,
    onSummarizeChapter: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    AppBar(
        modifier = modifier,
        backgroundColor = Color.Transparent,
        title = novelTitle,
        subtitle = chapterTitle,
        navigateUp = navigateUp,
        actions = {
            AppBarActions(
                actions = buildList {
                    add(
                        AppBar.Action(
                            title = stringResource(
                                if (bookmarked) {
                                    MR.strings.action_remove_bookmark
                                } else {
                                    MR.strings.action_bookmark
                                },
                            ),
                            icon = if (bookmarked) {
                                Icons.Outlined.Bookmark
                            } else {
                                Icons.Outlined.BookmarkBorder
                            },
                            onClick = onToggleBookmarked,
                        ),
                    )
                    add(
                        AppBar.OverflowAction(
                            title = stringResource(TDMR.strings.action_find_in_chapter),
                            onClick = onFindInPage,
                        ),
                    )
                    add(
                        AppBar.OverflowAction(
                            title = stringResource(TDMR.strings.action_reload_local),
                            onClick = onReloadLocal,
                        ),
                    )
                    add(
                        AppBar.OverflowAction(
                            title = stringResource(TDMR.strings.action_reload_source),
                            onClick = onReloadSource,
                        ),
                    )
                    add(
                        AppBar.OverflowAction(
                            title = stringResource(TDMR.strings.action_edit_appbar),
                            onClick = onEditBottomBar,
                        ),
                    )
                    onOpenInWebView?.let {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.action_open_in_web_view),
                                onClick = it,
                            ),
                        )
                    }
                    onOpenInBrowser?.let {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.action_open_in_browser),
                                onClick = it,
                            ),
                        )
                    }
                    onShare?.let {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.action_share),
                                onClick = it,
                            ),
                        )
                    }
                    onRetranslate?.let {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(TDMR.strings.action_retranslate),
                                onClick = it,
                            ),
                        )
                    }
                    onSummarizeChapter?.let {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(TDMR.strings.action_summarize_chapter),
                                onClick = it,
                            ),
                        )
                    }
                },
            )
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NovelReaderBottomBar(
    items: List<BottomBarItemState>,
    onNextChapter: () -> Unit,
    enabledNext: Boolean,
    onPreviousChapter: () -> Unit,
    enabledPrevious: Boolean,
    onOpenChapterDrawer: () -> Unit,
    orientation: ReaderOrientation,
    onClickOrientation: () -> Unit,
    onClickSettings: () -> Unit,
    onScrollToTop: () -> Unit,
    isAutoScrolling: Boolean,
    onToggleAutoScroll: () -> Unit,
    isTranslating: Boolean,
    translationMasterEnabled: Boolean,
    translationStatus: TranslationUiStatus,
    translationProgress: Float,
    onToggleTranslation: () -> Unit,
    onLongPressTranslation: () -> Unit,
    isTtsActive: Boolean,
    ttsEnabled: Boolean,
    ttsControlsVisible: Boolean,
    onToggleTtsControls: () -> Unit,
    onLongPressTts: () -> Unit,
    isEditing: Boolean,
    isWebView: Boolean,
    onToggleEdit: () -> Unit,
    onQuotes: () -> Unit,
    onOpenInWebView: (() -> Unit)?,
    onShare: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val enabledItems = remember(items, isWebView, ttsEnabled, translationMasterEnabled, onOpenInWebView, onShare) {
        items.filter {
            it.enabled &&
                it.item.isAvailable(ttsEnabled) &&
                (isWebView || it.item != BottomBarItem.EDIT) &&
                (translationMasterEnabled || it.item != BottomBarItem.TRANSLATE) &&
                (onOpenInWebView != null || it.item != BottomBarItem.WEBVIEW) &&
                (onShare != null || it.item != BottomBarItem.SHARE)
        }
    }

    Box(modifier = modifier) {
        val iconSize = 24.dp
        val buttonSize = 48.dp
        val paddingSize = 4.dp

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            enabledItems.forEach { itemState ->
                when (itemState.item) {
                    BottomBarItem.PREV_CHAPTER -> IconButton(
                        onClick = onPreviousChapter,
                        enabled = enabledPrevious,
                        modifier = Modifier.size(buttonSize),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.NavigateBefore,
                            contentDescription = stringResource(MR.strings.action_previous_chapter),
                            modifier = Modifier.size(iconSize),
                        )
                    }

                    BottomBarItem.NEXT_CHAPTER -> IconButton(
                        onClick = onNextChapter,
                        enabled = enabledNext,
                        modifier = Modifier.size(buttonSize),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.NavigateNext,
                            contentDescription = stringResource(MR.strings.action_next_chapter),
                            modifier = Modifier.size(iconSize),
                        )
                    }

                    BottomBarItem.CHAPTER_LIST -> IconButton(
                        onClick = onOpenChapterDrawer,
                        modifier = Modifier.size(buttonSize),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.FormatListBulleted,
                            contentDescription = stringResource(MR.strings.action_view_chapters),
                            modifier = Modifier.size(iconSize),
                        )
                    }

                    // Scroll to top
                    BottomBarItem.SCROLL_TO_TOP -> IconButton(
                        onClick = onScrollToTop,
                        modifier = Modifier.size(buttonSize),
                    ) {
                        Icon(
                            Icons.Outlined.VerticalAlignTop,
                            contentDescription = stringResource(TDMR.strings.action_scroll_to_top),
                            modifier = Modifier.size(iconSize),
                        )
                    }

                    // Translation toggle - tap for quick translate, long-press for language picker
                    BottomBarItem.TRANSLATE -> androidx.compose.material3.Surface(
                        modifier = Modifier
                            .size(buttonSize)
                            .padding(paddingSize),
                        shape = MaterialTheme.shapes.small,
                        color = if (isTranslating) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                    ) {
                        Box(
                            modifier = Modifier.combinedClickable(
                                onClick = onToggleTranslation,
                                onLongClick = onLongPressTranslation,
                            ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Translate,
                                contentDescription = stringResource(TDMR.strings.action_translate),
                                tint = if (isTranslating) {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier.size(iconSize),
                            )
                            if (translationStatus == TranslationUiStatus.TRANSLATED) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = null,
                                    modifier = Modifier.align(Alignment.BottomEnd).size(11.dp),
                                )
                            }
                            if (translationStatus == TranslationUiStatus.LOADING) {
                                val rotation by rememberInfiniteTransition(label = "translation-progress").animateFloat(
                                    initialValue = 0f,
                                    targetValue = 360f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(durationMillis = 6_000, easing = LinearEasing),
                                        repeatMode = RepeatMode.Restart,
                                    ),
                                    label = "translation-progress-rotation",
                                )
                                CircularProgressIndicator(
                                    progress = { translationProgress.coerceIn(0.02f, 0.99f) },
                                    modifier = Modifier
                                        .size(iconSize + 8.dp)
                                        .rotate(rotation),
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                    strokeWidth = 2.dp,
                                )
                            }
                        }
                    }

                    // Auto-scroll toggle
                    BottomBarItem.AUTO_SCROLL -> IconButton(
                        onClick = onToggleAutoScroll,
                        modifier = Modifier.size(buttonSize),
                    ) {
                        Icon(
                            imageVector = if (isAutoScrolling) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,
                            contentDescription = stringResource(
                                if (isAutoScrolling) {
                                    TDMR.strings.action_stop_auto_scroll
                                } else {
                                    TDMR.strings.action_start_auto_scroll
                                },
                            ),
                            modifier = Modifier.size(iconSize),
                        )
                    }

                    // TTS toggle - tap to show/hide controls overlay, long press to stop
                    BottomBarItem.TTS -> androidx.compose.material3.Surface(
                        modifier = Modifier
                            .size(buttonSize)
                            .padding(paddingSize),
                        shape = MaterialTheme.shapes.small,
                        color = if (ttsControlsVisible) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            Color.Transparent
                        },
                    ) {
                        Box(
                            modifier = Modifier.combinedClickable(
                                onClick = onToggleTtsControls,
                                onLongClick = onLongPressTts,
                            ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = if (ttsControlsVisible) {
                                    Icons.Outlined.VolumeUp
                                } else {
                                    Icons.Outlined.RecordVoiceOver
                                },
                                contentDescription = stringResource(TDMR.strings.pref_novel_tts),
                                tint = if (ttsControlsVisible) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier.size(iconSize),
                            )
                        }
                    }

                    // Legacy items kept in enum for serialization compat — no longer rendered
                    BottomBarItem.TTS_PREV_PARAGRAPH,
                    BottomBarItem.TTS_NEXT_PARAGRAPH,
                    BottomBarItem.TTS_VIEWPORT,
                    -> Unit

                    // Orientation
                    BottomBarItem.ORIENTATION -> IconButton(
                        onClick = onClickOrientation,
                        modifier = Modifier.size(buttonSize),
                    ) {
                        Icon(
                            orientation.icon,
                            contentDescription = stringResource(MR.strings.rotation_type),
                            modifier = Modifier.size(iconSize),
                        )
                    }

                    // Settings
                    BottomBarItem.SETTINGS -> IconButton(
                        onClick = onClickSettings,
                        modifier = Modifier.size(buttonSize),
                    ) {
                        Icon(
                            Icons.Outlined.Settings,
                            contentDescription = stringResource(MR.strings.action_settings),
                            modifier = Modifier.size(iconSize),
                        )
                    }

                    // Quotes
                    BottomBarItem.QUOTES -> IconButton(
                        onClick = onQuotes,
                        modifier = Modifier.size(buttonSize),
                    ) {
                        Icon(
                            Icons.Outlined.FormatQuote,
                            contentDescription = stringResource(TDMR.strings.action_quotes),
                            modifier = Modifier.size(iconSize),
                        )
                    }

                    // Edit
                    BottomBarItem.EDIT -> androidx.compose.material3.Surface(
                        modifier = Modifier
                            .size(buttonSize)
                            .padding(paddingSize),
                        shape = MaterialTheme.shapes.small,
                        color = if (isEditing) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        border = if (isEditing) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                    ) {
                        IconButton(onClick = onToggleEdit) {
                            Icon(
                                Icons.Outlined.Edit,
                                contentDescription = stringResource(MR.strings.action_edit),
                                tint = if (isEditing) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier.size(iconSize),
                            )
                        }
                    }

                    // Filtered out above when the source has no web support, so the callbacks are
                    // non-null by the time an item reaches here.
                    BottomBarItem.WEBVIEW -> IconButton(
                        onClick = { onOpenInWebView?.invoke() },
                        modifier = Modifier.size(buttonSize),
                    ) {
                        Icon(
                            Icons.Outlined.Public,
                            contentDescription = stringResource(MR.strings.action_open_in_web_view),
                            modifier = Modifier.size(iconSize),
                        )
                    }

                    BottomBarItem.SHARE -> IconButton(
                        onClick = { onShare?.invoke() },
                        modifier = Modifier.size(buttonSize),
                    ) {
                        Icon(
                            Icons.Outlined.Share,
                            contentDescription = stringResource(MR.strings.action_share),
                            modifier = Modifier.size(iconSize),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NovelTtsControlsOverlay(
    isTtsActive: Boolean,
    isTtsPaused: Boolean,
    onPauseResume: () -> Unit,
    onPrevParagraph: () -> Unit,
    onNextParagraph: () -> Unit,
    onStartFromViewport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onStartFromViewport) {
            Icon(
                Icons.Outlined.Visibility,
                contentDescription = stringResource(TDMR.strings.reader_tts_read_from_here),
                modifier = Modifier.size(22.dp),
            )
        }
        IconButton(onClick = onPrevParagraph) {
            Icon(
                Icons.Outlined.FastRewind,
                contentDescription = stringResource(TDMR.strings.tts_prev_paragraph),
                modifier = Modifier.size(22.dp),
            )
        }
        IconButton(onClick = onPauseResume) {
            Icon(
                imageVector = if (isTtsActive && !isTtsPaused) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                contentDescription = stringResource(TDMR.strings.pref_novel_tts),
                modifier = Modifier.size(22.dp),
            )
        }
        IconButton(onClick = onNextParagraph) {
            Icon(
                Icons.Outlined.FastForward,
                contentDescription = stringResource(TDMR.strings.tts_next_paragraph),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

// Resolves display icon + label per item — keeps the when() out of the sheet
@Composable
internal fun bottomBarItemInfo(
    item: BottomBarItem,
    orientation: ReaderOrientation,
    isAutoScrolling: Boolean,
    isTtsActive: Boolean,
    isTtsPaused: Boolean,
): Pair<ImageVector, String> = when (item) {
    BottomBarItem.PREV_CHAPTER ->
        Icons.AutoMirrored.Outlined.NavigateBefore to
            stringResource(MR.strings.action_previous_chapter)
    BottomBarItem.NEXT_CHAPTER ->
        Icons.AutoMirrored.Outlined.NavigateNext to
            stringResource(MR.strings.action_next_chapter)
    BottomBarItem.CHAPTER_LIST ->
        Icons.AutoMirrored.Outlined.FormatListBulleted to stringResource(MR.strings.action_view_chapters)
    BottomBarItem.SCROLL_TO_TOP -> Icons.Outlined.VerticalAlignTop to stringResource(TDMR.strings.action_scroll_to_top)
    BottomBarItem.TRANSLATE -> Icons.Outlined.Translate to stringResource(TDMR.strings.action_translate)
    BottomBarItem.AUTO_SCROLL -> Icons.Outlined.PlayArrow to stringResource(TDMR.strings.action_start_auto_scroll)
    BottomBarItem.TTS -> Icons.Outlined.RecordVoiceOver to stringResource(TDMR.strings.pref_novel_tts)
    BottomBarItem.TTS_VIEWPORT -> Icons.Outlined.Visibility to "Start TTS Here"
    BottomBarItem.TTS_PREV_PARAGRAPH -> Icons.Outlined.FastRewind to stringResource(TDMR.strings.tts_prev_paragraph)
    BottomBarItem.TTS_NEXT_PARAGRAPH -> Icons.Outlined.FastForward to stringResource(TDMR.strings.tts_next_paragraph)
    BottomBarItem.QUOTES -> Icons.Outlined.FormatQuote to stringResource(TDMR.strings.action_quotes)
    BottomBarItem.ORIENTATION -> orientation.icon to stringResource(MR.strings.rotation_type)
    BottomBarItem.SETTINGS -> Icons.Outlined.Settings to stringResource(MR.strings.action_settings)
    BottomBarItem.EDIT -> Icons.Outlined.Edit to stringResource(MR.strings.action_edit)
    BottomBarItem.WEBVIEW -> Icons.Outlined.Public to stringResource(MR.strings.action_open_in_web_view)
    BottomBarItem.SHARE -> Icons.Outlined.Share to stringResource(MR.strings.action_share)
}

@Composable
private fun NovelProgressSlider(
    currentProgress: Int,
    onProgressChange: (Int) -> Unit,
    backgroundColor: Color,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val sliderDragged by interactionSource.collectIsDraggedAsState()

    LaunchedEffect(currentProgress) {
        if (sliderDragged) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(backgroundColor)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Current progress percentage
        Box(contentAlignment = Alignment.CenterEnd) {
            Text(text = "$currentProgress%")
            // Taking up full length so the slider doesn't shift
            Text(text = "100%", color = Color.Transparent)
        }

        Slider(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
            value = currentProgress,
            valueRange = 0..100,
            onValueChange = { newProgress ->
                if (newProgress != currentProgress) {
                    onProgressChange(newProgress)
                }
            },
            interactionSource = interactionSource,
        )

        Text(text = "100%")
    }
}

@Composable
private fun NovelVerticalProgressSlider(
    currentProgress: Int,
    onProgressChange: (Int) -> Unit,
    backgroundColor: Color,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    var measuredTrackHeightPx by remember { mutableStateOf(0) }

    fun progressFromOffset(y: Float, totalHeight: Float): Int {
        if (totalHeight <= 0f) return currentProgress
        val clamped = y.coerceIn(0f, totalHeight)
        return ((clamped / totalHeight) * 100f).toInt().coerceIn(0, 100)
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(backgroundColor)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .width(VERTICAL_PROGRESS_CONTAINER_WIDTH)
                .onSizeChanged { size ->
                    measuredTrackHeightPx = size.height
                },
            contentAlignment = Alignment.Center,
        ) {
            val trackHeightPx = measuredTrackHeightPx.toFloat().coerceAtLeast(1f)
            val progressFraction = (currentProgress / 100f).coerceIn(0f, 1f)

            // Vertical track
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(4.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)),
            )

            // Filled progress from top down
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(progressFraction)
                    .width(4.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )

            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(VERTICAL_PROGRESS_CONTAINER_WIDTH)
                    .pointerInput(measuredTrackHeightPx) {
                        detectTapGestures { offset ->
                            val newProgress = progressFromOffset(offset.y, trackHeightPx)
                            if (newProgress != currentProgress) {
                                onProgressChange(newProgress)
                            }
                        }
                    }
                    .pointerInput(measuredTrackHeightPx) {
                        var lastSentProgress = currentProgress
                        detectVerticalDragGestures(
                            onDragStart = { offset ->
                                val newProgress = progressFromOffset(offset.y, trackHeightPx)
                                if (newProgress != lastSentProgress) {
                                    onProgressChange(newProgress)
                                    lastSentProgress = newProgress
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            },
                            onVerticalDrag = { change, _ ->
                                val newProgress = progressFromOffset(change.position.y, trackHeightPx)
                                if (newProgress != lastSentProgress) {
                                    onProgressChange(newProgress)
                                    lastSentProgress = newProgress
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            },
                        )
                    },
            )
        }
    }
}
