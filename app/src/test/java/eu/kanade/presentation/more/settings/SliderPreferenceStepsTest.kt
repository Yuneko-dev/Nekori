package eu.kanade.presentation.more.settings

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SliderPreferenceStepsTest {

    /** Every step-1 range the settings screens pass today. The default must not move for any of them. */
    private val stepOneRanges = listOf(
        0..5, 0..9, 0..10, 1..10, 0..20, 1..15, 1..20, 2..20, 1..30, 0..30,
        1..50, 0..50, 10..30, 10..40, 50..100, 0..100, 1..100, 0..120,
        0..300, 1..500, 0..2000, 0..10000, 0..120000,
    )

    private val steppedRanges = listOf(
        30..300 step 5,
        0..10_000 step 100,
        300..10_000 step 100,
        50..10000 step 500,
    )

    @Test
    fun `the default still matches the old formula for every step-1 range`() {
        stepOneRanges.forEach { range ->
            slider(range).steps shouldBe (range.last - range.first) - 1
        }
    }

    @Test
    fun `a stepped range gets one stop per value, not one per unit`() {
        slider(30..300 step 5).steps shouldBe 53 // 55 values
        slider(0..10_000 step 100).steps shouldBe 99 // 101 values
        slider(300..10_000 step 100).steps shouldBe 96 // 98 values
        slider(50..10000 step 500).steps shouldBe 18 // was hardcoded in SettingsAdvancedScreen
    }

    @Test
    fun `stops land exactly on the progression's values`() {
        (stepOneRanges + steppedRanges).forEach { range ->
            val stops = slider(range).steps + 1
            (range.last - range.first) % stops shouldBe 0
            (range.last - range.first) / stops shouldBe range.step
        }
    }

    private fun slider(range: IntProgression) = Preference.PreferenceItem.SliderPreference(
        value = range.first,
        title = "title",
        valueRange = range,
    )
}
