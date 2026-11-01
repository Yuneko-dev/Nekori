@file:Suppress("ktlint:standard:filename")

package eu.kanade.presentation.manga

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.unit.IntSize
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.manga.ChapterList
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class ChapterScrollActionsTest {
    @Test
    fun `actions use the mapped row center and actual list edges`() = runTest {
        val listState = listState(totalItems = 8)
        val actions = chapterScrollActions(
            scope = backgroundScope,
            listState = listState,
            items = listOf(item(31), ChapterList.MissingCount("gap", 4), item(7)),
            headerCount = 5,
            nextUnreadId = { 7 },
            onNoTarget = { error("Target is in the visible list") },
        )
        actions.toNextUnread()
        actions.toBottom()
        actions.toTop()
        runCurrent()
        coVerify(exactly = 1) { listState.animateScrollToItem(7, -300) }
        coVerify(exactly = 1) { listState.animateScrollToItem(7, 0) }
        coVerify(exactly = 1) { listState.animateScrollToItem(0, 0) }
    }

    @Test
    fun `empty list reports no unread target and keeps the bottom index valid`() = runTest {
        val listState = listState(totalItems = 1)
        var noTargets = 0
        val actions = chapterScrollActions(
            scope = backgroundScope,
            listState = listState,
            items = emptyList(),
            headerCount = 1,
            nextUnreadId = { null },
            onNoTarget = { noTargets++ },
        )
        actions.toNextUnread()
        runCurrent()
        assertEquals(1, noTargets)
        coVerify(exactly = 0) { listState.animateScrollToItem(any(), any()) }
        actions.toBottom()
        runCurrent()
        coVerify(exactly = 1) { listState.animateScrollToItem(0, 0) }
    }

    @Test
    fun `header counts include volume or page navigation on both layouts`() {
        assertEquals(4, chapterScrollHeaderCount(tablet = false, sectionNavigation = false))
        assertEquals(5, chapterScrollHeaderCount(tablet = false, sectionNavigation = true))
        assertEquals(1, chapterScrollHeaderCount(tablet = true, sectionNavigation = false))
        assertEquals(2, chapterScrollHeaderCount(tablet = true, sectionNavigation = true))
    }

    @Test
    fun `chapter targets include headers and missing count rows without using chapter numbers`() {
        val rows = listOf(item(31), ChapterList.MissingCount("gap", 4), item(7))
        for (tablet in listOf(false, true)) {
            for (section in listOf(false, true)) {
                val headers = chapterScrollHeaderCount(tablet, section)
                assertEquals(headers, chapterScrollIndex(rows, 31, headers))
                assertEquals(headers + 2, chapterScrollIndex(rows, 7, headers))
            }
        }
    }

    @Test
    fun `hidden absent or empty unread targets cannot become a header index`() {
        val rows = listOf(item(31))
        assertNull(chapterScrollIndex(rows, null, 5))
        assertNull(chapterScrollIndex(rows, 99, 5))
        assertNull(chapterScrollIndex(emptyList(), 31, 5))
    }

    private fun item(id: Long) = ChapterList.Item(
        chapter = Chapter.create().copy(id = id),
        downloadState = Download.State.NOT_DOWNLOADED,
        downloadProgress = 0,
    )

    private fun listState(totalItems: Int) = mockk<LazyListState> {
        every { layoutInfo } returns mockk {
            every { totalItemsCount } returns totalItems
            every { viewportSize } returns IntSize(400, 600)
        }
        coEvery { animateScrollToItem(any(), any()) } returns Unit
    }
}
