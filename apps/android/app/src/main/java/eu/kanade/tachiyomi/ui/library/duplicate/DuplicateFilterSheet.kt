package eu.kanade.tachiyomi.ui.library.duplicate

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.library.duplicate.DuplicateDetectionViewModel.CategoryIncludeMode
import eu.kanade.tachiyomi.ui.library.duplicate.DuplicateDetectionViewModel.SortMode
import tachiyomi.domain.manga.interactor.BlankTitleFilter
import tachiyomi.domain.manga.interactor.DuplicateMatchMode
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.i18n.stringResource

/** Sort options offered by the UI, in display order. The enum also carries ascending variants that
 *  no chip exposes; listing them here keeps the sheet's contents explicit rather than incidental. */
private val SORT_CHIPS = listOf(
    SortMode.NAME to MR.strings.name,
    SortMode.LATEST_ADDED to MR.strings.latest,
    SortMode.CHAPTER_COUNT_DESC to TDMR.strings.duplicate_sort_ch_desc,
    SortMode.DOWNLOAD_COUNT_DESC to TDMR.strings.duplicate_sort_dl_desc,
    SortMode.READ_COUNT_DESC to TDMR.strings.duplicate_sort_read_desc,
    SortMode.PINNED_SOURCE to TDMR.strings.duplicate_select_pinned,
    SortMode.SOURCE_PRIORITY to TDMR.strings.duplicate_sort_priority,
)

private val SheetPadding = 16.dp

