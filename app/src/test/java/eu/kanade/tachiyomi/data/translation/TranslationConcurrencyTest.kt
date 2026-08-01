package eu.kanade.tachiyomi.data.translation

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TranslationConcurrencyTest {
    @Test
    fun `late translation result cannot commit after a newer request`() {
        val tracker = TranslationRequestTracker()
        val old = tracker.begin()
        val current = tracker.begin()

        tracker.canCommit(old) shouldBe false
        tracker.canCommit(current) shouldBe true
    }

    @Test
    fun `pending title ids dedupe and clear on cancellation`() {
        val pending = PendingTitleTranslations()

        pending.add(7) shouldBe true
        pending.add(7) shouldBe false
        pending.clear()
        pending.add(7) shouldBe true
    }
}
