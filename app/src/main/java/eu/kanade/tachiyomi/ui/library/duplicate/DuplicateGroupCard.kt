package eu.kanade.tachiyomi.ui.library.duplicate

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.getNameForMangaInfo
import eu.kanade.tachiyomi.util.source.getMangaUrlOrNull
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.domain.category.model.Category as CategoryModel

/**
 * One duplicate group: a header carrying the group's own actions, and its members as selectable
 * list rows. Uses [ListItem] so row height, padding and the selected-container colour come from the
 * Material 3 tokens rather than hand-tuned dp values.
 */
@Composable
fun DuplicateGroupCard(
    groupTitle: String,
    mangaList: List<MangaWithChapterCount>,
    fullGroupCount: Int,
    canSelectHiddenTail: Boolean,
    allSelected: Boolean,
    selection: Set<Long>,
    mangaCategories: Map<Long, List<CategoryModel>>,
    showFullUrls: Boolean,
    onToggleSelection: (Long) -> Unit,
    onSelectGroup: () -> Unit,
    onDismissGroup: () -> Unit,
    onClickManga: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(groupTitle) { mutableStateOf(true) }

    OutlinedCard(modifier = modifier.fillMaxWidth()) {
        ListItem(
            supportingContent = {
                Column {
                    Text(stringResource(TDMR.strings.duplicate_n_in_group, mangaList.size))
                    if (fullGroupCount > mangaList.size) {
                        Text(
                            text = stringResource(
                                if (canSelectHiddenTail) {
                                    TDMR.strings.duplicate_group_truncated_selectable
                                } else {
                                    TDMR.strings.duplicate_group_truncated_filtered
                                },
                                mangaList.size,
                                fullGroupCount,
                            ),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onSelectGroup) {
                        Icon(
                            imageVector = if (allSelected) Icons.Outlined.CheckBox else Icons.Filled.SelectAll,
                            contentDescription = stringResource(
                                if (allSelected) {
                                    TDMR.strings.duplicate_deselect_group
                                } else {
                                    TDMR.strings.duplicate_select_group
                                },
                            ),
                        )
                    }
                    IconButton(onClick = onDismissGroup) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(TDMR.strings.duplicate_dismiss_group),
                        )
                    }
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = stringResource(
                                if (expanded) TDMR.strings.action_collapse else TDMR.strings.action_expand,
                            ),
                        )
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        ) {
            Text(
                text = groupTitle.replaceFirstChar { it.uppercaseChar() },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column {
                HorizontalDivider()
                mangaList.forEachIndexed { index, entry ->
                    DuplicateItem(
                        manga = entry,
                        categories = mangaCategories[entry.manga.id].orEmpty(),
                        isSelected = entry.manga.id in selection,
                        isFirst = index == 0,
                        showFullUrl = showFullUrls,
                        onToggleSelection = { onToggleSelection(entry.manga.id) },
                        onClick = { onClickManga(entry.manga.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DuplicateItem(
    manga: MangaWithChapterCount,
    categories: List<CategoryModel>,
    isSelected: Boolean,
    isFirst: Boolean,
    showFullUrl: Boolean,
    onToggleSelection: () -> Unit,
    onClick: () -> Unit,
) {
    val sourceManager: SourceManager = remember { Injekt.get() }
    val downloadManager: DownloadManager = remember { Injekt.get() }
    val source = remember(manga.manga.source) { sourceManager.getOrStub(manga.manga.source) }
    val sourceName = remember(source) { source.getNameForMangaInfo() }
    val downloadedCount = remember(manga.manga.id) { downloadManager.getDownloadCount(manga.manga) }
    val defaultCategoryLabel = stringResource(MR.strings.label_default)

    // Chapter/download/read/source/author collapse into one separated line: they are peer facts about
    // the row, and one wrapping line reads better than five competing coloured fragments.
    val metadata = buildList {
        add(stringResource(TDMR.strings.duplicate_n_chapters, manga.chapterCount))
        if (downloadedCount > 0) add(stringResource(TDMR.strings.duplicate_n_downloads, downloadedCount))
        if (manga.readCount > 0) add(stringResource(TDMR.strings.duplicate_n_read, manga.readCount.toInt()))
        add(sourceName)
        manga.manga.author?.takeIf { it.isNotBlank() }?.let(::add)
    }.joinToString(SEPARATOR)

    ListItem(
        leadingContent = {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggleSelection() },
            )
        },
        overlineContent = if (isFirst) {
            {
                Text(
                    text = stringResource(TDMR.strings.duplicate_original),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            null
        },
        supportingContent = {
            Column {
                Text(text = metadata, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (categories.isNotEmpty()) {
                    Text(
                        text = stringResource(
                            TDMR.strings.duplicate_categories_label,
                            categories.joinToString(", ") { it.name.ifBlank { defaultCategoryLabel } },
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (showFullUrl) {
                    val fullUrl = remember(manga.manga.url, source) {
                        source.getMangaUrlOrNull(manga.manga.toSManga()) ?: manga.manga.url
                    }
                    Text(
                        text = fullUrl,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        // The row opens the entry; the checkbox owns selection.
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                Color.Transparent
            },
        ),
    ) {
        Text(
            text = manga.manga.title,
            // The first row is the one the "all except first" actions keep, so it is marked.
            fontWeight = if (isFirst) FontWeight.SemiBold else null,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val SEPARATOR = " • "
