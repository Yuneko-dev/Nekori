package eu.kanade.tachiyomi.ui.browse.source.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import eu.kanade.domain.source.model.FilterPreset
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.FilterSuggestion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import tachiyomi.core.common.preference.TriState
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.CollapsibleBox
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.SelectItem
import tachiyomi.presentation.core.components.SortItem
import tachiyomi.presentation.core.components.TextItem
import tachiyomi.presentation.core.components.TriStateItem
import tachiyomi.presentation.core.components.material.Button
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.roundToInt

private const val SUGGESTION_DEBOUNCE_MS = 250L
private const val SUGGESTION_MIN_QUERY_LENGTH = 2
private const val SUGGESTION_TIMEOUT_MS = 5_000L
private const val SUGGESTION_LIMIT = 6
private const val SUGGESTION_MAX_BACKOFF_MS = 8_000L
private const val SUGGESTION_MAX_CACHED_QUERIES = 200

/** Session cache so retyping a query does not ask the plugin again. */
private val filterSuggestionCache = object :
    LinkedHashMap<String, List<FilterSuggestion>>(16, 0.75f, true) {
    override fun removeEldestEntry(
        eldest: MutableMap.MutableEntry<String, List<FilterSuggestion>>?,
    ): Boolean = size > SUGGESTION_MAX_CACHED_QUERIES
}

typealias FilterSuggestionsFetcher = suspend (filterKey: String, query: String) -> List<FilterSuggestion>

