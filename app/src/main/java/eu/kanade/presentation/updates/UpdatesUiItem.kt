@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.presentation.updates

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.manga.components.ChapterDownloadIndicator
import eu.kanade.presentation.manga.components.DotSeparatorText
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.util.relativeTimeSpanString
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.updates.UpdatesItem
import tachiyomi.domain.updates.model.UpdatesWithRelations
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.components.ListGroupHeader
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.selectedBackground

internal fun LazyListScope.updatesLastUpdatedItem(
    lastUpdated: Long,
) {
    item(key = "updates-lastUpdated") {
        Box(
            modifier = Modifier
                .animateItem(fadeInSpec = null, fadeOutSpec = null)
                .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
        ) {
            Text(
                text = stringResource(MR.strings.updates_last_update_info, relativeTimeSpanString(lastUpdated)),
                fontStyle = FontStyle.Italic,
            )
        }
    }
}

internal fun LazyListScope.updatesUiItems(
    uiModels: List<UpdatesUiModel>,
    isGroupExpanded: (UpdatesUiModel.GroupKey) -> Boolean,
    onToggleGroup: (UpdatesUiModel.GroupKey) -> Unit,
    selectionMode: Boolean,
    onUpdateSelected: (UpdatesItem, Boolean) -> Unit,
    onUpdateGroupSelected: (List<UpdatesItem>, Boolean) -> Unit,
    onClickCover: (UpdatesItem) -> Unit,
    onClickUpdate: (UpdatesItem) -> Unit,
    onDownloadChapter: (List<UpdatesItem>, ChapterDownloadAction) -> Unit,
) {
    uiModels.forEach { model ->
        when (model) {
            is UpdatesUiModel.Header -> item(
                key = "updates-header-${model.date}",
                contentType = "header",
            ) {
                ListGroupHeader(
                    modifier = Modifier.animateItem(),
                    text = relativeDateText(model.date),
                )
            }
            is UpdatesUiModel.Item -> updatesChapterItem(
                updatesItem = model.item,
                selectionMode = selectionMode,
                onUpdateSelected = onUpdateSelected,
                onClickCover = onClickCover,
                onClickUpdate = onClickUpdate,
                onDownloadChapter = onDownloadChapter,
            )
            is UpdatesUiModel.Group -> {
                val expanded = isGroupExpanded(model.key)
                item(
                    key = "updates-group-${model.key.date}-${model.key.mangaId}",
                    contentType = "group",
                ) {
                    UpdatesPerDayNovelGroupItem(
                        item = model.items.first(),
                        chapterCount = model.items.size,
                        hasUnread = model.hasUnread,
                        selected = model.allSelected,
                        expanded = expanded,
                        onClickCover = { onClickCover(model.items.first()) }.takeIf { !selectionMode },
                        onClick = {
                            if (selectionMode) {
                                onUpdateGroupSelected(model.items, !model.allSelected)
                            } else {
                                onToggleGroup(model.key)
                            }
                        },
                        onLongClick = { onUpdateGroupSelected(model.items, true) },
                        onToggle = { onToggleGroup(model.key) },
                        modifier = Modifier.animateItem(),
                    )
                }
                if (expanded) {
                    model.items.forEach { updatesItem ->
                        updatesChapterItem(
                            updatesItem = updatesItem,
                            selectionMode = selectionMode,
                            onUpdateSelected = onUpdateSelected,
                            onClickCover = onClickCover,
                            onClickUpdate = onClickUpdate,
                            onDownloadChapter = onDownloadChapter,
                        )
                    }
                }
            }
        }
    }
}

private fun LazyListScope.updatesChapterItem(
    updatesItem: UpdatesItem,
    selectionMode: Boolean,
    onUpdateSelected: (UpdatesItem, Boolean) -> Unit,
    onClickCover: (UpdatesItem) -> Unit,
    onClickUpdate: (UpdatesItem) -> Unit,
    onDownloadChapter: (List<UpdatesItem>, ChapterDownloadAction) -> Unit,
) {
    item(
        key = "updates-${updatesItem.update.mangaId}-${updatesItem.update.chapterId}-${updatesItem.update.dateFetch}",
        contentType = "item",
    ) {
        UpdatesUiItem(
            modifier = Modifier.animateItem(),
            update = updatesItem.update,
            selected = updatesItem.selected,
            readProgress = updatesItem.update.lastPageRead
                .takeIf { !updatesItem.update.read && it > 0L }
                ?.let {
                    if (updatesItem.isNovel) {
                        stringResource(TDMR.strings.chapter_progress_novel, it.toInt())
                    } else {
                        stringResource(MR.strings.chapter_progress, it + 1)
                    }
                },
            onLongClick = { onUpdateSelected(updatesItem, !updatesItem.selected) },
            onClick = {
                if (selectionMode) {
                    onUpdateSelected(updatesItem, !updatesItem.selected)
                } else {
                    onClickUpdate(updatesItem)
                }
            },
            onClickCover = { onClickCover(updatesItem) }.takeIf { !selectionMode },
            onDownloadChapter = { action: ChapterDownloadAction ->
                onDownloadChapter(listOf(updatesItem), action)
            }.takeIf { !selectionMode },
            downloadStateProvider = updatesItem.downloadStateProvider,
            downloadProgressProvider = updatesItem.downloadProgressProvider,
        )
    }
}

