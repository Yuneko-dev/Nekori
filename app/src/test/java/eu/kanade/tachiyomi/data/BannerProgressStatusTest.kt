package eu.kanade.tachiyomi.data

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class BannerProgressStatusTest {

    @Test
    fun `brief work stays hidden`() = runTest {
        val status = BannerProgressStatus()
        val runningStates = mutableListOf<Boolean>()
        backgroundScope.launch { status.isRunning.toList(runningStates) }
        runCurrent()

        status.start()
        advanceTimeBy(500)
        status.stop()
        advanceTimeBy(1_000)
        runCurrent()

        runningStates.shouldContainExactly(false)
    }

    @Test
    fun `overlapping work stays visible until every run stops`() = runTest {
        val status = BannerProgressStatus(visibilityDebounceMillis = 0)
        val runningStates = mutableListOf<Boolean>()
        backgroundScope.launch { status.isRunning.toList(runningStates) }
        runCurrent()

        status.start()
        status.start()
        runCurrent()
        status.stop()
        runCurrent()
        status.stop()
        runCurrent()

        runningStates.shouldContainExactly(false, true, false)
    }

    @Test
    fun `progress is bounded and reset for a new run`() {
        val status = BannerProgressStatus()

        status.updateProgress(current = 150, total = 100)
        status.progress.value shouldBe 1f

        status.start()
        status.progress.value shouldBe null

        status.updateProgress(current = 1, total = 0)
        status.progress.value shouldBe null
    }
}
