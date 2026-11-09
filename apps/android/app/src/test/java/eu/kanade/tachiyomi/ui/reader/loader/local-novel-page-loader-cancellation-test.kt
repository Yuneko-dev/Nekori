@file:Suppress("ktlint:standard:filename")

package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.ChapterContent
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import java.io.InterruptedIOException

class LocalNovelPageLoaderCancellationTest {
    private val chapter = ReaderChapter(Chapter.create().copy(id = 1, name = "c", url = "/c"))

    @Test
    fun `owner cancellation requeues even when source wraps it in io failure`() = runBlocking {
        withTimeout(5_000) {
            for (wrap in listOf(false, true)) {
                val entered = CompletableDeferred<Unit>()
                val source = mockk<Source> {
                    every { isNovelSource } returns true
                    coEvery { fetchChapterContent(any()) } coAnswers {
                        entered.complete(Unit)
                        try {
                            awaitCancellation()
                        } catch (error: CancellationException) {
                            if (wrap) throw InterruptedIOException("cancelled socket") else throw error
                        }
                    }
                }
                val loader = LocalNovelPageLoader(chapter, source)
                val page = ReaderPage(0, "/c")
                val owner = launch { loader.loadPage(page) }
                entered.await()
                owner.cancelAndJoin()
                assertEquals(Page.State.Queue, page.status)
            }
        }
    }

    @Test
    fun `a source swallowing cancellation cannot publish stale content`() = runBlocking {
        withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            val source = mockk<Source> {
                every { isNovelSource } returns true
                coEvery { fetchChapterContent(any()) } coAnswers {
                    entered.complete(Unit)
                    try {
                        awaitCancellation()
                    } catch (_: CancellationException) {
                        ChapterContent(html = "stale")
                    }
                }
            }
            val loader = LocalNovelPageLoader(chapter, source)
            val page = ReaderPage(0, "/c")
            val owner = launch { loader.loadPage(page) }
            entered.await()
            owner.cancelAndJoin()
            assertEquals(Page.State.Queue, page.status)
            assertNull(page.chapterContent)
        }
    }

    @Test
    fun `source cancellation and timeout are errors while caller stays active`() = runBlocking {
        for (timeout in listOf(false, true)) {
            val source = mockk<Source> {
                every { isNovelSource } returns true
                coEvery { fetchChapterContent(any()) } coAnswers {
                    if (timeout) withTimeout(1) { awaitCancellation() }
                    throw CancellationException("source failed")
                }
            }
            val page = ReaderPage(0, "/c")
            LocalNovelPageLoader(chapter, source).loadPage(page)
            val state = page.status
            assertTrue(state is Page.State.Error)
            assertTrue((state as Page.State.Error).error is CancellationException)
            if (timeout) assertTrue(state.error is TimeoutCancellationException)
        }
    }
}
