package eu.kanade.tachiyomi.ui.reader

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ChapterPrefetchTest {

    @Test
    fun `asking again for the chapter already running does not start a second run`() = runTest {
        val started = mutableListOf<Long>()
        val prefetch = ChapterPrefetch(backgroundScope)
        val never = CompletableDeferred<Unit>()

        repeat(3) {
            prefetch.start(7L) {
                started += 7L
                never.await()
            }
        }
        runCurrent()

        started shouldContainExactly listOf(7L)
    }

    @Test
    fun `re-targeting cancels the run in flight`() = runTest {
        var firstCancelled = false
        val prefetch = ChapterPrefetch(backgroundScope)
        val never = CompletableDeferred<Unit>()

        prefetch.start(1L) {
            try {
                never.await()
            } finally {
                firstCancelled = true
            }
        }
        runCurrent()
        prefetch.start(2L) {}
        runCurrent()

        firstCancelled shouldBe true
    }

    @Test
    fun `a finished run does not block a later request for the same chapter`() = runTest {
        var runs = 0
        val prefetch = ChapterPrefetch(backgroundScope)

        prefetch.start(5L) { runs++ }
        runCurrent()
        prefetch.start(5L) { runs++ }
        runCurrent()

        runs shouldBe 2
    }

    @Test
    fun `cancel stops the run and clears the target`() = runTest {
        var cancelled = false
        var reruns = 0
        val prefetch = ChapterPrefetch(backgroundScope)
        val never = CompletableDeferred<Unit>()

        prefetch.start(3L) {
            try {
                never.await()
            } finally {
                cancelled = true
            }
        }
        runCurrent()
        prefetch.cancel()
        runCurrent()
        // The target is cleared too, so the same chapter can be asked for again.
        prefetch.start(3L) { reruns++ }
        runCurrent()

        cancelled shouldBe true
        reruns shouldBe 1
    }
}