@Composable
fun SourceFilterDialog(
    onDismissRequest: () -> Unit,
    filters: FilterList,
    onReset: () -> Unit,
    onFilter: () -> Unit,
    onUpdate: (FilterList) -> Unit,
    onOpenPresets: () -> Unit,
    @Suppress("UNUSED_PARAMETER") presets: List<FilterPreset>,
    onSavePreset: (name: String, setAsDefault: Boolean) -> Unit,
    @Suppress("UNUSED_PARAMETER") onLoadPreset: (Long) -> Unit,
    @Suppress("UNUSED_PARAMETER") onDeletePreset: (Long) -> Unit,
    onFetchFilterSuggestions: FilterSuggestionsFetcher? = null,
) {
    val updateFilters = { onUpdate(filters) }
    var showSaveDialog by remember { mutableStateOf(false) }
    // Reset remounts the list so fields with local drafts clear with the filter state.
    var resetVersion by remember { mutableIntStateOf(0) }

    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        LazyColumn {
            stickyHeader {
                Row(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.background)
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = {
                        onReset()
                        resetVersion++
                    }) {
                        Text(
                            text = stringResource(MR.strings.action_reset),
                            style = LocalTextStyle.current.copy(
                                color = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    }

                    // Save icon
                    IconButton(onClick = { showSaveDialog = true }) {
                        Icon(
                            imageVector = Icons.Filled.Save,
                            contentDescription = stringResource(TDMR.strings.browse_filter_save_preset_cd),
                        )
                    }

                    // Open presets modal instead of dropdown
                    IconButton(onClick = onOpenPresets) {
                        Icon(
                            imageVector = Icons.Outlined.BookmarkBorder,
                            contentDescription = stringResource(TDMR.strings.browse_filter_presets_cd),
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    Button(onClick = {
                        onFilter()
                        onDismissRequest()
                    }) {
                        Text(stringResource(MR.strings.action_filter))
                    }
                }
                HorizontalDivider()
            }

            itemsIndexed(
                items = filters,
                key = { index, filter -> "$resetVersion:${filter.name}:$index" },
            ) { _, filter ->
                FilterItem(
                    filter = filter,
                    resetVersion = resetVersion,
                    onFetchFilterSuggestions = onFetchFilterSuggestions,
                    onUpdate = updateFilters,
                )
            }
        }
    }

    if (showSaveDialog) {
        SavePresetDialog(
            onDismiss = { showSaveDialog = false },
            onSave = { name, setAsDefault ->
                onSavePreset(name, setAsDefault)
                showSaveDialog = false
            },
        )
    }
}

@Composable
private fun FilterItem(
    filter: Filter<*>,
    resetVersion: Int,
    onFetchFilterSuggestions: FilterSuggestionsFetcher?,
    onUpdate: () -> Unit,
) {
    when (filter) {
        is Filter.Header -> {
            HeadingItem(filter.name)
        }
        is Filter.Separator -> {
            HorizontalDivider()
        }
        is Filter.CheckBox -> {
            CheckboxItem(
                label = filter.name,
                checked = filter.state,
            ) {
                filter.state = !filter.state
                onUpdate()
            }
        }
        is Filter.TriState -> {
            TriStateItem(
                label = filter.name,
                state = filter.state.toTriStateFilter(),
            ) {
                filter.state = filter.state.toTriStateFilter().next().toTriStateInt()
                onUpdate()
            }
        }
        is Filter.Text -> {
            val filterKey = filter.key
            if (filter.autocomplete && filterKey != null && onFetchFilterSuggestions != null) {
                AutocompleteTextItem(
                    filter = filter,
                    filterKey = filterKey,
                    fetch = onFetchFilterSuggestions,
                    resetVersion = resetVersion,
                    onUpdate = onUpdate,
                )
            } else {
                TextItem(
                    label = filter.name,
                    value = filter.state,
                ) {
                    filter.state = it
                    onUpdate()
                }
            }
        }
        is Filter.Select<*> -> {
            SelectItem(
                label = filter.name,
                options = filter.values,
                selectedIndex = filter.state,
            ) {
                filter.state = it
                onUpdate()
            }
        }
        is Filter.Sort -> {
            CollapsibleBox(
                heading = filter.name,
            ) {
                Column {
                    filter.values.mapIndexed { index, item ->
                        val sortAscending = filter.state?.ascending
                            ?.takeIf { index == filter.state?.index }
                        SortItem(
                            label = item,
                            sortDescending = if (sortAscending != null) !sortAscending else null,
                            onClick = {
                                val ascending = if (index == filter.state?.index) {
                                    !filter.state!!.ascending
                                } else {
                                    filter.state?.ascending ?: true
                                }
                                filter.state = Filter.Sort.Selection(
                                    index = index,
                                    ascending = ascending,
                                )
                                onUpdate()
                            },
                        )
                    }
                }
            }
        }
        is Filter.Group<*> -> {
            CollapsibleBox(
                heading = filter.name,
            ) {
                Column {
                    filter.state
                        .filterIsInstance<Filter<*>>()
                        .forEach { child ->
                            FilterItem(
                                filter = child,
                                resetVersion = resetVersion,
                                onFetchFilterSuggestions = onFetchFilterSuggestions,
                                onUpdate = onUpdate,
                            )
                        }
                }
            }
        }
    }
}

@Composable
private fun AutocompleteTextItem(
    filter: Filter.Text,
    filterKey: String,
    fetch: FilterSuggestionsFetcher,
    resetVersion: Int,
    onUpdate: () -> Unit,
) {
    // Keyed on resetVersion too, so Reset clears a draft that never reached filter.state.
    var text by remember(resetVersion, filter.key) { mutableStateOf(filter.state) }
    var fieldBounds by remember { mutableStateOf<Rect?>(null) }
    val autocomplete = rememberAutocompleteField(
        filterKey = filterKey,
        query = text,
        enabled = true,
        fetch = fetch,
    ) { suggestion ->
        text = suggestion.value
        filter.state = suggestion.value
        onUpdate()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { fieldBounds = it.boundsInWindow() },
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { value ->
                    autocomplete.onEdit()
                    text = value
                    filter.state = value
                    onUpdate()
                },
                label = { Text(text = filter.name) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused) {
                            autocomplete.onFocus()
                        } else {
                            autocomplete.onBlur()
                        }
                    },
            )
        }
        FilterSuggestionPopup(
            visible = autocomplete.suggestions.isNotEmpty(),
            suggestions = autocomplete.suggestions,
            onSelect = autocomplete.select,
            onDismiss = autocomplete.dismiss,
            anchorBounds = fieldBounds,
        )
    }
}

@Composable
private fun FilterSuggestionPopup(
    visible: Boolean,
    suggestions: List<FilterSuggestion>,
    onSelect: (FilterSuggestion) -> Unit,
    onDismiss: () -> Unit,
    anchorBounds: Rect?,
) {
    if (!visible || anchorBounds == null || suggestions.isEmpty()) return
    val anchor = anchorBounds
    val density = LocalDensity.current
    val gapPx = with(density) { 4.dp.roundToPx() }
    val popupWidth = with(density) { anchor.width.toDp() }
    val positionProvider = remember(anchor, gapPx) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val left = anchor.left.roundToInt()
                    .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                val belowTop = anchor.bottom.roundToInt() + gapPx
                val spaceBelow = windowSize.height - belowTop
                val spaceAbove = anchor.top.roundToInt() - gapPx
                val top = if (spaceBelow >= popupContentSize.height || spaceBelow >= spaceAbove) {
                    belowTop
                } else {
                    (anchor.top.roundToInt() - gapPx - popupContentSize.height).coerceAtLeast(0)
                }
                return IntOffset(left, top)
            }
        }
    }

    Popup(
        popupPositionProvider = positionProvider,
        properties = PopupProperties(focusable = false),
        onDismissRequest = onDismiss,
    ) {
        FilterSuggestionList(
            suggestions = suggestions,
            onSelect = onSelect,
            modifier = Modifier.width(popupWidth),
        )
    }
}

