package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NovelWebViewTapPolicyTest {

    private fun tap(target: ReaderGestureTarget, isVideoChapter: Boolean = false) =
        target.tapAction(isVideoChapter = isVideoChapter)

    @Test
    fun `interactive and unclaimed targets do nothing`() {
        assertEquals(ReaderTapAction.NONE, tap(ReaderGestureTarget.BLOCKED))
        assertEquals(ReaderTapAction.NONE, tap(ReaderGestureTarget.BLOCKED, isVideoChapter = true))
    }

    @Test
    fun `image taps bypass tap zones and toggle the reader menu`() {
        assertEquals(ReaderTapAction.TOGGLE_MENU, tap(ReaderGestureTarget.IMAGE))
    }

    // Regression: gating this on a second preference shadowed navigationModeNovel, so every tap
    // zone toggled the menu instead of turning the page.
    @Test
    fun `reader surface reaches the tap zones, which navigationModeNovel alone governs`() {
        assertEquals(ReaderTapAction.TAP_ZONES, tap(ReaderGestureTarget.SURFACE))
        assertEquals(ReaderTapAction.TOGGLE_MENU, tap(ReaderGestureTarget.SURFACE, isVideoChapter = true))
    }

    @Test
    fun `only the reader surface may become a chapter swipe`() {
        assertTrue(ReaderGestureTarget.SURFACE.allowsChapterSwipe())
        assertFalse(ReaderGestureTarget.IMAGE.allowsChapterSwipe())
        assertFalse(ReaderGestureTarget.BLOCKED.allowsChapterSwipe())
    }

    @Test
    fun `unknown and missing claims fail closed`() {
        assertEquals(ReaderGestureTarget.SURFACE, ReaderGestureTarget.fromWire("surface"))
        assertEquals(ReaderGestureTarget.IMAGE, ReaderGestureTarget.fromWire("image"))
        assertEquals(ReaderGestureTarget.BLOCKED, ReaderGestureTarget.fromWire("blocked"))
        assertEquals(ReaderGestureTarget.BLOCKED, ReaderGestureTarget.fromWire("SURFACE"))
        assertEquals(ReaderGestureTarget.BLOCKED, ReaderGestureTarget.fromWire(""))
        assertEquals(ReaderGestureTarget.BLOCKED, ReaderGestureTarget.fromWire(null))
    }
}
