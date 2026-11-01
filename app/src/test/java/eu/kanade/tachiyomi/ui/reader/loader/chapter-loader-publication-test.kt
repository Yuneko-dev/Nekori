@file:Suppress("ktlint:standard:filename")

package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ChapterLoaderPublicationTest {
    private class Successor : PageLoader() {
        override var isLocal = true
        override suspend fun getPages(): List<ReaderPage> = emptyList()
    }

    private class Fixture {
        val source = mockk<CatalogueSource>(relaxed = true)
        val downloads = mockk<DownloadManager>(relaxed = true)
        val chapter = ReaderChapter(Chapter.create().copy(id = 1, name = "One", url = "/one"))
        val loader: ChapterLoader
        init {
            every { source.isNovelSource } returns true
            loader = ChapterLoader(
                mockk(relaxed = true),
                downloads,
                mockk(relaxed = true),
                Manga.create().copy(id = 2, source = 3, title = "Novel"),
                source,
                mockk(),
                mockk(),
            )
            chapter.ref()
        }
        fun installSuccessor(): ReaderChapter.State {
            chapter.pageLoader = Successor()
            return ReaderChapter.State.Loaded(listOf(ReaderPage(0, text = "fresh"))).also { chapter.state = it }
        }
    }

    @Test
    fun `final unref while real source page list is loading keeps chapter waiting`() = runBlocking {
        val fixture = Fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { fixture.source.getPageList(any()) } coAnswers {
            entered.complete(Unit)
            release.await()
            listOf(Page(0, "/one"))
        }
        val job = async { runCatching { fixture.loader.loadChapter(fixture.chapter) } }
        try {
            withTimeout(10_000) { entered.await() }
            val old = fixture.chapter.pageLoader!!
            fixture.chapter.unref()
            assertTrue(!old.isRecycled)
            release.complete(Unit)
            assertTrue(job.await().exceptionOrNull() is CancellationException)
            assertSame(ReaderChapter.State.Wait, fixture.chapter.state)
            assertNull(fixture.chapter.pageLoader)
            assertTrue(old.isRecycled)
        } finally {
            release.complete(Unit)
            job.await()
        }
    }

    @Test
    fun `late success and failure from a replaced loader never overwrite fresh state`() = runBlocking {
        for (fail in listOf(false, true)) {
            val fixture = Fixture()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            coEvery { fixture.source.getPageList(any()) } coAnswers {
                entered.complete(Unit)
                release.await()
                if (fail) throw IOException("old failure")
                listOf(Page(0, "/one"))
            }
            val job = async { runCatching { fixture.loader.loadChapter(fixture.chapter) } }
            try {
                withTimeout(10_000) { entered.await() }
                val fresh = fixture.installSuccessor()
                val successor = fixture.chapter.pageLoader
                release.complete(Unit)
                assertTrue(job.await().exceptionOrNull() is CancellationException)
                assertSame(fresh, fixture.chapter.state)
                assertSame(successor, fixture.chapter.pageLoader)
            } finally {
                release.complete(Unit)
                job.await()
                fixture.chapter.unref()
            }
        }
    }

    @Test
    fun `final unref during loader factory lookup rejects late loader attachment`() = runBlocking {
        val fixture = Fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        every { fixture.downloads.isChapterDownloaded(any(), any(), any(), any(), any(), any()) } answers {
            entered.complete(Unit)
            check(release.await(10, TimeUnit.SECONDS))
            false
        }
        val job = async(Dispatchers.Default) { runCatching { fixture.loader.loadChapter(fixture.chapter) } }
        try {
            withTimeout(10_000) { entered.await() }
            fixture.chapter.unref()
            release.countDown()
            assertTrue(job.await().exceptionOrNull() is CancellationException)
            assertSame(ReaderChapter.State.Wait, fixture.chapter.state)
            assertNull(fixture.chapter.pageLoader)
        } finally {
            release.countDown()
            job.await()
        }
    }

    @Test
    fun `cancellation resets Wait and active source errors remain Error`() = runBlocking {
        for (cancel in listOf(false, true)) {
            val fixture = Fixture()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            coEvery { fixture.source.getPageList(any()) } coAnswers {
                entered.complete(Unit)
                release.await()
                throw IOException("source failure")
            }
            val job = launch { runCatching { fixture.loader.loadChapter(fixture.chapter) } }
            try {
                withTimeout(10_000) { entered.await() }
                if (cancel) {
                    job.cancelAndJoin()
                } else {
                    release.complete(Unit)
                    job.join()
                }
                if (cancel) {
                    assertSame(ReaderChapter.State.Wait, fixture.chapter.state)
                } else {
                    assertTrue(fixture.chapter.state is ReaderChapter.State.Error)
                }
            } finally {
                release.complete(Unit)
                job.cancelAndJoin()
                fixture.chapter.unref()
            }
        }
    }

    @Test
    fun `an already cancelled caller cannot leave the chapter loading before IO dispatch`() = runBlocking {
        val fixture = Fixture()
        try {
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel()
                runCatching { fixture.loader.loadChapter(fixture.chapter) }
            }
            job.join()
            assertSame(ReaderChapter.State.Wait, fixture.chapter.state)
            assertNull(fixture.chapter.pageLoader)
        } finally {
            fixture.chapter.unref()
        }
    }

    @Test
    fun `source returning pages after swallowing cancellation cannot publish loaded state`() = runBlocking {
        val fixture = Fixture()
        val entered = CompletableDeferred<Unit>()
        coEvery { fixture.source.getPageList(any()) } coAnswers {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } catch (_: CancellationException) {
                listOf(Page(0, "/late-page"))
            }
        }
        val job = launch { runCatching { fixture.loader.loadChapter(fixture.chapter) } }
        try {
            withTimeout(10_000) { entered.await() }
            job.cancelAndJoin()
            assertSame(ReaderChapter.State.Wait, fixture.chapter.state)
        } finally {
            job.cancelAndJoin()
            fixture.chapter.unref()
        }
    }
}