@Composable
private fun FilterSuggestionList(
    suggestions: List<FilterSuggestion>,
    onSelect: (FilterSuggestion) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .shadow(4.dp, MaterialTheme.shapes.small)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = MaterialTheme.shapes.small,
            ),
    ) {
        suggestions.forEach { suggestion ->
            Text(
                text = suggestion.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(suggestion) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

private class AutocompleteFieldState(
    val suggestions: List<FilterSuggestion>,
    val onFocus: () -> Unit,
    val onBlur: () -> Unit,
    val onEdit: () -> Unit,
    val dismiss: () -> Unit,
    val select: (FilterSuggestion) -> Unit,
)

/**
 * Debounced, stale-safe autocomplete for one filter field.
 *
 * Mirrors the LNReader sheet: suggestions only show while the input is focused, a pick hides them
 * until the next edit or focus, failures back off progressively, and a request that hangs times out
 * into plain free text.
 */
@Composable
private fun rememberAutocompleteField(
    filterKey: String?,
    query: String,
    enabled: Boolean,
    fetch: FilterSuggestionsFetcher?,
    onSelect: (FilterSuggestion) -> Unit,
): AutocompleteFieldState {
    var suggestions by remember(filterKey) { mutableStateOf(emptyList<FilterSuggestion>()) }
    var isFocused by remember(filterKey) { mutableStateOf(false) }
    // A pick closes the list without blurring the input, so only an edit reopens it.
    var isSuppressed by remember(filterKey) { mutableStateOf(false) }
    var consecutiveFailures by remember(filterKey) { mutableIntStateOf(0) }

    val trimmedQuery = query.trim()
    val isActive = enabled && filterKey != null && fetch != null &&
        trimmedQuery.length >= SUGGESTION_MIN_QUERY_LENGTH
    val cacheKey = "$filterKey\u0000$trimmedQuery"

    LaunchedEffect(filterKey, trimmedQuery, enabled) {
        if (!isActive || filterKey == null || fetch == null) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        filterSuggestionCache[cacheKey]?.let {
            suggestions = it
            consecutiveFailures = 0
            return@LaunchedEffect
        }

        val backoffMultiplier = 1L shl consecutiveFailures.coerceAtMost(5)
        delay((SUGGESTION_DEBOUNCE_MS * backoffMultiplier).coerceAtMost(SUGGESTION_MAX_BACKOFF_MS))

        val result = withTimeoutOrNull(SUGGESTION_TIMEOUT_MS) {
            try {
                fetch(filterKey, trimmedQuery)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
        if (result == null) {
            consecutiveFailures++
            suggestions = emptyList()
        } else {
            consecutiveFailures = 0
            val limited = result.take(SUGGESTION_LIMIT)
            filterSuggestionCache[cacheKey] = limited
            suggestions = limited
        }
    }

    return AutocompleteFieldState(
        suggestions = if (isFocused && !isSuppressed) suggestions else emptyList(),
        onFocus = {
            isFocused = true
            isSuppressed = false
        },
        onBlur = { isFocused = false },
        onEdit = { isSuppressed = false },
        dismiss = { isSuppressed = true },
        select = { suggestion ->
            onSelect(suggestion)
            isSuppressed = true
        },
    )
}

private fun Int.toTriStateFilter(): TriState {
    return when (this) {
        Filter.TriState.STATE_IGNORE -> TriState.DISABLED
        Filter.TriState.STATE_INCLUDE -> TriState.ENABLED_IS
        Filter.TriState.STATE_EXCLUDE -> TriState.ENABLED_NOT
        else -> throw IllegalStateException("Unknown TriState state: $this")
    }
}

private fun TriState.toTriStateInt(): Int {
    return when (this) {
        TriState.DISABLED -> Filter.TriState.STATE_IGNORE
        TriState.ENABLED_IS -> Filter.TriState.STATE_INCLUDE
        TriState.ENABLED_NOT -> Filter.TriState.STATE_EXCLUDE
    }
}

@Composable
private fun SavePresetDialog(
    onDismiss: () -> Unit,
    onSave: (String, Boolean) -> Unit,
) {
    var presetName by remember { mutableStateOf("") }
    var setAsDefault by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(TDMR.strings.browse_filter_preset_save_dialog_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = presetName,
                    onValueChange = { presetName = it },
                    label = { Text(stringResource(TDMR.strings.browse_filter_preset_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = setAsDefault,
                        onCheckedChange = { setAsDefault = it },
                    )
                    Text(
                        text = stringResource(TDMR.strings.browse_filter_preset_set_default_label),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(presetName, setAsDefault) },
                enabled = presetName.isNotBlank(),
            ) {
                Text(stringResource(MR.strings.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}
