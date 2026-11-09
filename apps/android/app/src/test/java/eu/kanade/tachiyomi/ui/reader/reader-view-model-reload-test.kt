@file:Suppress("ktlint:standard:filename")

package eu.kanade.tachiyomi.ui.reader

import eu.kanade.tachiyomi.source.model.Page
import io.mockk.coEvery
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class ReaderViewModelReloadTest {
    @Test
    fun `reload is rejected before a current chapter exists`() = runTest {
        ReaderViewModelTestFixture(testScheduler).use { fixture ->
            assertEquals(false, fixture.model.reloadChapter() as Any?)
            verify(exactly = 0) { fixture.viewer.setChapters(any()) }
        }
    }

    @Test
    fun `reload is rejected when no chapter loader is available`() = runTest {
        ReaderViewModelTestFixture(testScheduler).use { fixture ->
            fixture.installChapter(withLoader = false)
            runCurrent()
            assertEquals(false, fixture.model.reloadChapter() as Any?)
            verify(exactly = 0) { fixture.viewer.setChapters(any()) }
        }
    }

    @Test
    fun `a second reload cancels and joins the previous load before replacing pages`() = runTest {
        ReaderViewModelTestFixture(testScheduler).use { fixture ->
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val releaseCleanup = CompletableDeferred<Unit>()
            val secondStarted = CompletableDeferred<Unit>()
            val published = CompletableDeferred<String?>()
            val calls = AtomicInteger()
            fixture.installChapter()
            runCurrent()
            coEvery { fixture.source.getPageList(any()) } coAnswers {
                if (calls.incrementAndGet() == 1) {
                    started.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                        withContext(NonCancellable) { releaseCleanup.await() }
                    }
                } else {
                    secondStarted.complete(Unit)
                    listOf(Page(0, "/one").apply { text = "fresh" })
                }
            }
            every { fixture.viewer.setChapters(any()) } answers {
                published.complete(
                    firstArg<eu.kanade.tachiyomi.ui.reader.model.ViewerChapters>()
                        .currChapter.pages?.single()?.text,
                )
            }
            try {
                fixture.model.reloadChapter()
                fixture.awaitIo(started)
                fixture.model.reloadChapter(fromSource = true)
                fixture.awaitIo(cancelled)
                assertFalse(secondStarted.isCompleted, "Second load must wait for previous resource cleanup")
                releaseCleanup.complete(Unit)
                fixture.awaitIo(secondStarted)
                // Commit uses Dispatchers.Main, so advance only after the IO operation has started.
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
                while (!published.isCompleted) {
                    check(System.nanoTime() < deadline) { "Reload did not publish fresh pages" }
                    runCurrent()
                    withContext(kotlinx.coroutines.Dispatchers.Default) { Thread.yield() }
                }
                assertEquals("fresh", published.await())
                assertEquals(2, calls.get())
                verify(exactly = 1) { fixture.viewer.setChapters(any()) }
                val automatic = withContext(kotlinx.coroutines.Dispatchers.Default) {
                    kotlinx.coroutines.withTimeout(10_000) { fixture.model.beginAutomaticChapterNavigation() }
                }
                assertTrue(automatic.id > 0, "Successful reload must release the manual navigation barrier")
                fixture.model.finishChapterNavigation(automatic)
            } finally {
                releaseCleanup.complete(Unit)
            }
        }
    }
}
