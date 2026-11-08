@file:Suppress("ktlint:standard:filename")

package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.ChapterContent
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.loader.LocalNovelPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import java.util.concurrent.atomic.AtomicInteger

class NovelPageLoaderHandoffTest {
    private val chapter = ReaderChapter(Chapter.create().copy(id = 1, name = "c", url = "/c"))

    @Test
    fun `helper waiter recovers when its direct fetch owner is cancelled`() = runBlocking {
        withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            val observed = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val source = mockk<Source> {
                every { isNovelSource } returns true
                coEvery { fetchChapterContent(any()) } coAnswers {
                    if (calls.incrementAndGet() == 1) {
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                    ChapterContent(html = "recovered", noCache = true, noPrefetch = true)
                }
            }
            val loader = LocalNovelPageLoader(chapter, source)
            val page = spyk(ReaderPage(0, "/c"))
            every { page.status } answers {
                callOriginal().also { if (it == Page.State.LoadPage) observed.complete(Unit) }
            }
            val owner = launch { loader.loadPage(page) }
            entered.await()
            val waiter = async { NovelPageLoader.awaitPageText("test", page, loader, 3_000, this@withTimeout) }
            observed.await()
            owner.cancelAndJoin()

            assertTrue(waiter.await())
            assertEquals(2, calls.get())
            assertEquals(ChapterContent(html = "recovered", noCache = true, noPrefetch = true), page.chapterContent)
        }
    }

    @Test
    fun `cancelling helper waiter cannot cancel or poison another callers fetch`() = runBlocking {
        withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            val observed = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val source = mockk<Source> {
                every { isNovelSource } returns true
                coEvery { fetchChapterContent(any()) } coAnswers {
                    entered.complete(Unit)
                    release.await()
                    ChapterContent(html = "kept")
                }
            }
            val loader = LocalNovelPageLoader(chapter, source)
            val page = spyk(ReaderPage(0, "/c"))
            every { page.status } answers {
                callOriginal().also { if (it == Page.State.LoadPage) observed.complete(Unit) }
            }
            val owner = async { loader.loadPage(page) }
            entered.await()
            val waiter = launch { NovelPageLoader.awaitPageText("test", page, loader, 3_000, this@withTimeout) }
            observed.await()
            waiter.cancelAndJoin()
            assertTrue(owner.isActive)
            assertEquals(Page.State.LoadPage, page.status)
            release.complete(Unit)
            owner.await()
            assertEquals(Page.State.Ready, page.status)
            coVerify(exactly = 1) { source.fetchChapterContent(page) }
        }
    }
}
