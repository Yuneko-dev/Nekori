package eu.kanade.tachiyomi.ui.download

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.animateFloatingActionButton
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.translation.TranslationService
import eu.kanade.tachiyomi.util.system.copyToClipboard
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

private enum class DownloadQueueFilter {
    All,
    Active,
    Errors,
}

class DownloadQueueScreen(private val initialTab: Int = 0) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = viewModel<DownloadQueueViewModel>()
        val novelList by screenModel.novelState.collectAsState()
        val pausedGroups by screenModel.pausedNovelMangaIds.collectAsState()
        val titleMaxLines by screenModel.titleMaxLines.collectAsState()

        val translationService = remember { Injekt.get<TranslationService>() }
        val translationProgress by translationService.progressState.collectAsState()
        val translationPaused by translationService.isPaused.collectAsState()
        val translationQueue by translationService.queueState.collectAsState()
        val currentTranslatingChapterId by translationService.currentTranslatingChapterId.collectAsState()

        var selectedTab by remember { mutableStateOf(initialTab.coerceIn(0, 1)) }
        var filterMode by remember { mutableStateOf(DownloadQueueFilter.All) }
        val canReorder = filterMode == DownloadQueueFilter.All

        val filteredNovelList by remember(novelList, filterMode) {
            derivedStateOf {
                when (filterMode) {
                    DownloadQueueFilter.All -> novelList
                    DownloadQueueFilter.Active -> novelList.filter {
                        it.isActive ||
                            it.subItems.any { d -> d.status == Download.State.QUEUE }
                    }
                    DownloadQueueFilter.Errors -> novelList.filter { it.hasError }
                }
            }
        }

        val novelCount by remember {
            derivedStateOf { novelList.sumOf { it.totalChapters } }
        }

        val tabs = listOf(
            "${stringResource(TDMR.strings.label_novels)} ($novelCount)",
            "${stringResource(TDMR.strings.label_translations)} (${translationQueue.size})",
        )

        val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
        var fabExpanded by remember { mutableStateOf(true) }
        val nestedScrollConnection = remember {
            // All this lines just for fab state :/
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    fabExpanded = available.y >= 0
                    return scrollBehavior.nestedScrollConnection.onPreScroll(available, source)
                }

                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    return scrollBehavior.nestedScrollConnection.onPostScroll(consumed, available, source)
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    return scrollBehavior.nestedScrollConnection.onPreFling(available)
                }

                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                    return scrollBehavior.nestedScrollConnection.onPostFling(consumed, available)
                }
            }
        }

        Scaffold(
            topBar = {
                AppBar(
                    titleContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(MR.strings.label_download_queue),
                                maxLines = 1,
                                modifier = Modifier.weight(1f, false),
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    navigateUp = navigator::pop,
                    actions = {
                        if (selectedTab == 1) {
                            val translationActions = buildList {
                                if (translationProgress.isRunning && !translationProgress.isCancelling) {
                                    add(
                                        AppBar.Action(
                                            title = stringResource(
                                                if (translationPaused) {
                                                    TDMR.strings.pref_translation_resume
                                                } else {
                                                    TDMR.strings.pref_translation_pause
                                                },
                                            ),
                                            icon = if (translationPaused) {
                                                Icons.Filled.PlayArrow
                                            } else {
                                                Icons.Outlined.Pause
                                            },
                                            onClick = {
                                                if (translationPaused) {
                                                    translationService.resume()
                                                } else {
                                                    translationService.pause()
                                                }
                                            },
                                        ),
                                    )
                                    add(
                                        AppBar.Action(
                                            title = stringResource(TDMR.strings.pref_translation_cancel),
                                            icon = Icons.Filled.Stop,
                                            onClick = { translationService.cancel() },
                                        ),
                                    )
                                }
                                if (translationQueue.isNotEmpty()) {
                                    add(
                                        AppBar.Action(
                                            title = stringResource(TDMR.strings.pref_translation_clear_queue),
                                            icon = Icons.Filled.DeleteSweep,
                                            onClick = { translationService.clearQueue() },
                                        ),
                                    )
                                }
                            }
                            AppBarActions(translationActions.toList())
                        } else if (filteredNovelList.isNotEmpty()) {
                            var sortExpanded by remember { mutableStateOf(false) }
                            var filterExpanded by remember { mutableStateOf(false) }
                            val onDismissRequest = { sortExpanded = false }

                            DropdownMenu(
                                expanded = filterExpanded,
                                onDismissRequest = { filterExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(text = stringResource(MR.strings.all)) },
                                    onClick = {
                                        filterMode = DownloadQueueFilter.All
                                        filterExpanded = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(text = stringResource(MR.strings.ext_downloading)) },
                                    onClick = {
                                        filterMode = DownloadQueueFilter.Active
                                        filterExpanded = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(text = stringResource(MR.strings.channel_errors)) },
                                    onClick = {
                                        filterMode = DownloadQueueFilter.Errors
                                        filterExpanded = false
                                    },
                                )
                            }

                            DropdownMenu(
                                expanded = sortExpanded && canReorder,
                                onDismissRequest = onDismissRequest,
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(text = stringResource(TDMR.strings.action_order_by_progress))
                                    },
                                    onClick = {
                                        val order = novelList
                                            .sortedByDescending { it.overallProgress }
                                            .map { it.mangaId }
                                        screenModel.reorderNovelQueueByGroupOrder(order)
                                        onDismissRequest()
                                    },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(text = stringResource(TDMR.strings.action_order_by_total_chapters))
                                    },
                                    onClick = {
                                        val order = novelList
                                            .sortedByDescending { it.totalChapters }
                                            .map { it.mangaId }
                                        screenModel.reorderNovelQueueByGroupOrder(order)
                                        onDismissRequest()
                                    },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(text = stringResource(TDMR.strings.action_order_by_extension))
                                    },
                                    onClick = {
                                        val order = novelList
                                            .sortedWith(
                                                compareBy<NovelDownloadItem>(
                                                    { it.sourceName.lowercase() },
                                                    { it.mangaTitle.lowercase() },
                                                ),
                                            )
                                            .map { it.mangaId }
                                        screenModel.reorderNovelQueueByGroupOrder(order)
                                        onDismissRequest()
                                    },
                                )
                            }

                            AppBarActions(
                                listOf(
                                    AppBar.Action(
                                        title = stringResource(MR.strings.action_filter),
                                        icon = Icons.Outlined.FilterList,
                                        onClick = { filterExpanded = true },
                                    ),
                                    AppBar.Action(
                                        title = stringResource(MR.strings.action_sort),
                                        icon = Icons.AutoMirrored.Outlined.Sort,
                                        onClick = { if (canReorder) sortExpanded = true },
                                    ),
                                    AppBar.OverflowAction(
                                        title = stringResource(MR.strings.action_cancel_all),
                                        onClick = { screenModel.clearQueue() },
                                    ),
                                ),
                            )
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
            floatingActionButton = {
                val isRunning by screenModel.isDownloaderRunning.collectAsState()
                SmallExtendedFloatingActionButton(
                    text = {
                        val id = if (isRunning) {
                            MR.strings.action_pause
                        } else {
                            MR.strings.action_resume
                        }
                        Text(text = stringResource(id))
                    },
                    icon = {
                        val icon = if (isRunning) {
                            Icons.Outlined.Pause
                        } else {
                            Icons.Filled.PlayArrow
                        }
                        Icon(imageVector = icon, contentDescription = null)
                    },
                    onClick = {
                        if (isRunning) {
                            screenModel.pauseDownloads()
                        } else {
                            screenModel.startDownloads()
                        }
                    },
                    expanded = fabExpanded,
                    modifier = Modifier.animateFloatingActionButton(
                        visible = selectedTab == 0 && novelList.isNotEmpty(),
                        alignment = Alignment.BottomEnd,
                    ),
                )
            },
        ) { contentPadding ->
            Column(modifier = Modifier.padding(top = contentPadding.calculateTopPadding())) {
                TabRow(selectedTabIndex = selectedTab) {
                    tabs.forEachIndexed { index, titleRes ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(titleRes) },
                        )
                    }
                }

                if ((selectedTab == 0 && filteredNovelList.isEmpty()) ||
                    (selectedTab == 1 && translationQueue.isEmpty() && !translationProgress.isRunning)
                ) {
                    EmptyScreen(
                        stringRes = if (selectedTab == 1) {
                            TDMR.strings.pref_translation_status_idle
                        } else {
                            MR.strings.information_no_downloads
                        },
                    )
                } else {
                    Box(modifier = Modifier.nestedScroll(nestedScrollConnection)) {
                        if (selectedTab == 1) {
                            TranslationQueueContent(
                                progress = translationProgress,
                                isPaused = translationPaused,
                                queueItems = translationQueue,
                                currentTranslatingChapterId = currentTranslatingChapterId,
                                onMoveMangaToTop = translationService::moveMangaToFront,
                                onRemoveAllForManga = translationService::dequeueAllForManga,
                                onRemoveTask = translationService::dequeue,
                            )
                        } else {
                            androidx.compose.foundation.lazy.LazyColumn(
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    top = 8.dp,
                                    bottom = 80.dp,
                                    start = 16.dp,
                                    end = 16.dp,
                                ),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                items(
                                    items = filteredNovelList,
                                    key = { it.mangaId },
                                ) { item ->
                                    NovelDownloadCard(
                                        item = item,
                                        titleMaxLines = titleMaxLines,
                                        isPaused = item.mangaId in pausedGroups,
                                        onPauseResume = {
                                            if (item.mangaId in pausedGroups) {
                                                screenModel.resumeNovelGroup(item.mangaId)
                                            } else {
                                                screenModel.pauseNovelGroup(item.mangaId)
                                            }
                                        },
                                        onCancel = { screenModel.cancel(item.subItems) },
                                        onMoveToTop = {
                                            screenModel.reorder(
                                                item.subItems + (
                                                    novelList.flatMap {
                                                        it.subItems
                                                    } - item.subItems.toSet()
                                                    ),
                                            )
                                        },
                                        onMoveToBottom = {
                                            screenModel.reorder(
                                                (
                                                    novelList.flatMap {
                                                        it.subItems
                                                    } - item.subItems.toSet()
                                                    ) + item.subItems,
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NovelDownloadCard(
    item: NovelDownloadItem,
    titleMaxLines: Int,
    isPaused: Boolean,
    onPauseResume: () -> Unit,
    onCancel: () -> Unit,
    onMoveToTop: () -> Unit,
    onMoveToBottom: () -> Unit,
) {
    val context = LocalContext.current
    val errorLabel = stringResource(MR.strings.download_notifier_title_error)
    var showMenu by remember { mutableStateOf(false) }
    var errorsExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (item.isActive) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.sourceName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = item.mangaTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = titleMaxLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(
                            TDMR.strings.novel_downloads_chapters_format,
                            item.downloadedChapters,
                            item.totalChapters,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(TDMR.strings.novel_downloads_more_options),
                        )
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (isPaused) MR.strings.action_resume else MR.strings.action_pause,
                                    ),
                                )
                            },
                            onClick = {
                                onPauseResume()
                                showMenu = false
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (isPaused) {
                                        Icons.Filled.PlayArrow
                                    } else {
                                        Icons.Outlined.Pause
                                    },
                                    contentDescription = null,
                                )
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(TDMR.strings.novel_downloads_move_top)) },
                            onClick = {
                                onMoveToTop()
                                showMenu = false
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowUp,
                                    contentDescription = null,
                                )
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(TDMR.strings.novel_downloads_move_bottom)) },
                            onClick = {
                                onMoveToBottom()
                                showMenu = false
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                )
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(MR.strings.action_cancel)) },
                            onClick = {
                                onCancel()
                                showMenu = false
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = null,
                                )
                            },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Progress bar
            LinearProgressIndicator(
                progress = { item.overallProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(MaterialTheme.shapes.small),
                color = when {
                    item.hasError -> MaterialTheme.colorScheme.error
                    item.isActive -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.secondary
                },
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Status row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (isPaused) stringResource(MR.strings.paused) else item.statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        item.hasError -> MaterialTheme.colorScheme.error
                        isPaused -> MaterialTheme.colorScheme.onSurfaceVariant
                        item.isActive -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )

                val currentDownload = item.currentDownload
                if (currentDownload != null) {
                    Text(
                        text = stringResource(
                            TDMR.strings.novel_downloads_chapter_format,
                            currentDownload.chapterName,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).padding(start = 8.dp),
                    )
                }

                Text(
                    text = "${(item.overallProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (item.hasError) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(
                            TDMR.strings.novel_downloads_failed_chapters,
                            item.erroredDownloads.size,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    IconButton(
                        onClick = { context.copyToClipboard(errorLabel, item.fullErrorReport) },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = stringResource(TDMR.strings.novel_downloads_copy_error),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                val collapsedLimit = 2
                val visibleErrors = if (errorsExpanded) {
                    item.errorDetails
                } else {
                    item.errorDetails.take(collapsedLimit)
                }
                visibleErrors.forEach { (chapter, reason) ->
                    Text(
                        text = "$chapter: $reason",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val hiddenCount = item.errorDetails.size - collapsedLimit
                if (hiddenCount > 0) {
                    Text(
                        text = if (errorsExpanded) {
                            stringResource(TDMR.strings.novel_downloads_show_less)
                        } else {
                            stringResource(TDMR.strings.novel_downloads_more_errors, hiddenCount)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { errorsExpanded = !errorsExpanded }
                            .padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}
