package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TtsHandoffStateTest {

    @Test
    fun `handoff waits for slow translation without starting a second request`() = runTest {
        val state = TtsHandoffState<String>(backgroundScope)
        var requests = 0
        repeat(3) {
            state.prefetch(10, 0f, 0f) {
                requests++
                delay(60_000)
                "translated chapter 11"
            }
        }
        val handoff = async { state.take(10) }
        advanceTimeBy(35_000)
        assertFalse(handoff.isCompleted)
        assertEquals(1, requests)
        advanceTimeBy(25_000)
        runCurrent()
        assertEquals("translated chapter 11", handoff.await())
        assertNull(state.take(10))
    }

    @Test
    fun `native progress respects threshold and can prepare successive chapters`() = runTest {
        val state = TtsHandoffState<Long>(backgroundScope)
        var requests = 0
        state.prefetch(10, 0.5f, 0.95f) {
            requests++
            11L
        }
        runCurrent()
        assertEquals(0, requests)
        state.prefetch(10, 0.95f, 0.95f) {
            requests++
            11L
        }
        assertEquals(11L, state.take(10))
        state.prefetch(11, 0f, 0f) {
            requests++
            12L
        }
        assertEquals(12L, state.take(11))
        assertEquals(2, requests)
    }

    @Test
    fun `stop cancels pending work and clears a completed result`() = runTest {
        val state = TtsHandoffState<String>(backgroundScope)
        var cancelled = false
        state.prefetch(10, 0f, 0f) {
            try {
                CompletableDeferred<Unit>().await()
                "unreachable"
            } finally {
                cancelled = true
            }
        }
        runCurrent()
        state.cancel()
        runCurrent()
        assertTrue(cancelled)
        assertNull(state.take(10))
        state.prefetch(10, 0f, 0f) { "ready" }
        runCurrent()
        state.cancel()
        assertNull(state.take(10))
    }

    @Test
    fun `navigation cannot consume preparation from a different chapter`() = runTest {
        val state = TtsHandoffState<String>(backgroundScope)
        state.prefetch(10, 0f, 0f) { "chapter 11" }
        runCurrent()
        assertNull(state.take(20))
        state.prefetch(20, 0f, 0f) { "chapter 21" }
        assertEquals("chapter 21", state.take(20))
    }

    @Test
    fun `cancelling a waiting handoff cancels its provider request`() = runTest {
        val state = TtsHandoffState<String>(backgroundScope)
        var cancelled = false
        state.prefetch(10, 0f, 0f) {
            try {
                CompletableDeferred<String>().await()
            } finally {
                cancelled = true
            }
        }
        val handoff = async { state.take(10) }
        runCurrent()
        handoff.cancel()
        runCurrent()
        assertTrue(cancelled)
    }
}
