package eu.kanade.domain.chapter.interactor

import eu.kanade.tachiyomi.ui.reader.adjacentPagedPagesToLoad
import eu.kanade.tachiyomi.ui.reader.isPagedAdjacentPage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PagedSourceOrderTest {

    @Test
    fun loadedPagesFollowReaderQueueOrder() {
        val queue = listOf(1L, 2L, 6L)
            .flatMap { page ->
                List(50) { sourceIndex ->
                    LoadedChapter(
                        number = (page * 50 - sourceIndex).toInt(),
                        sourceOrder = pagedSourceOrder(
                            totalPages = 76,
                            pageNumber = page,
                            indexInPage = sourceIndex,
                        ),
                    )
                }
            }
            .sortedByDescending { it.sourceOrder }
            .map { it.number }

        assertEquals((1..100).toList() + (251..300).toList(), queue)
        assertEquals(51, queue.getOrNull(queue.indexOf(50) + 1))
        assertEquals(50, queue.getOrNull(queue.indexOf(51) - 1))
        assertNull(queue.getOrNull(queue.indexOf(300) + 1))
        assertEquals(
            pagedSourceOrder(totalPages = 76, pageNumber = 2, indexInPage = 0),
            pagedSourceOrder(totalPages = 77, pageNumber = 2, indexInPage = 0),
        )
        assertTrue(isPagedAdjacentPage(currentPage = 4, candidatePage = 4, pageDelta = -1))
        assertTrue(isPagedAdjacentPage(currentPage = 4, candidatePage = 3, pageDelta = -1))
        assertFalse(isPagedAdjacentPage(currentPage = 4, candidatePage = 1, pageDelta = -1))
        assertEquals(
            listOf(2L),
            adjacentPagedPagesToLoad(
                currentPage = 1,
                totalPages = 76,
                previousCandidatePage = null,
                nextCandidatePage = null,
                loadedPages = setOf(1),
            ),
        )
        assertEquals(
            listOf(3L),
            adjacentPagedPagesToLoad(
                currentPage = 4,
                totalPages = 76,
                previousCandidatePage = 1,
                nextCandidatePage = 4,
                loadedPages = setOf(1, 4, 5),
            ),
        )
        assertEquals(
            listOf(6L),
            adjacentPagedPagesToLoad(
                currentPage = 5,
                totalPages = 76,
                previousCandidatePage = 5,
                nextCandidatePage = null,
                loadedPages = setOf(1, 4, 5),
            ),
        )
    }

    private data class LoadedChapter(
        val number: Int,
        val sourceOrder: Long,
    )
}