@Composable
private fun UpdatesPerDayNovelGroupItem(
    item: UpdatesItem,
    chapterCount: Int,
    hasUnread: Boolean,
    selected: Boolean,
    expanded: Boolean,
    onClickCover: (() -> Unit)?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .selectedBackground(selected)
            .height(56.dp)
            .padding(start = MaterialTheme.padding.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MangaCover.Square(
            modifier = Modifier
                .padding(vertical = 6.dp)
                .fillMaxHeight(),
            data = item.update.coverData,
            contentDescription = item.update.mangaTitle,
            onClick = onClickCover,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
                .padding(horizontal = MaterialTheme.padding.medium),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = item.update.mangaTitle,
                maxLines = 1,
                style = MaterialTheme.typography.bodyMedium,
                color = LocalContentColor.current.copy(alpha = if (hasUnread) 1f else DISABLED_ALPHA),
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (hasUnread) {
                    Icon(
                        imageVector = Icons.Filled.Circle,
                        contentDescription = stringResource(MR.strings.unread),
                        modifier = Modifier
                            .height(8.dp)
                            .padding(end = 4.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = pluralStringResource(
                        MR.plurals.notification_chapters_generic,
                        count = chapterCount,
                        chapterCount,
                    ),
                    maxLines = 1,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = if (hasUnread) 1f else DISABLED_ALPHA),
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onToggle) {
            Icon(
                imageVector = if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                contentDescription = stringResource(
                    if (expanded) TDMR.strings.action_collapse else TDMR.strings.action_expand,
                ),
            )
        }
    }
}

@Composable
private fun UpdatesUiItem(
    update: UpdatesWithRelations,
    selected: Boolean,
    readProgress: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onClickCover: (() -> Unit)?,
    onDownloadChapter: ((ChapterDownloadAction) -> Unit)?,
    // Download Indicator
    downloadStateProvider: () -> Download.State,
    downloadProgressProvider: () -> Int,
    modifier: Modifier = Modifier,
) {
    val textAlpha = if (update.read) DISABLED_ALPHA else 1f

    Row(
        modifier = modifier
            .selectedBackground(selected)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .height(56.dp)
            .padding(horizontal = MaterialTheme.padding.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MangaCover.Square(
            modifier = Modifier
                .padding(vertical = 6.dp)
                .fillMaxHeight(),
            data = update.coverData,
            onClick = onClickCover,
        )

        Column(
            modifier = Modifier
                .padding(horizontal = MaterialTheme.padding.medium)
                .weight(1f),
        ) {
            Text(
                text = update.mangaTitle,
                maxLines = 1,
                style = MaterialTheme.typography.bodyMedium,
                color = LocalContentColor.current.copy(alpha = textAlpha),
                overflow = TextOverflow.Ellipsis,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                var textHeight by remember { mutableIntStateOf(0) }
                if (!update.read) {
                    Icon(
                        imageVector = Icons.Filled.Circle,
                        contentDescription = stringResource(MR.strings.unread),
                        modifier = Modifier
                            .height(8.dp)
                            .padding(end = 4.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                if (update.bookmark) {
                    Icon(
                        imageVector = Icons.Filled.Bookmark,
                        contentDescription = stringResource(MR.strings.action_filter_bookmarked),
                        modifier = Modifier
                            .sizeIn(maxHeight = with(LocalDensity.current) { textHeight.toDp() - 2.dp }),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                }
                Text(
                    text = update.chapterName,
                    maxLines = 1,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = textAlpha),
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { textHeight = it.size.height },
                    modifier = Modifier
                        .weight(weight = 1f, fill = false),
                )
                if (readProgress != null) {
                    DotSeparatorText()
                    Text(
                        text = readProgress,
                        maxLines = 1,
                        color = LocalContentColor.current.copy(alpha = DISABLED_ALPHA),
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        ChapterDownloadIndicator(
            enabled = onDownloadChapter != null,
            modifier = Modifier.padding(start = 4.dp),
            downloadStateProvider = downloadStateProvider,
            downloadProgressProvider = downloadProgressProvider,
            onClick = { onDownloadChapter?.invoke(it) },
        )
    }
}
