@file:Suppress("ktlint:standard:filename")

package eu.kanade.presentation.manga

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.graphics.vector.ImageVector
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.ui.manga.ChapterList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal fun chapterScrollAction(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    inOverflow: Boolean,
): AppBar.AppBarAction = if (inOverflow) {
    AppBar.OverflowAction(title = title, onClick = onClick)
} else {
    AppBar.Action(title = title, icon = icon, onClick = onClick)
}

// Phone: info, actions, description, chapter header. Tablet: chapter header only.
internal fun chapterScrollHeaderCount(tablet: Boolean, sectionNavigation: Boolean): Int =
    (if (tablet) 1 else 4) + if (sectionNavigation) 1 else 0

internal fun chapterScrollIndex(items: List<ChapterList>, chapterId: Long?, headerCount: Int): Int? {
    val index = items.indexOfFirst { it is ChapterList.Item && it.id == chapterId }
    return index.takeIf { it >= 0 }?.plus(headerCount)
}

internal class ChapterScrollActions(
    val toTop: () -> Unit,
    val toNextUnread: () -> Unit,
    val toBottom: () -> Unit,
)

internal fun chapterScrollActions(
    scope: CoroutineScope,
    listState: LazyListState,
    items: List<ChapterList>,
    headerCount: Int,
    nextUnreadId: () -> Long?,
    onNoTarget: () -> Unit,
): ChapterScrollActions = ChapterScrollActions(
    toTop = { scope.launch { listState.animateScrollToItem(0) } },
    toNextUnread = {
        val index = chapterScrollIndex(items, nextUnreadId(), headerCount)
        if (index == null) {
            onNoTarget()
        } else {
            scope.launch {
                val halfHeight = listState.layoutInfo.viewportSize.height / 2
                listState.animateScrollToItem(index, scrollOffset = -halfHeight)
            }
        }
    },
    toBottom = {
        scope.launch {
            listState.animateScrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
        }
    },
)
