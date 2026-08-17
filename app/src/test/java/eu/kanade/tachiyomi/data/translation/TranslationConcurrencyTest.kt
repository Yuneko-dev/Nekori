package eu.kanade.tachiyomi.data.translation

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class TranslationConcurrencyTest {
    @Test
    fun `request tracker only commits the newest generation`() {
        val tracker = TranslationRequestTracker()

        val first = tracker.begin()
        val second = tracker.begin()

        tracker.canCommit(first) shouldBe false
        tracker.canCommit(second) shouldBe true

        tracker.invalidate()
        tracker.canCommit(second) shouldBe false
    }

    @Test
    fun `pending titles ignore duplicates until cleared`() {
        val pending = PendingTitleTranslations()

        pending.add(7) shouldBe true
        pending.add(7) shouldBe false

        pending.clear()
        pending.add(7) shouldBe true
    }

    @Test
    fun `never runs more values than the permit count allows`() = runTest {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val finished = mutableListOf<Int>()

        val failure = parallelCatchingFirstFailure((0 until 6).toList(), maxParallel = 2) { index, _ ->
            peak.updateAndGet(active.incrementAndGet()::coerceAtLeast)
            delay(100)
            active.decrementAndGet()
            finished += index
        }

        failure shouldBe null
        peak.get() shouldBe 2
        finished.sorted() shouldContainExactly (0 until 6).toList()
    }

    @Test
    fun `stops starting values once one has failed`() = runTest {
        val started = mutableListOf<Int>()

        val failure = parallelCatchingFirstFailure((0 until 6).toList(), maxParallel = 1) { index, _ ->
            started += index
            delay(10)
            if (index == 2) error("chunk 3 failed")
        }

        // Values 3..5 never begin: work past the failure cannot be resumed anyway.
        started shouldContainExactly listOf(0, 1, 2)
        failure?.index shouldBe 2
        failure?.cause?.message shouldBe "chunk 3 failed"
    }

    @Test
    fun `reports the lowest failed index, not the last one to fail`() = runTest {
        // Index 0 fails first in time and index 1 last, so keeping the newest failure would report 1.
        val failure = parallelCatchingFirstFailure(listOf(100L, 300L, 200L), maxParallel = 3) { index, wait ->
            delay(wait)
            error("value $index failed")
        }

        failure?.index shouldBe 0
        failure?.cause?.message shouldBe "value 0 failed"
    }

    @Test
    fun `only the chunks before the first gap can be resumed`() {
        val chunks = listOf(listOf("one"), null, listOf("three"))

        contiguousTranslationPrefix(chunks) shouldContainExactly listOf("one")
        contiguousTranslationPrefix(listOf(null, listOf("x"))).shouldContainExactly(emptyList())
        contiguousTranslationPrefix(listOf(listOf("a"), listOf("b"))) shouldContainExactly listOf("a", "b")
    }
}
