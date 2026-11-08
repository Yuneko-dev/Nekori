@file:Suppress("ktlint:standard:filename")

package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

import eu.kanade.tachiyomi.source.model.ChapterContent
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import io.mockk.every
import io.mockk.spyk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class NovelPageLoaderRecoveryTest {
    @Test
    fun `a cancelled supplied scope fails before retrying queued work`() = runBlocking {
        val job = Job().apply { cancel(CancellationException("reader scope closed")) }
        val scope = CoroutineScope(job + Dispatchers.IO)
        val loads = AtomicInteger()
        val loader = object : PageLoader() {
            override var isLocal = true
            override suspend fun getPages() = emptyList<ReaderPage>()
            override suspend fun loadPage(page: ReaderPage) {
                loads.incrementAndGet()
            }
        }
        // A separate dispatcher lets the timeout bound even an accidental synchronous Queue loop.
        val failure = async(Dispatchers.Default) {
            runCatching {
                NovelPageLoader.awaitPageText("test", ReaderPage(0, "/c"), loader, 500, scope)
            }.exceptionOrNull()
        }.await()

        assertTrue(failure is CancellationException)
        assertFalse(failure is TimeoutCancellationException, "A closed scope must fail before the page timeout")
        assertEquals("reader scope closed", failure?.message)
        assertEquals(0, loads.get())
    }

    @Test
    fun `first loading snapshot retains recovery when owner immediately requeues`() = runBlocking {
        withTimeout(5_000) {
            val loads = AtomicInteger()
            val reads = AtomicInteger()
            val page = spyk(ReaderPage(0, "/c"))
            page.status = Page.State.LoadPage
            // Models the owner re-queuing between the helper's snapshot and its subscription.
            every { page.status } answers {
                callOriginal().also {
                    if (reads.incrementAndGet() == 1) page.status = Page.State.Queue
                }
            }
            val loader = object : PageLoader() {
                override var isLocal = true
                override suspend fun getPages() = emptyList<ReaderPage>()
                override suspend fun loadPage(page: ReaderPage) {
                    loads.incrementAndGet()
                    page.chapterContent = ChapterContent(html = "recovered")
                    page.status = Page.State.Ready
                }
            }

            assertTrue(NovelPageLoader.awaitPageText("test", page, loader, 3_000, this@withTimeout))
            assertEquals(1, loads.get())
            assertEquals(1, reads.get(), "Recovery ownership comes from one initial status snapshot")
        }
    }

    @Test
    fun `loader owned retry is not duplicated by helper`() = runBlocking {
        withTimeout(5_000) {
            val retried = CompletableDeferred<Unit>()
            val loads = AtomicInteger()
            val page = ReaderPage(0, "/c").apply { status = Page.State.Error(Exception("old")) }
            val loader = object : PageLoader() {
                override var isLocal = false
                override val handlesPageRetries = true
                override suspend fun getPages() = emptyList<ReaderPage>()
                override suspend fun loadPage(page: ReaderPage) {
                    loads.incrementAndGet()
                }
                override fun retryPage(page: ReaderPage) {
                    page.status = Page.State.Queue
                    retried.complete(Unit)
                }
            }
            val waiting = async { NovelPageLoader.awaitPageText("test", page, loader, 3_000, this@withTimeout) }
            retried.await()
            val payload = ChapterContent(html = "retry worker")
            page.chapterContent = payload
            page.status = Page.State.Ready

            assertTrue(waiting.await())
            assertEquals(0, loads.get())
            assertSame(payload, page.chapterContent)
        }
    }

    @Test
    fun `failed error claim preserves a ready result published during retry`() = runBlocking {
        withTimeout(5_000) {
            val page = ReaderPage(0, "/c").apply { status = Page.State.Error(Exception("old")) }
            val payload = ChapterContent(html = "winner", noCache = true)
            val loads = AtomicInteger()
            val loader = object : PageLoader() {
                override var isLocal = true
                override suspend fun getPages() = emptyList<ReaderPage>()
                override suspend fun loadPage(page: ReaderPage) {
                    loads.incrementAndGet()
                }
                override fun retryPage(page: ReaderPage) {
                    page.chapterContent = payload
                    page.status = Page.State.Ready
                }
            }
            assertTrue(NovelPageLoader.awaitPageText("test", page, loader, 3_000, this@withTimeout))
            assertEquals(0, loads.get())
            assertSame(payload, page.chapterContent)
        }
    }

    @Test
    fun `error without loader retry is claimed and fetched again`() = runBlocking {
        val page = ReaderPage(0, "/c").apply { status = Page.State.Error(Exception("old")) }
        val loads = AtomicInteger()
        val loader = object : PageLoader() {
            override var isLocal = true
            override suspend fun getPages() = emptyList<ReaderPage>()
            override suspend fun loadPage(page: ReaderPage) {
                loads.incrementAndGet()
                page.chapterContent = ChapterContent(html = "retried")
                page.status = Page.State.Ready
            }
        }
        assertTrue(NovelPageLoader.awaitPageText("test", page, loader, 3_000, this))
        assertEquals(1, loads.get())
    }
}
