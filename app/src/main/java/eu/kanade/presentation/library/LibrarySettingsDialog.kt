@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.presentation.library

import android.content.res.Configuration
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.TabbedDialog
import eu.kanade.presentation.components.TabbedDialogPaddings
import eu.kanade.presentation.components.toTabTitles
import eu.kanade.tachiyomi.ui.library.LibrarySettingsViewModel
import eu.kanade.tachiyomi.util.system.isReleaseBuildType
import kotlinx.coroutines.delay
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.model.sort
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.components.BaseSortItem
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.components.SortItem
import tachiyomi.presentation.core.components.TriStateItem
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

@Composable
fun LibrarySettingsDialog(
    onDismissRequest: () -> Unit,
    viewModel: LibrarySettingsViewModel,
    category: Category?,
) {
    TabbedDialog(
        onDismissRequest = onDismissRequest,
        tabTitles = listOf(
            stringResource(MR.strings.action_filter),
            stringResource(MR.strings.action_sort),
            stringResource(MR.strings.action_display),
            stringResource(TDMR.strings.edit_label_tags),
            stringResource(MR.strings.label_extensions),
        ).toTabTitles(),
    ) { page ->
        if (page == 3) {
            Column(
                modifier = Modifier
                    .padding(vertical = TabbedDialogPaddings.Vertical),
            ) {
                TagsPage(viewModel = viewModel)
            }
        } else {
            Column(
                modifier = Modifier
                    .padding(vertical = TabbedDialogPaddings.Vertical)
                    .verticalScroll(rememberScrollState()),
            ) {
                when (page) {
                    0 -> FilterPage(
                        viewModel = viewModel,
                    )
                    1 -> SortPage(
                        category = category,
                        viewModel = viewModel,
                    )
                    2 -> DisplayPage(
                        viewModel = viewModel,
                    )
                    3 -> TagsPage(
                        viewModel = viewModel,
                    )
                    4 -> ExtensionsPage(
                        viewModel = viewModel,
                    )
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.FilterPage(
    viewModel: LibrarySettingsViewModel,
) {
    val filterDownloaded by viewModel.libraryPreferences.filterDownloaded.collectAsState()
    val downloadedOnly by viewModel.preferences.downloadedOnly.collectAsState()
    val autoUpdateMangaRestrictions by viewModel.libraryPreferences.autoUpdateMangaRestrictions.collectAsState()

    TriStateItem(
        label = stringResource(MR.strings.label_downloaded),
        state = if (downloadedOnly) {
            TriState.ENABLED_IS
        } else {
            filterDownloaded
        },
        enabled = !downloadedOnly,
        onClick = { viewModel.toggleFilter(LibraryPreferences::filterDownloaded) },
    )
    val filterUnread by viewModel.libraryPreferences.filterUnread.collectAsState()
    TriStateItem(
        label = stringResource(MR.strings.action_filter_unread),
        state = filterUnread,
        onClick = { viewModel.toggleFilter(LibraryPreferences::filterUnread) },
    )
    val filterStarted by viewModel.libraryPreferences.filterStarted.collectAsState()
    TriStateItem(
        label = stringResource(MR.strings.label_started),
        state = filterStarted,
        onClick = { viewModel.toggleFilter(LibraryPreferences::filterStarted) },
    )
    val filterBookmarked by viewModel.libraryPreferences.filterBookmarked.collectAsState()
    TriStateItem(
        label = stringResource(MR.strings.action_filter_bookmarked),
        state = filterBookmarked,
        onClick = { viewModel.toggleFilter(LibraryPreferences::filterBookmarked) },
    )
    val filterCompleted by viewModel.libraryPreferences.filterCompleted.collectAsState()
    TriStateItem(
        label = stringResource(MR.strings.completed),
        state = filterCompleted,
        onClick = { viewModel.toggleFilter(LibraryPreferences::filterCompleted) },
    )
    val filterChapterCount by viewModel.libraryPreferences.filterChapterCount().collectAsState()
    val chapterCountThreshold by viewModel.libraryPreferences.filterChapterCountThreshold.collectAsState()
    var thresholdText by remember { mutableStateOf(chapterCountThreshold.toString()) }
    TriStateItem(
        label = when (filterChapterCount) {
            TriState.ENABLED_IS -> stringResource(
                TDMR.strings.library_settings_filter_chapter_count_at_least,
                chapterCountThreshold,
            )
            TriState.ENABLED_NOT -> stringResource(
                TDMR.strings.library_settings_filter_chapter_count_less_than,
                chapterCountThreshold,
            )
            else -> stringResource(TDMR.strings.library_settings_filter_chapter_count_label)
        },
        state = filterChapterCount,
        onClick = { viewModel.toggleFilter(LibraryPreferences::filterChapterCount) },
    )
    if (filterChapterCount != TriState.DISABLED) {
        OutlinedTextField(
            value = thresholdText,
            onValueChange = { value ->
                thresholdText = value
                value.toIntOrNull()?.takeIf { it > 0 }?.let {
                    viewModel.libraryPreferences.filterChapterCountThreshold.set(it)
                }
            },
            label = { Text(stringResource(TDMR.strings.library_settings_chapter_count_threshold_label)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
        )
    }

    // TODO: re-enable when custom intervals are ready for stable
    if ((!isReleaseBuildType) && LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD in autoUpdateMangaRestrictions) {
        val filterIntervalCustom by viewModel.libraryPreferences.filterIntervalCustom().collectAsState()
        TriStateItem(
            label = stringResource(MR.strings.action_filter_interval_custom),
            state = filterIntervalCustom,
            onClick = { viewModel.toggleFilter(LibraryPreferences::filterIntervalCustom) },
        )
    }

    val trackers by viewModel.trackersFlow.collectAsState()
    when (trackers.size) {
        0 -> {
            // No trackers
        }
        1 -> {
            val service = trackers[0]
            val filterTracker by viewModel.libraryPreferences.filterTracking(service.id.toInt()).collectAsState()
            TriStateItem(
                label = stringResource(MR.strings.action_filter_tracked),
                state = filterTracker,
                onClick = { viewModel.toggleTracker(service.id.toInt()) },
            )
        }
        else -> {
            HeadingItem(MR.strings.action_filter_tracked)
            trackers.map { service ->
                val filterTracker by viewModel.libraryPreferences.filterTracking(service.id.toInt()).collectAsState()
                TriStateItem(
                    label = service.name,
                    state = filterTracker,
                    onClick = { viewModel.toggleTracker(service.id.toInt()) },
                )
            }
        }
    }

    // Search options section
    HeadingItem(stringResource(TDMR.strings.library_settings_search_options_header))
    CheckboxItem(
        label = stringResource(TDMR.strings.library_settings_search_chapter_names),
        pref = viewModel.libraryPreferences.searchChapterNames,
    )
    CheckboxItem(
        label = stringResource(TDMR.strings.library_settings_search_descriptions_tags),
        pref = viewModel.libraryPreferences.searchChapterContent,
    )
    CheckboxItem(
        label = stringResource(TDMR.strings.library_settings_search_alt_titles),
        pref = viewModel.libraryPreferences.searchAlternativeTitles,
    )
    CheckboxItem(
        label = stringResource(TDMR.strings.library_settings_search_by_url),
        pref = viewModel.libraryPreferences.searchByUrl,
    )
    CheckboxItem(
        label = stringResource(TDMR.strings.library_settings_use_regex_search),
        pref = viewModel.libraryPreferences.useRegexSearch,
    )
}

@Composable
private fun ColumnScope.SortPage(
    category: Category?,
    viewModel: LibrarySettingsViewModel,
) {
    val trackers by viewModel.trackersFlow.collectAsState()
    val sortingMode = category.sort.type
    val sortDescending = !category.sort.isAscending

    val options = remember(trackers.isEmpty()) {
        val trackerMeanPair = if (trackers.isNotEmpty()) {
            MR.strings.action_sort_tracker_score to LibrarySort.Type.TrackerMean
        } else {
            null
        }
        listOfNotNull(
            MR.strings.action_sort_alpha to LibrarySort.Type.Alphabetical,
            MR.strings.action_sort_total to LibrarySort.Type.TotalChapters,
            MR.strings.downloaded_chapters to LibrarySort.Type.DownloadedChapters,
            MR.strings.action_sort_last_read to LibrarySort.Type.LastRead,
            MR.strings.action_sort_last_manga_update to LibrarySort.Type.LastUpdate,
            MR.strings.action_sort_unread_count to LibrarySort.Type.UnreadCount,
            MR.strings.action_sort_latest_chapter to LibrarySort.Type.LatestChapter,
            MR.strings.action_sort_chapter_fetch_date to LibrarySort.Type.ChapterFetchDate,
            MR.strings.action_sort_date_added to LibrarySort.Type.DateAdded,
            TDMR.strings.action_sort_source_name to LibrarySort.Type.SourceName,
            trackerMeanPair,
            MR.strings.action_sort_random to LibrarySort.Type.Random,
        )
    }

    options.map { (titleRes, mode) ->
        if (mode == LibrarySort.Type.Random) {
            BaseSortItem(
                label = stringResource(titleRes),
                icon = Icons.Default.Refresh
                    .takeIf { sortingMode == LibrarySort.Type.Random },
                onClick = {
                    viewModel.setSort(category, mode, LibrarySort.Direction.Ascending)
                },
            )
            return@map
        }
        SortItem(
            label = stringResource(titleRes),
            sortDescending = sortDescending.takeIf { sortingMode == mode },
            onClick = {
                val isTogglingDirection = sortingMode == mode
                val direction = when {
                    isTogglingDirection -> if (sortDescending) {
                        LibrarySort.Direction.Ascending
                    } else {
                        LibrarySort.Direction.Descending
                    }
                    else -> if (sortDescending) {
                        LibrarySort.Direction.Descending
                    } else {
                        LibrarySort.Direction.Ascending
                    }
                }
                viewModel.setSort(category, mode, direction)
            },
        )
    }
}

private val displayModes = listOf(
    MR.strings.action_display_grid to LibraryDisplayMode.CompactGrid,
    MR.strings.action_display_comfortable_grid to LibraryDisplayMode.ComfortableGrid,
    MR.strings.action_display_cover_only_grid to LibraryDisplayMode.CoverOnlyGrid,
    MR.strings.action_display_list to LibraryDisplayMode.List,
)

@Composable
private fun ColumnScope.DisplayPage(
    viewModel: LibrarySettingsViewModel,
) {
    val displayMode by viewModel.libraryPreferences.displayMode.collectAsState()
    SettingsChipRow(MR.strings.action_display_mode) {
        displayModes.map { (titleRes, mode) ->
            FilterChip(
                selected = displayMode == mode,
                onClick = { viewModel.setDisplayMode(mode) },
                label = { Text(stringResource(titleRes)) },
            )
        }
    }

    if (displayMode != LibraryDisplayMode.List) {
        val configuration = LocalConfiguration.current
        val columnPreference = remember {
            if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                viewModel.libraryPreferences.landscapeColumns
            } else {
                viewModel.libraryPreferences.portraitColumns
            }
        }

        val columns by columnPreference.collectAsState()
        SliderItem(
            value = columns,
            valueRange = 0..15,
            label = stringResource(MR.strings.pref_library_columns),
            valueString = if (columns > 0) {
                columns.toString()
            } else {
                stringResource(MR.strings.label_auto)
            },
            onChange = columnPreference::set,
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    }

    HeadingItem(MR.strings.overlay_header)
    CheckboxItem(
        label = stringResource(MR.strings.action_display_download_badge),
        pref = viewModel.libraryPreferences.downloadBadge,
    )
    CheckboxItem(
        label = stringResource(MR.strings.action_display_unread_badge),
        pref = viewModel.libraryPreferences.unreadBadge,
    )
    CheckboxItem(
        label = stringResource(MR.strings.action_display_local_badge),
        pref = viewModel.libraryPreferences.localBadge,
    )
    CheckboxItem(
        label = stringResource(MR.strings.action_display_language_badge),
        pref = viewModel.libraryPreferences.languageBadge,
    )
    CheckboxItem(
        label = stringResource(MR.strings.action_display_show_continue_reading_button),
        pref = viewModel.libraryPreferences.showContinueReadingButton,
    )
    CheckboxItem(
        label = stringResource(TDMR.strings.library_settings_show_url_in_list),
        pref = viewModel.libraryPreferences.showUrlInList,
    )

    val titleMaxLines by viewModel.libraryPreferences.titleMaxLines.collectAsState()
    SliderItem(
        value = titleMaxLines,
        valueRange = 1..15,
        label = stringResource(TDMR.strings.library_settings_title_max_lines),
        valueString = titleMaxLines.toString(),
        onChange = viewModel.libraryPreferences.titleMaxLines::set,
        pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    )

    HeadingItem(MR.strings.tabs_header)
    CheckboxItem(
        label = stringResource(MR.strings.action_display_show_tabs),
        pref = viewModel.libraryPreferences.categoryTabs,
    )
    CheckboxItem(
        label = stringResource(MR.strings.action_display_show_number_of_items),
        pref = viewModel.libraryPreferences.categoryNumberOfItems,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.TagsPage(
    viewModel: LibrarySettingsViewModel,
) {
    val tags by viewModel.tagsFlow.collectAsState()
    val includedTags by viewModel.libraryPreferences.includedTags.collectAsState()
    val excludedTags by viewModel.libraryPreferences.excludedTags.collectAsState()
    val filterNoTags by viewModel.libraryPreferences.filterNoTags().collectAsState()
    val noTagsCount by viewModel.noTagsCountFlow.collectAsState()
    val isLoading by viewModel.tagsLoading.collectAsState()

    // Tag options
    val tagIncludeModeAnd by viewModel.libraryPreferences.tagIncludeMode.collectAsState()
    val tagExcludeModeAnd by viewModel.libraryPreferences.tagExcludeMode.collectAsState()
    val tagSortByName by viewModel.libraryPreferences.tagSortByName.collectAsState()
    val tagSortAscending by viewModel.libraryPreferences.tagSortAscending.collectAsState()
    val tagCaseSensitive by viewModel.libraryPreferences.tagCaseSensitive.collectAsState()

    // Tag search state
    val tagSearchQuery by viewModel.tagSearchQuery.collectAsState()
    val committedTagQuery by viewModel.committedTagQuery.collectAsState()

    // Options expanded state
    val optionsExpanded by viewModel.tagOptionsExpanded.collectAsState()
    var showRefreshCompleted by remember { mutableStateOf(false) }
    var wasLoading by remember { mutableStateOf(false) }

    // Load data when first entering this page (only if empty)
    LaunchedEffect(Unit) {
        if (tags.isEmpty()) {
            viewModel.refreshTags()
        }
    }

    LaunchedEffect(isLoading) {
        if (isLoading) {
            wasLoading = true
            showRefreshCompleted = false
        } else if (wasLoading) {
            wasLoading = false
            showRefreshCompleted = true
            delay(900)
            showRefreshCompleted = false
        }
    }

    // Header row with refresh and options toggle
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { viewModel.toggleTagOptions() }) {
            Icon(
                imageVector = if (optionsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (optionsExpanded) {
                    stringResource(TDMR.strings.library_settings_tags_collapse_options_cd)
                } else {
                    stringResource(TDMR.strings.library_settings_tags_expand_options_cd)
                },
            )
            Spacer(Modifier.width(4.dp))
            Text(stringResource(TDMR.strings.label_options))
        }
        TextButton(
            onClick = { viewModel.refreshTags(forceRefresh = true) },
            enabled = !isLoading,
        ) {
            Crossfade(
                targetState = when {
                    isLoading -> "loading"
                    showRefreshCompleted -> "done"
                    else -> "idle"
                },
            ) { state ->
                when (state) {
                    "loading" -> CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    "done" -> Icon(
                        Icons.Default.Done,
                        contentDescription = stringResource(TDMR.strings.library_settings_refreshed_cd),
                    )
                    else -> Icon(
                        Icons.Default.Refresh,
                        contentDescription = stringResource(MR.strings.action_webview_refresh),
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            Text(
                if (showRefreshCompleted) {
                    stringResource(TDMR.strings.library_settings_refreshed_cd)
                } else {
                    stringResource(MR.strings.action_webview_refresh)
                },
            )
        }
    }

    // Collapsible options section
    if (optionsExpanded) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = TabbedDialogPaddings.Horizontal, vertical = 4.dp),
        ) {
            // Include mode toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(TDMR.strings.library_settings_tags_include_mode_label),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row {
                    FilterChip(
                        selected = !tagIncludeModeAnd,
                        onClick = { viewModel.libraryPreferences.tagIncludeMode.set(false) },
                        label = { Text(stringResource(TDMR.strings.library_settings_tag_mode_or)) },
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = tagIncludeModeAnd,
                        onClick = { viewModel.libraryPreferences.tagIncludeMode.set(true) },
                        label = { Text(stringResource(TDMR.strings.library_settings_tag_mode_and)) },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Exclude mode toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(TDMR.strings.library_settings_tags_exclude_mode_label),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row {
                    FilterChip(
                        selected = !tagExcludeModeAnd,
                        onClick = { viewModel.libraryPreferences.tagExcludeMode.set(false) },
                        label = { Text(stringResource(TDMR.strings.library_settings_tag_mode_or)) },
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = tagExcludeModeAnd,
                        onClick = { viewModel.libraryPreferences.tagExcludeMode.set(true) },
                        label = { Text(stringResource(TDMR.strings.library_settings_tag_mode_and)) },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Sort options
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(TDMR.strings.library_settings_tags_sort_by_label),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row {
                    FilterChip(
                        selected = !tagSortByName,
                        onClick = { viewModel.libraryPreferences.tagSortByName.set(false) },
                        label = { Text(stringResource(TDMR.strings.library_settings_tags_sort_count)) },
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = tagSortByName,
                        onClick = { viewModel.libraryPreferences.tagSortByName.set(true) },
                        label = { Text(stringResource(MR.strings.name)) },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Sort direction
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(TDMR.strings.library_settings_tags_sort_order_label),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row {
                    FilterChip(
                        selected = !tagSortAscending,
                        onClick = { viewModel.libraryPreferences.tagSortAscending.set(false) },
                        label = { Text(stringResource(TDMR.strings.library_settings_tags_sort_desc)) },
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = tagSortAscending,
                        onClick = { viewModel.libraryPreferences.tagSortAscending.set(true) },
                        label = { Text(stringResource(TDMR.strings.library_settings_tags_sort_asc)) },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Case sensitivity toggle
            CheckboxItem(
                label = stringResource(TDMR.strings.label_case_sensitive_matching),
                pref = viewModel.libraryPreferences.tagCaseSensitive,
            )
        }
    }

    // Clear All button
    if (includedTags.isNotEmpty() || excludedTags.isNotEmpty() || filterNoTags != TriState.DISABLED) {
        TextButton(
            onClick = { viewModel.clearAllTagFilters() },
            modifier = Modifier.padding(horizontal = TabbedDialogPaddings.Horizontal),
        ) {
            Icon(
                Icons.Default.Clear,
                contentDescription = stringResource(TDMR.strings.library_settings_tags_clear_all_cd),
            )
            Spacer(Modifier.width(4.dp))
            Text(stringResource(TDMR.strings.library_settings_tags_clear_all_filters))
        }
    }

    // No tags filter
    TriStateItem(
        label = stringResource(TDMR.strings.library_settings_tags_no_tags_count, noTagsCount),
        state = filterNoTags,
        onClick = { viewModel.toggleNoTagsFilter() },
    )

    // Tag search input
    val keyboardController = LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value = tagSearchQuery,
        onValueChange = { viewModel.setTagSearchQuery(it) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = TabbedDialogPaddings.Horizontal, vertical = 8.dp),
        placeholder = { Text(stringResource(TDMR.strings.library_settings_tags_search_placeholder)) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = stringResource(MR.strings.action_search)) },
        trailingIcon = if (tagSearchQuery.isNotEmpty()) {
            {
                IconButton(onClick = { viewModel.clearTagSearch() }) {
                    Icon(
                        Icons.Default.Clear,
                        contentDescription = stringResource(TDMR.strings.library_settings_tags_clear_search_cd),
                    )
                }
            }
        } else {
            null
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(
            onSearch = {
                viewModel.commitTagSearch()
                keyboardController?.hide()
            },
        ),
    )

    // Sort and filter tags. Active (included/excluded) tags missing from the library's loaded tags
    // are surfaced as count-0 entries so cross-type or stale filters stay visible and clearable;
    // these prefs are shared across the manga/novel/all libraries, so a tag included on one type
    // would otherwise silently filter another to empty while nothing appears selected. They land in
    // the active partition below, pinned to the top.
    val sortedTags =
        remember(
            tags,
            tagSortByName,
            tagSortAscending,
            committedTagQuery,
            includedTags,
            excludedTags,
            tagCaseSensitive,
        ) {
            val loadedKeys = if (tagCaseSensitive) {
                tags.mapTo(HashSet()) { it.first }
            } else {
                tags.mapTo(HashSet()) { it.first.lowercase() }
            }
            fun isLoaded(tag: String) =
                if (tagCaseSensitive) tag in loadedKeys else tag.lowercase() in loadedKeys
            val phantomActive = (includedTags + excludedTags)
                .filterNot { isLoaded(it) }
                .map { it to 0 }
            val allTags = phantomActive + tags

            val filtered = if (committedTagQuery.isBlank()) {
                allTags
            } else {
                val query = if (tagCaseSensitive) committedTagQuery else committedTagQuery.lowercase()
                allTags.filter { (tag, _) ->
                    val tagToMatch = if (tagCaseSensitive) tag else tag.lowercase()
                    tagToMatch.contains(query)
                }
            }

            val (activeTags, inactiveTags) = filtered.partition { (tag, _) ->
                tag in includedTags || tag in excludedTags
            }

            val sortComparator: Comparator<Pair<String, Int>> = if (tagSortByName) {
                if (tagSortAscending) {
                    compareBy { it.first.lowercase() }
                } else {
                    compareByDescending { it.first.lowercase() }
                }
            } else {
                if (tagSortAscending) {
                    compareBy { it.second }
                } else {
                    compareByDescending { it.second }
                }
            }

            activeTags.sortedWith(sortComparator) + inactiveTags.sortedWith(sortComparator)
        }

    if (sortedTags.isEmpty() && !isLoading) {
        Text(
            text = stringResource(TDMR.strings.library_settings_tags_none_found),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = TabbedDialogPaddings.Horizontal, vertical = 8.dp),
        )
    } else if (isLoading && sortedTags.isEmpty()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(TDMR.strings.library_settings_tags_loading))
        }
    } else {
        Text(
            text = stringResource(TDMR.strings.library_settings_tags_tap_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = TabbedDialogPaddings.Horizontal, vertical = 4.dp),
        )

        Text(
            text = stringResource(TDMR.strings.library_settings_tags_count_label, sortedTags.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = TabbedDialogPaddings.Horizontal, vertical = 2.dp),
        )

        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Adaptive(minSize = 100.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = TabbedDialogPaddings.Horizontal, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalItemSpacing = 8.dp,
        ) {
            items(sortedTags.size, key = { sortedTags[it].first }) { index ->
                val (tag, count) = sortedTags[index]
                val isIncluded = tag in includedTags
                val isExcluded = tag in excludedTags

                FilterChip(
                    selected = isIncluded || isExcluded,
                    onClick = { viewModel.toggleTagIncluded(tag) },
                    label = { Text("$tag ($count)") },
                    leadingIcon = {
                        when {
                            isIncluded -> Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize),
                            )

                            isExcluded -> Icon(
                                imageVector = Icons.Filled.Clear,
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize),
                            )
                        }
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = if (isExcluded) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        },
                        selectedLabelColor = if (isExcluded) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        },
                    ),
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.ExtensionsPage(
    viewModel: LibrarySettingsViewModel,
) {
    val excludedExtensions by viewModel.libraryPreferences.excludedExtensions.collectAsState()
    val availableExtensions by viewModel.extensionsFlow.collectAsState()
    val isLoading by viewModel.extensionsLoading.collectAsState()
    var showRefreshCompleted by remember { mutableStateOf(false) }
    var wasLoading by remember { mutableStateOf(false) }

    LaunchedEffect(isLoading) {
        if (isLoading) {
            wasLoading = true
            showRefreshCompleted = false
        } else if (wasLoading) {
            wasLoading = false
            showRefreshCompleted = true
            delay(900)
            showRefreshCompleted = false
        }
    }

    // Extensions are now auto-loaded in the ViewModel's init block
    // No need for LaunchedEffect here

    HeadingItem(MR.strings.label_extensions)

    // Compact action row: refresh/check/uncheck
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = TabbedDialogPaddings.Horizontal, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(
            onClick = {
                viewModel.refreshExtensions(forceRefresh = true)
            },
            enabled = !isLoading,
            modifier = Modifier.weight(1f),
        ) {
            Crossfade(
                targetState = when {
                    isLoading -> "loading"
                    showRefreshCompleted -> "done"
                    else -> "idle"
                },
            ) { state ->
                when (state) {
                    "loading" -> CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    "done" -> Icon(
                        Icons.Default.Done,
                        contentDescription = stringResource(TDMR.strings.library_settings_refreshed_cd),
                    )
                    else -> Icon(
                        Icons.Default.Refresh,
                        contentDescription = stringResource(MR.strings.action_webview_refresh),
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = if (showRefreshCompleted) {
                    stringResource(TDMR.strings.library_settings_refreshed_cd)
                } else {
                    stringResource(MR.strings.action_webview_refresh)
                },
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }

        TextButton(
            onClick = { viewModel.checkAllExtensions() },
            modifier = Modifier.weight(1f),
        ) {
            Icon(
                Icons.Default.Check,
                contentDescription = stringResource(TDMR.strings.library_settings_extensions_check_all_cd),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = stringResource(MR.strings.all),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }

        TextButton(
            onClick = { viewModel.uncheckAllExtensions() },
            modifier = Modifier.weight(1f),
        ) {
            Icon(
                Icons.Default.Clear,
                contentDescription = stringResource(TDMR.strings.library_settings_extensions_uncheck_all_cd),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = stringResource(MR.strings.none),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    if (availableExtensions.isEmpty() && !isLoading) {
        Text(
            text = stringResource(TDMR.strings.library_settings_extensions_none_found),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = TabbedDialogPaddings.Horizontal),
        )
    } else if (isLoading && availableExtensions.isEmpty()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(TDMR.strings.library_settings_extensions_loading))
        }
    } else {
        // Show count of missing sources
        val stubCount = availableExtensions.count { it.isStub }
        if (stubCount > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = TabbedDialogPaddings.Horizontal, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(TDMR.strings.library_settings_extensions_missing_count, stubCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        // Separate manga and novel sources when showing "All" type
        val mangaSources = if (viewModel.type == eu.kanade.tachiyomi.ui.library.LibraryViewModel.LibraryType.All) {
            availableExtensions.filter { !it.isNovel }
        } else {
            emptyList()
        }
        val novelSources = if (viewModel.type == eu.kanade.tachiyomi.ui.library.LibraryViewModel.LibraryType.All) {
            availableExtensions.filter { it.isNovel }
        } else {
            emptyList()
        }
        val showSeparator = viewModel.type == eu.kanade.tachiyomi.ui.library.LibraryViewModel.LibraryType.All &&
            mangaSources.isNotEmpty() && novelSources.isNotEmpty()

        val sourcesToShow = if (viewModel.type == eu.kanade.tachiyomi.ui.library.LibraryViewModel.LibraryType.All) {
            mangaSources + novelSources
        } else {
            availableExtensions
        }

        sourcesToShow.forEachIndexed { index, extensionInfo ->
            // Add divider between manga and novel sections
            if (showSeparator && index == mangaSources.size) {
                Spacer(modifier = Modifier.height(8.dp))
                androidx.compose.material3.HorizontalDivider(
                    modifier = Modifier.padding(horizontal = TabbedDialogPaddings.Horizontal),
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                Text(
                    text = stringResource(TDMR.strings.label_novel_sources),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = TabbedDialogPaddings.Horizontal, vertical = 4.dp),
                )
            }

            // Extension is checked if it's NOT in the excluded set
            val isChecked = extensionInfo.sourceId.toString() !in excludedExtensions
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (extensionInfo.isStub) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = stringResource(TDMR.strings.library_settings_extensions_missing_source_cd),
                        modifier = Modifier
                            .padding(start = TabbedDialogPaddings.Horizontal)
                            .size(16.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
                CheckboxItem(
                    label = if (extensionInfo.isStub) {
                        stringResource(
                            TDMR.strings.library_settings_extensions_missing_suffix,
                            extensionInfo.sourceName,
                        )
                    } else {
                        extensionInfo.sourceName
                    },
                    checked = isChecked,
                    onClick = {
                        viewModel.toggleExtensionFilter(extensionInfo.sourceId.toString(), !isChecked)
                    },
                )
            }
        }
    }
}
