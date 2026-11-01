@file:Suppress("ktlint:standard:filename")

package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.jsplugin.source.JsSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.ChapterContent
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import java.util.concurrent.atomic.AtomicInteger

class LocalNovelPageLoaderConcurrencyTest {
    private val chapter = ReaderChapter(Chapter.create().copy(id = 1, name = "c", url = "/c"))

    @Test
    fun `concurrent force refresh callers share one invalidation and structured payload`() = runBlocking {
        withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val observed = CompletableDeferred<Unit>()
            val payload = ChapterContent(
                state = "checkpoint",
                type = "mixed",
                html = "<p>Continue in browser</p>",
                noCache = true,
                noPrefetch = true,
                checkpointMessage = "Sign in",
            )
            val source = mockk<JsSource> {
                every { isNovelSource } returns true
                coEvery { invalidateChapterText("/c") } returns Unit
                coEvery { fetchChapterContent(any()) } coAnswers {
                    entered.complete(Unit)
                    release.await()
                    payload
                }
            }
            val loader = LocalNovelPageLoader(chapter, source, forceRefresh = true)
            val page = spyk(ReaderPage(0, "/c"))
            every { page.status } answers {
                callOriginal().also { if (it == Page.State.LoadPage) observed.complete(Unit) }
            }
            val first = async { loader.loadPage(page) }
            entered.await()
            val second = async { loader.loadPage(page) }
            observed.await()
            release.complete(Unit)
            first.await()
            second.await()

            coVerify(exactly = 1) { source.invalidateChapterText("/c") }
            coVerify(exactly = 1) { source.fetchChapterContent(page) }
            assertSame(payload, page.chapterContent)
            assertEquals(Page.State.Ready, page.status)
        }
    }

    @Test
    fun `surviving direct waiter takes over a cancelled owner`() = runBlocking {
        withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            val observed = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            lateinit var owner: Job
            val source = mockk<Source> {
                every { isNovelSource } returns true
                coEvery { fetchChapterContent(any()) } coAnswers {
                    if (calls.incrementAndGet() == 1) {
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                    assertTrue(owner.isCancelled, "A secondary caller must wait until the owner releases the claim")
                    ChapterContent(html = "next")
                }
            }
            val loader = LocalNovelPageLoader(chapter, source)
            val page = spyk(ReaderPage(0, "/c"))
            every { page.status } answers {
                callOriginal().also { if (it == Page.State.LoadPage) observed.complete(Unit) }
            }
            owner = launch { loader.loadPage(page) }
            entered.await()
            val waiter = async { loader.loadPage(page) }
            observed.await()
            owner.cancelAndJoin()
            waiter.await()

            assertEquals(2, calls.get())
            assertEquals(Page.State.Ready, page.status)
            assertEquals("next", page.text)
        }
    }

    @Test
    fun `cancelling a secondary direct waiter leaves the owner running`() = runBlocking {
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
            val waiter = launch { loader.loadPage(page) }
            observed.await()
            waiter.cancelAndJoin()
            assertEquals(Page.State.LoadPage, page.status)
            release.complete(Unit)
            owner.await()

            assertEquals(Page.State.Ready, page.status)
            coVerify(exactly = 1) { source.fetchChapterContent(page) }
        }
    }
}
