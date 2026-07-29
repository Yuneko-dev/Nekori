package eu.kanade.tachiyomi.ui.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NovelFindInPageStateTest {

    @Test
    fun `changing query clears stale matches while native search is pending`() {
        val state = NovelFindInPageState()
            .withQuery("first")
            .withResult(activeMatchOrdinal = 2, numberOfMatches = 7, isDoneCounting = true)
            .withQuery("second")

        assertEquals("second", state.query)
        assertEquals("", state.statusText)
        assertFalse(state.hasMatches)
        assertFalse(state.isNoMatch)
    }

    @Test
    fun `native zero based ordinal is displayed as one based`() {
        val state = NovelFindInPageState()
            .withQuery("novel")
            .withResult(activeMatchOrdinal = 2, numberOfMatches = 7, isDoneCounting = true)

        assertEquals("3/7", state.statusText)
        assertTrue(state.hasMatches)
        assertFalse(state.isNoMatch)
    }

    @Test
    fun `intermediate callback with matches displays latest count`() {
        val state = NovelFindInPageState()
            .withQuery("novel")
            .withResult(activeMatchOrdinal = 0, numberOfMatches = 3, isDoneCounting = false)

        assertEquals("1/3", state.statusText)
        assertTrue(state.hasMatches)
    }

    @Test
    fun `intermediate callback without matches keeps status blank`() {
        val state = NovelFindInPageState()
            .withQuery("missing")
            .withResult(activeMatchOrdinal = 0, numberOfMatches = 0, isDoneCounting = false)

        assertEquals("", state.statusText)
        assertFalse(state.isNoMatch)
    }

    @Test
    fun `finished search without matches displays zero of zero`() {
        val state = NovelFindInPageState()
            .withQuery("missing")
            .withResult(activeMatchOrdinal = 0, numberOfMatches = 0, isDoneCounting = true)

        assertEquals("0/0", state.statusText)
        assertFalse(state.hasMatches)
        assertTrue(state.isNoMatch)
    }

    @Test
    fun `empty query has no status or navigation`() {
        val state = NovelFindInPageState()

        assertEquals("", state.statusText)
        assertFalse(state.hasMatches)
        assertFalse(state.isNoMatch)
    }

    @Test
    fun `late native result cannot reenable navigation after query is cleared`() {
        val state = NovelFindInPageState()
            .withQuery("novel")
            .withQuery("")
            .withResult(activeMatchOrdinal = 1, numberOfMatches = 4, isDoneCounting = true)

        assertEquals("", state.statusText)
        assertFalse(state.hasMatches)
        assertFalse(state.isNoMatch)
    }
}