/**
 * Every filter, match mode and sort option for the duplicate screen. These are a settings surface,
 * not content, so they live in a sheet instead of an inline panel that pushes the results off-screen
 * and scrolls away with them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicateFilterSheet(
    state: DuplicateDetectionViewModel.State,
    screenModel: DuplicateDetectionViewModel,
    onDismissRequest: () -> Unit,
    onOpenSourcePriority: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    ModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
        ) {
            Text(
                text = stringResource(TDMR.strings.duplicate_filters_sort),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = SheetPadding),
            )

            FilterSection(stringResource(TDMR.strings.duplicate_match_label)) {
                SelectableChip(
                    label = stringResource(TDMR.strings.duplicate_match_exact),
                    selected = state.matchMode == DuplicateMatchMode.EXACT,
                    enabled = !state.listingMode,
                    onClick = { screenModel.setMatchMode(DuplicateMatchMode.EXACT) },
                )
                SelectableChip(
                    label = stringResource(TDMR.strings.duplicate_match_contains),
                    selected = state.matchMode == DuplicateMatchMode.CONTAINS,
                    enabled = !state.listingMode,
                    onClick = { screenModel.setMatchMode(DuplicateMatchMode.CONTAINS) },
                )
                SelectableChip(
                    label = stringResource(TDMR.strings.duplicate_listing_mode),
                    selected = state.listingMode,
                    onClick = { screenModel.setListingMode(!state.listingMode) },
                )
            }

            FilterSection(stringResource(TDMR.strings.label_options)) {
                SelectableChip(
                    label = stringResource(TDMR.strings.duplicate_full_urls_short),
                    selected = state.showFullUrls,
                    onClick = screenModel::toggleShowFullUrls,
                )
                SelectableChip(
                    label = stringResource(TDMR.strings.duplicate_flexible_group_matching),
                    selected = state.filterByGroupCategory,
                    onClick = { screenModel.setFilterByGroupCategory(!state.filterByGroupCategory) },
                )
                SelectableChip(
                    label = stringResource(TDMR.strings.duplicate_apply_library_filters),
                    selected = state.applyLibraryFilters,
                    onClick = { screenModel.setApplyLibraryFilters(!state.applyLibraryFilters) },
                )
            }

            // CONTAINS matches substrings, so a blank key can never form a group there, and listing
            // mode does not group by key at all.
            val blankFiltersEnabled = !state.listingMode && state.matchMode != DuplicateMatchMode.CONTAINS
            FilterSection(stringResource(TDMR.strings.duplicate_blank_label)) {
                SelectableChip(
                    label = stringResource(TDMR.strings.duplicate_blank_exclude),
                    selected = state.blankTitleFilter == BlankTitleFilter.EXCLUDE,
                    enabled = blankFiltersEnabled,
                    onClick = { screenModel.setBlankTitleFilter(BlankTitleFilter.EXCLUDE) },
                )
                SelectableChip(
                    label = stringResource(TDMR.strings.duplicate_blank_include),
                    selected = state.blankTitleFilter == BlankTitleFilter.INCLUDE,
                    enabled = blankFiltersEnabled,
                    onClick = { screenModel.setBlankTitleFilter(BlankTitleFilter.INCLUDE) },
                )
            }

            FilterSection(stringResource(TDMR.strings.duplicate_sort_label)) {
                SORT_CHIPS.forEach { (mode, labelRes) ->
                    SelectableChip(
                        label = stringResource(labelRes),
                        selected = state.sortMode == mode,
                        onClick = { screenModel.setSortMode(mode) },
                    )
                }
            }

            if (state.categories.isNotEmpty()) {
                CategoryFilterSection(state, screenModel)
            }

            HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
            ListItem(
                supportingContent = { Text(stringResource(TDMR.strings.duplicate_source_priority_desc)) },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null)
                },
                modifier = Modifier.clickable(onClick = onOpenSourcePriority),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            ) {
                Text(stringResource(TDMR.strings.duplicate_source_priority))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryFilterSection(
    state: DuplicateDetectionViewModel.State,
    screenModel: DuplicateDetectionViewModel,
) {
    FilterSection(stringResource(TDMR.strings.duplicate_category_label)) {
        SelectableChip(
            label = stringResource(TDMR.strings.duplicate_category_include_or),
            selected = state.categoryIncludeMode == CategoryIncludeMode.ANY,
            onClick = { screenModel.setCategoryIncludeMode(CategoryIncludeMode.ANY) },
        )
        SelectableChip(
            label = stringResource(TDMR.strings.duplicate_category_include_and),
            selected = state.categoryIncludeMode == CategoryIncludeMode.ALL,
            onClick = { screenModel.setCategoryIncludeMode(CategoryIncludeMode.ALL) },
        )
        if (state.selectedCategoryFilters.isNotEmpty() || state.excludedCategoryFilters.isNotEmpty()) {
            SelectableChip(
                label = stringResource(TDMR.strings.duplicate_category_clear),
                selected = false,
                onClick = screenModel::clearCategoryFilters,
            )
        }
    }

    val defaultLabel = stringResource(MR.strings.label_default)
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SheetPadding, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.categories.forEach { category ->
            val isIncluded = category.id in state.selectedCategoryFilters
            val isExcluded = category.id in state.excludedCategoryFilters
            FilterChip(
                selected = isIncluded || isExcluded,
                onClick = { screenModel.toggleCategoryFilter(category.id) },
                label = { Text(category.name.ifBlank { defaultLabel }) },
                leadingIcon = when {
                    isIncluded -> chipIcon(Icons.Filled.Check)
                    isExcluded -> chipIcon(Icons.Filled.Close)
                    else -> null
                },
                colors = if (isExcluded) {
                    FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                } else {
                    FilterChipDefaults.filterChipColors()
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = SheetPadding, end = SheetPadding, top = 16.dp, bottom = 8.dp),
    )
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SheetPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}

/**
 * The one chip shape this screen uses, replacing fifteen hand-written copies that each repeated the
 * same `leadingIcon = if (selected) Check else null` block.
 */
@Composable
private fun SelectableChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    FilterChip(
        selected = selected,
        enabled = enabled,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) chipIcon(Icons.Filled.Check) else null,
    )
}

private fun chipIcon(icon: ImageVector): @Composable () -> Unit = {
    Icon(icon, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize))
}
