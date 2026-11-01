@file:Suppress("ktlint:standard:filename")

package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.source.model.ChapterContent
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.NovelPageLoader
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class HttpPageLoaderCancellationTest {
    @Test
    fun `recycling worker terminates waiting page with error instead of queue`() = runBlocking {
        withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            val chapter = ReaderChapter(Chapter.create().copy(id = 1, name = "c", url = "/c"))
            val source = mockk<HttpSource> {
                every { isNovelSource } returns true
                coEvery { fetchChapterContent(any()) } coAnswers {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
            val loader = HttpPageLoader(chapter, source, mockk(relaxed = true))
            val page = ReaderPage(0, "/c").apply { this.chapter = chapter }
            val waiting = async { NovelPageLoader.awaitPageText("test", page, loader, 3_000, this@withTimeout) }
            entered.await()
            loader.recycle()
            assertFalse(waiting.await())
            assertTrue(page.status is Page.State.Error)
        }
    }

    @Test
    fun `shared helper leaves a failed page retry with its owning http queue`() = runBlocking {
        withTimeout(5_000) {
            val chapter = ReaderChapter(Chapter.create().copy(id = 1, name = "c", url = "/c"))
            val requests = AtomicInteger()
            val source = mockk<HttpSource> {
                every { isNovelSource } returns true
                coEvery { fetchChapterContent(any()) } coAnswers {
                    if (requests.incrementAndGet() == 1) throw IOException("network failure")
                    ChapterContent(html = "Recovered")
                }
            }
            val loader = HttpPageLoader(chapter, source, mockk(relaxed = true))
            try {
                val page = ReaderPage(0, "/c").apply { this.chapter = chapter }
                assertFalse(NovelPageLoader.awaitPageText("test", page, loader, 1_000, this@withTimeout))
                assertTrue(page.status is Page.State.Error)
                assertTrue(NovelPageLoader.awaitPageText("test", page, loader, 1_000, this@withTimeout))
                assertEquals("Recovered", page.text)
                assertEquals(2, requests.get())
            } finally {
                loader.recycle()
            }
        }
    }
}
