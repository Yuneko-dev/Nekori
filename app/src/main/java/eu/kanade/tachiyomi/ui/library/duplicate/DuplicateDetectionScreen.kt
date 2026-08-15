package eu.kanade.tachiyomi.ui.library.duplicate

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.library.DeleteLibraryMangaDialog
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import kotlinx.coroutines.launch
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.toCommonCheckboxState
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.core.common.i18n.stringResource as ctxStringResource
import tachiyomi.domain.category.model.Category as CategoryModel

class DuplicateDetectionScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val clipboard: Clipboard = LocalClipboard.current
        val snackbarHostState = remember { SnackbarHostState() }

        val screenModel = viewModel<DuplicateDetectionViewModel>()
        val state by screenModel.state.collectAsState()

        val listState = rememberLazyListState()
        val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

        var searchOpen by rememberSaveable { mutableStateOf(false) }
        var showFilterSheet by rememberSaveable { mutableStateOf(false) }

        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                if (state.selection.isNotEmpty()) {
                    // Material 3 contextual action bar: selection replaces the toolbar rather than
                    // appending icons to it, and the count becomes the title.
                    AppBar(
                        title = null,
                        actionModeCounter = state.selection.size,
                        onCancelActionMode = screenModel::clearSelection,
                        actionModeActions = {
                            AppBarActions(
                                actions = selectionActions(
                                    screenModel = screenModel,
                                    onCopyLinks = {
                                        scope.launch {
                                            val urls = screenModel.getSelectedUrls()
                                            clipboard.setClipEntry(
                                                ClipData.newPlainText(
                                                    context.ctxStringResource(MR.strings.duplicate_copy_links),
                                                    urls.joinToString("\n"),
                                                ).toClipEntry(),
                                            )
                                            snackbarHostState.showSnackbar(
                                                context.ctxStringResource(
                                                    MR.strings.duplicate_urls_copied,
                                                    urls.size,
                                                ),
                                            )
                                        }
                                    },
                                ),
                            )
                        },
                        scrollBehavior = scrollBehavior,
                    )
                } else {
                    SearchToolbar(
                        titleContent = {
                            AppBarTitle(
                                title = stringResource(MR.strings.duplicate_find_duplicates),
                                subtitle = state.resultsSubtitle(),
                            )
                        },
                        navigateUp = navigator::pop,
                        searchQuery = state.searchQuery.takeIf { searchOpen },
                        onChangeSearchQuery = { query ->
                            searchOpen = query != null
                            screenModel.setSearchQuery(query.orEmpty())
                        },
                        searchEnabled = state.hasStartedAnalysis && state.duplicateGroups.isNotEmpty(),
                        actions = {
                            AppBarActions(
                                actions = buildList {
                                    add(
                                        AppBar.Action(
                                            title = stringResource(MR.strings.duplicate_filters_sort),
                                            icon = Icons.Outlined.FilterList,
                                            onClick = { showFilterSheet = true },
                                        ),
                                    )
                                    if (state.hasStartedAnalysis) {
                                        add(
                                            AppBar.OverflowAction(
                                                title = stringResource(MR.strings.duplicate_reanalyze),
                                                onClick = screenModel::loadDuplicates,
                                            ),
                                        )
                                    }
                                    // Nothing to select until results exist.
                                    if (state.filteredDuplicateGroups.isNotEmpty()) {
                                        addAll(selectionStrategyActions(screenModel))
                                    }
                                },
                            )
                        },
                        scrollBehavior = scrollBehavior,
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { contentPadding ->
            when {
                !state.hasStartedAnalysis -> EmptyScreen(
                    message = stringResource(MR.strings.duplicate_initial_description),
                    actions = listOf(
                        EmptyScreenAction(
                            stringRes = MR.strings.duplicate_start_analysis,
                            icon = Icons.Filled.PlayArrow,
                            onClick = screenModel::loadDuplicates,
                        ),
                    ),
                    modifier = Modifier.padding(contentPadding),
                )

                state.isLoading -> LoadingWithProgress(
                    progress = state.precheckProgress,
                    modifier = Modifier.padding(contentPadding),
                )

                state.filteredDuplicateGroups.isEmpty() -> EmptyScreen(
                    message = stringResource(
                        if (state.duplicateGroups.isEmpty()) {
                            MR.strings.duplicate_no_duplicates
                        } else {
                            MR.strings.duplicate_no_matches_filter
                        },
                    ),
                    actions = listOf(
                        EmptyScreenAction(
                            stringRes = MR.strings.duplicate_reanalyze,
                            icon = Icons.Filled.PlayArrow,
                            onClick = screenModel::loadDuplicates,
                        ),
                    ),
                    modifier = Modifier.padding(contentPadding),
                )

                else -> {
                    // LazyListScope is not a composable scope, so the string resolves out here.
                    val truncationWarning = state.truncationWarning()
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = contentPadding,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        truncationWarning?.let { warning ->
                            item(key = "truncation_warning") {
                                Text(
                                    text = warning,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                            }
                        }

                        items(
                            state.filteredDuplicateGroups.toList(),
                            key = { it.first },
                        ) { (title, mangaList) ->
                            val selectableGroupIds = state.selectableGroupIds(title, mangaList)
                            DuplicateGroupCard(
                                groupTitle = title,
                                mangaList = mangaList,
                                fullGroupCount = state.fullGroupCountFor(title, mangaList.size),
                                canSelectHiddenTail = state.canIncludeHiddenTail,
                                allSelected = selectableGroupIds.isNotEmpty() &&
                                    state.selection.containsAll(selectableGroupIds),
                                selection = state.selection,
                                mangaCategories = state.mangaCategories,
                                showFullUrls = state.showFullUrls,
                                onToggleSelection = screenModel::toggleSelection,
                                onSelectGroup = { screenModel.selectGroup(title) },
                                onDismissGroup = { screenModel.dismissGroup(title) },
                                onClickManga = { navigator.push(MangaScreen(it)) },
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                    }
                }
            }
        }

        if (showFilterSheet) {
            DuplicateFilterSheet(
                state = state,
                screenModel = screenModel,
                onDismissRequest = { showFilterSheet = false },
                onOpenSourcePriority = {
                    showFilterSheet = false
                    navigator.push(SourcePriorityScreen)
                },
            )
        }

        if (state.showDeleteDialog) {
            DeleteLibraryMangaDialog(
                containsLocalManga = screenModel.selectionContainsLocalManga(),
                onDismissRequest = screenModel::closeDeleteDialog,
                onConfirm = {
                        removeFromLibrary,
                        deleteDownloads,
                        clearChaptersFromDb,
                        deleteTranslations,
                        clearCovers,
                        clearDescriptions,
                        clearTags,
                    ->
                    val count = state.selection.size
                    scope.launch {
                        screenModel.deleteSelected(
                            removeFromLibrary = removeFromLibrary,
                            deleteDownloads = deleteDownloads,
                            clearChaptersFromDb = clearChaptersFromDb,
                            deleteTranslations = deleteTranslations,
                            clearCovers = clearCovers,
                            clearDescriptions = clearDescriptions,
                            clearTags = clearTags,
                        )
                        snackbarHostState.showSnackbar(
                            context.ctxStringResource(MR.strings.duplicate_deleted_count, count),
                        )
                    }
                },
            )
        }

        if (state.showMoveToCategoryDialog) {
            ChangeCategoryDialog(
                initialSelection = remember(state.selection, state.mangaCategoryIdSets, state.categories) {
                    categorySelectionFor(state)
                },
                onDismissRequest = screenModel::closeMoveToCategoryDialog,
                onEditCategories = {
                    screenModel.closeMoveToCategoryDialog()
                    navigator.push(CategoryScreen())
                },
                onConfirm = { addCategories, removeCategories ->
                    if (addCategories.isNotEmpty() || removeCategories.isNotEmpty()) {
                        val count = state.selection.size
                        scope.launch {
                            val success = screenModel.moveSelectedToCategories(addCategories, removeCategories)
                            snackbarHostState.showSnackbar(
                                if (success) {
                                    context.ctxStringResource(MR.strings.duplicate_moved_count, count)
                                } else {
                                    context.ctxStringResource(MR.strings.duplicate_move_failed)
                                },
                            )
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun LoadingWithProgress(
    progress: Pair<Int, Int>?,
    modifier: Modifier = Modifier,
) {
    if (progress == null) {
        LoadingScreen(modifier = modifier.fillMaxSize())
        return
    }
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        CircularProgressIndicator()
        Text(
            text = stringResource(TDMR.strings.duplicate_checking_progress, progress.first, progress.second),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Actions shown while a selection exists: what you can *do* with the selected entries. */
@Composable
private fun selectionActions(
    screenModel: DuplicateDetectionViewModel,
    onCopyLinks: () -> Unit,
): List<AppBar.AppBarAction> = buildList {
    add(
        AppBar.Action(
            title = stringResource(MR.strings.duplicate_copy_links),
            icon = Icons.Filled.ContentCopy,
            onClick = onCopyLinks,
        ),
    )
    add(
        AppBar.Action(
            title = stringResource(MR.strings.duplicate_move_to_category),
            icon = Icons.AutoMirrored.Filled.DriveFileMove,
            onClick = screenModel::openMoveToCategoryDialog,
        ),
    )
    add(
        AppBar.Action(
            title = stringResource(MR.strings.duplicate_delete_selected),
            icon = Icons.Filled.Delete,
            onClick = screenModel::openDeleteDialog,
        ),
    )
    addAll(selectionStrategyActions(screenModel))
}

/**
 * Bulk-selection strategies. They both start and refine a selection, so the same list is offered in
 * the plain toolbar and in the contextual one; a FAB is wrong for them because none is the screen's
 * single primary action.
 */
@Composable
private fun selectionStrategyActions(
    screenModel: DuplicateDetectionViewModel,
): List<AppBar.OverflowAction> = listOf(
    MR.strings.duplicate_select_all to screenModel::selectAllDuplicates,
    MR.strings.duplicate_select_all_except_first to screenModel::selectAllExceptFirst,
    MR.strings.duplicate_select_lowest_ch to screenModel::selectLowestChapterCount,
    MR.strings.duplicate_select_highest_ch to screenModel::selectHighestChapterCount,
    MR.strings.duplicate_select_lowest_dl to screenModel::selectLowestDownloadCount,
    MR.strings.duplicate_select_highest_dl to screenModel::selectHighestDownloadCount,
    MR.strings.duplicate_select_lowest_read to screenModel::selectLowestReadCount,
    MR.strings.duplicate_select_highest_read to screenModel::selectHighestReadCount,
    MR.strings.duplicate_select_lowest_priority to screenModel::selectLowestSourcePriority,
    MR.strings.duplicate_select_highest_priority to screenModel::selectHighestSourcePriority,
    MR.strings.duplicate_select_pinned to screenModel::selectPinnedInGroups,
    MR.strings.duplicate_select_non_pinned to screenModel::selectNonPinnedInGroups,
    MR.strings.duplicate_invert_selection to screenModel::invertSelection,
).map { (labelRes, action) ->
    AppBar.OverflowAction(title = stringResource(labelRes), onClick = action)
}

@Composable
private fun DuplicateDetectionViewModel.State.resultsSubtitle(): String? {
    if (!hasStartedAnalysis || filteredDuplicateGroups.isEmpty()) return null
    val summary = stringResource(
        MR.strings.duplicate_results_summary,
        filteredDuplicateGroups.size,
        filteredDuplicateGroups.values.sumOf { it.size },
    )
    val filtered = selectedCategoryFilters.isNotEmpty() || excludedCategoryFilters.isNotEmpty()
    return summary + if (filtered) stringResource(MR.strings.duplicate_results_filtered) else ""
}

/** The one truncation notice that applies, or null. Listing and scan modes can never both truncate. */
@Composable
private fun DuplicateDetectionViewModel.State.truncationWarning(): String? = when {
    listingMode && listingTruncated ->
        stringResource(MR.strings.duplicate_listing_truncated, listingTotalMatches)
    !listingMode && scanGroupsTruncated ->
        stringResource(MR.strings.duplicate_scan_groups_truncated, duplicateGroups.size, scanTotalGroups)
    else -> null
}

/** Members the scan found for this group, which can exceed the materialized rows on screen. */
private fun DuplicateDetectionViewModel.State.fullGroupCountFor(title: String, shownCount: Int): Int {
    val materialized = duplicateGroups[title]?.size ?: shownCount
    val scanned = allGroupIds[title]?.size ?: materialized
    return if (scanned > materialized) scanned else shownCount
}

private fun categorySelectionFor(
    state: DuplicateDetectionViewModel.State,
): List<CheckboxState<CategoryModel>> {
    val selectedIds = state.selection
    if (selectedIds.isEmpty()) return state.categories.map { CheckboxState.State.None(it) }

    // Hidden-tail selections have no materialized category data; skip them rather than assuming
    // Uncategorized, which would wrongly drag a category that's actually common down to "mixed".
    val perManga = selectedIds.mapNotNull { state.mangaCategoryIdSets[it] }
    return state.categories.toCommonCheckboxState({ it.id }, perManga)
}
