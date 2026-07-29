package eu.kanade.presentation.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DrawerState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.tachiyomi.ui.reader.ReaderChapterDrawerSnapshot
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun NovelChapterDrawer(
    drawerState: DrawerState,
    snapshot: ReaderChapterDrawerSnapshot?,
    openSessionId: Long,
    selectionInProgress: Boolean,
    onDismissRequest: () -> Unit,
    onChapterSelected: (Long) -> Unit,
    content: @Composable () -> Unit,
) {
    if (snapshot == null) {
        content()
        return
    }

    BackHandler(
        enabled = drawerState.isOpen || drawerState.isAnimationRunning,
        onBack = onDismissRequest,
    )

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen && !drawerState.isAnimationRunning,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                NovelChapterDrawerContent(
                    snapshot = snapshot,
                    openSessionId = openSessionId,
                    selectionInProgress = selectionInProgress,
                    onChapterSelected = onChapterSelected,
                )
            }
        },
        content = content,
    )
}

@Composable
private fun NovelChapterDrawerContent(
    snapshot: ReaderChapterDrawerSnapshot,
    openSessionId: Long,
    selectionInProgress: Boolean,
    onChapterSelected: (Long) -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val currentPosition by remember(listState, snapshot.currentChapterId) {
        derivedStateOf {
            val visibleItems = listState.layoutInfo.visibleItemsInfo
            when {
                visibleItems.any { it.key == snapshot.currentChapterId } -> CurrentChapterPosition.VISIBLE
                snapshot.currentIndex < 0 || visibleItems.isEmpty() -> CurrentChapterPosition.VISIBLE
                snapshot.currentIndex < visibleItems.first().index -> CurrentChapterPosition.ABOVE
                else -> CurrentChapterPosition.BELOW
            }
        }
    }
    val currentScrollIndex = (snapshot.currentIndex - 2).coerceAtLeast(0)
    val upperButtonTargetsCurrent = currentPosition == CurrentChapterPosition.ABOVE
    val lowerButtonTargetsCurrent = currentPosition == CurrentChapterPosition.BELOW

    LaunchedEffect(openSessionId, snapshot.sectionKey) {
        snapshot.currentIndex.takeIf { it >= 0 }?.let { currentIndex ->
            listState.requestScrollToItem((currentIndex - 2).coerceAtLeast(0))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .fillMaxWidth(),
    ) {
        Text(
            text = stringResource(MR.strings.chapters),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
        )
        HorizontalDivider()
        FastScrollLazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(
                items = snapshot.items,
                key = { it.id },
            ) { chapter ->
                val selected = chapter.id == snapshot.currentChapterId
                NavigationDrawerItem(
                    label = {
                        Column {
                            Text(
                                text = chapter.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurface.copy(
                                    alpha = if (chapter.read && !selected) 0.6f else 1f,
                                ),
                            )
                            if (chapter.dateUpload > 0) {
                                Text(
                                    text = relativeDateText(chapter.dateUpload),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    },
                    selected = selected,
                    onClick = {
                        if (!selectionInProgress) {
                            onChapterSelected(chapter.id)
                        }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }
        HorizontalDivider()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        listState.animateScrollToItem(
                            if (upperButtonTargetsCurrent) currentScrollIndex else 0,
                        )
                    }
                },
                enabled = snapshot.items.isNotEmpty() && !selectionInProgress,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(
                        if (upperButtonTargetsCurrent) {
                            TDMR.strings.action_current_chapter
                        } else {
                            TDMR.strings.action_scroll_to_top
                        },
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        listState.animateScrollToItem(
                            if (lowerButtonTargetsCurrent) currentScrollIndex else snapshot.items.lastIndex,
                        )
                    }
                },
                enabled = snapshot.items.isNotEmpty() && !selectionInProgress,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(
                        if (lowerButtonTargetsCurrent) {
                            TDMR.strings.action_current_chapter
                        } else {
                            TDMR.strings.action_scroll_to_bottom
                        },
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private enum class CurrentChapterPosition {
    VISIBLE,
    ABOVE,
    BELOW,
}
