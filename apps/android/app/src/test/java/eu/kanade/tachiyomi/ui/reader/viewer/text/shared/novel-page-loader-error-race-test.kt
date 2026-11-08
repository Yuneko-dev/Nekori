@file:Suppress("ktlint:standard:filename")

package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.ChapterContent
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.loader.LocalNovelPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NovelPageLoaderErrorRaceTest {
    @Test
    fun `helper losing the same error claim recovers after the winner is cancelled`() = runBlocking {
        withTimeout(8_000) {
            val snapshots = CyclicBarrier(2)
            val reads = AtomicInteger()
            val calls = AtomicInteger()
            val ownerName = CompletableDeferred<String>()
            val source = mockk<Source> {
                every { isNovelSource } returns true
                coEvery { fetchChapterContent(any()) } coAnswers {
                    if (calls.incrementAndGet() == 1) {
                        ownerName.complete(currentCoroutineContext()[CoroutineName]!!.name)
                        awaitCancellation()
                    }
                    ChapterContent(html = "recovered")
                }
            }
            val chapter = ReaderChapter(Chapter.create().copy(id = 1, name = "c", url = "/c"))
            val loader = LocalNovelPageLoader(chapter, source)
            val page = spyk(ReaderPage(0, "/c"))
            page.status = Page.State.Error(Exception("previous failure"))
            every { page.status } answers {
                callOriginal().also {
                    // Both helpers see the same Error before either can claim it.
                    if (reads.incrementAndGet() <= 2) snapshots.await(3, TimeUnit.SECONDS)
                }
            }
            val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("first"))
            val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("second"))
            try {
                val first = async(Dispatchers.Default) {
                    NovelPageLoader.awaitPageText("first", page, loader, 2_000, firstScope)
                }
                val second = async(Dispatchers.Default) {
                    NovelPageLoader.awaitPageText("second", page, loader, 2_000, secondScope)
                }
                if (ownerName.await() == "first") {
                    first.cancelAndJoin()
                    assertTrue(second.await())
                } else {
                    second.cancelAndJoin()
                    assertTrue(first.await())
                }
                assertEquals(2, calls.get())
                assertEquals(Page.State.Ready, page.status)
            } finally {
                firstScope.cancel()
                secondScope.cancel()
            }
        }
    }
}
