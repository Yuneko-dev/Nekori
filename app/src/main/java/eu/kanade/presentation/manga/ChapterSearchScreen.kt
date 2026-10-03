package eu.kanade.presentation.manga

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.tachiyomi.ui.manga.search.ChapterSearchOptions
import eu.kanade.tachiyomi.ui.manga.search.ChapterSearchResult
import eu.kanade.tachiyomi.ui.manga.search.ChapterSearchScreen
import eu.kanade.tachiyomi.ui.manga.search.ChapterSearchSnippet
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.secondaryItemAlpha

private const val COLLAPSED_SNIPPETS = 3

@Composable
fun ChapterSearchScreenContent(
    state: ChapterSearchScreen.State,
    navigateUp: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onOptionsChange: (ChapterSearchOptions) -> Unit,
    onResultClick: (Chapter, ChapterSearchSnippet?) -> Unit,
) {
    Scaffold(
        topBar = { scrollBehavior ->
            Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                Box {
                    SearchToolbar(
                        searchQuery = state.query,
                        onChangeSearchQuery = { onQueryChange(it.orEmpty()) },
                        placeholderText = stringResource(TDMR.strings.chapter_search_hint),
                        onSearch = { onSearch() },
                        onClickCloseSearch = navigateUp,
                        navigateUp = navigateUp,
                        scrollBehavior = scrollBehavior,
                    )
                    val total = state.chapters?.size ?: 0
                    if (state.isSearching && total > 0) {
                        LinearProgressIndicator(
                            progress = { state.searchedCount / total.toFloat() },
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth(),
                        )
                    }
                }
                SearchOptionsRow(options = state.options, onOptionsChange = onOptionsChange)
                if (state.options.isRegex) {
                    Text(
                        text = stringResource(TDMR.strings.chapter_search_regex_hint),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
                    )
                }
            }
        },
    ) { contentPadding ->
        val chapters = state.chapters
        when {
            chapters == null -> LoadingScreen(Modifier.padding(contentPadding))
            chapters.isEmpty() -> EmptyScreen(
                stringRes = TDMR.strings.chapter_search_no_chapters,
                modifier = Modifier.padding(contentPadding),
            )
            state.regexError != null -> EmptyScreen(
                message = stringResource(TDMR.strings.novel_invalid_regex_format, state.regexError),
                modifier = Modifier.padding(contentPadding),
            )
            state.submittedQuery == null -> EmptyScreen(
                stringRes = TDMR.strings.chapter_search_empty,
                modifier = Modifier.padding(contentPadding),
            )
            !state.isSearching && state.results.isEmpty() && state.failedCount == 0 -> EmptyScreen(
                stringRes = MR.strings.no_results_found,
                modifier = Modifier.padding(contentPadding),
            )
            else -> SearchResults(
                state = state,
                totalChapters = chapters.size,
                contentPadding = contentPadding,
                onResultClick = onResultClick,
            )
        }
    }
}

@Composable
private fun SearchOptionsRow(
    options: ChapterSearchOptions,
    onOptionsChange: (ChapterSearchOptions) -> Unit,
) {
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = MaterialTheme.padding.small),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        FilterChip(
            selected = options.isRegex,
            onClick = { onOptionsChange(options.copy(isRegex = !options.isRegex)) },
            label = { Text(stringResource(TDMR.strings.novel_use_regex)) },
        )
        FilterChip(
            selected = options.caseSensitive,
            onClick = { onOptionsChange(options.copy(caseSensitive = !options.caseSensitive)) },
            label = { Text(stringResource(TDMR.strings.label_case_sensitive_matching)) },
        )
        FilterChip(
            selected = options.wholeWord,
            onClick = { onOptionsChange(options.copy(wholeWord = !options.wholeWord)) },
            label = { Text(stringResource(TDMR.strings.novel_match_whole_word)) },
        )
    }
}

@Composable
private fun SearchResults(
    state: ChapterSearchScreen.State,
    totalChapters: Int,
    contentPadding: PaddingValues,
    onResultClick: (Chapter, ChapterSearchSnippet?) -> Unit,
) {
    val expandedChapters = remember(state.submittedQuery, state.options) { mutableStateMapOf<Long, Boolean>() }
    FastScrollLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item(key = "summary") {
            SearchSummary(state = state, totalChapters = totalChapters)
        }
        state.results.forEach { result ->
            val chapterId = result.chapter.id
            val expanded = expandedChapters[chapterId] == true
            item(key = chapterId, contentType = "chapter") {
                SearchResultHeader(
                    result = result,
                    expanded = expanded,
                    onToggleExpanded = { expandedChapters[chapterId] = !expanded },
                    onClick = { onResultClick(result.chapter, null) },
                )
            }
            items(
                count = if (expanded) result.matchCount else minOf(COLLAPSED_SNIPPETS, result.matchCount),
                key = { "$chapterId-match-$it" },
                contentType = { "snippet" },
            ) { index ->
                val snippet = result.snippets[index]
                ListItem(
                    modifier = Modifier.clickable { onResultClick(result.chapter, snippet) },
                    content = { SnippetText(snippet) },
                    trailingContent = {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = stringResource(TDMR.strings.chapter_search_open_match),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun SearchSummary(
    state: ChapterSearchScreen.State,
    totalChapters: Int,
) {
    val text = when {
        state.isSearching -> stringResource(TDMR.strings.chapter_search_progress, state.searchedCount, totalChapters)
        state.results.isEmpty() -> stringResource(MR.strings.no_results_found)
        else -> pluralStringResource(TDMR.plurals.chapter_search_matches, state.totalMatches, state.totalMatches) +
            " " +
            pluralStringResource(TDMR.plurals.chapter_search_matched_chapters, state.results.size, state.results.size)
    }
    Column(
        modifier = Modifier.padding(
            horizontal = MaterialTheme.padding.medium,
            vertical = MaterialTheme.padding.small,
        ),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.secondaryItemAlpha(),
        )
        if (state.failedCount > 0) {
            Text(
                text = pluralStringResource(
                    TDMR.plurals.chapter_search_failed_chapters,
                    state.failedCount,
                    state.failedCount,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun SearchResultHeader(
    result: ChapterSearchResult,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        content = {
            Text(
                result.chapter.name,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                pluralStringResource(TDMR.plurals.chapter_search_matches, result.matchCount, result.matchCount),
                color = MaterialTheme.colorScheme.primary,
            )
        },
        trailingContent = {
            if (result.matchCount > COLLAPSED_SNIPPETS) {
                IconButton(onClick = onToggleExpanded) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = stringResource(
                            if (expanded) TDMR.strings.action_collapse else TDMR.strings.action_expand,
                        ),
                    )
                }
            }
        },
    )
}

@Composable
private fun SnippetText(snippet: ChapterSearchSnippet) {
    val highlight = SpanStyle(
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        background = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
    )
    val positionColor = MaterialTheme.colorScheme.onSurfaceVariant
    val text = remember(snippet, highlight, positionColor) {
        val excerpt = snippet.text
        buildAnnotatedString {
            append(excerpt.substring(0, snippet.matchStart))
            withStyle(highlight) { append(excerpt.substring(snippet.matchStart, snippet.matchEnd)) }
            append(excerpt.substring(snippet.matchEnd))
            withStyle(SpanStyle(color = positionColor)) {
                append(" (%.1f%%)".format(snippet.position * 100))
            }
        }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = MaterialTheme.padding.extraSmall),
    )
}
