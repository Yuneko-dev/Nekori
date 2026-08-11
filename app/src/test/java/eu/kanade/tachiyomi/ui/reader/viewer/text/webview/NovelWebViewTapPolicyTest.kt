package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NovelWebViewTapPolicyTest {

    private fun tap(
        target: ReaderGestureTarget,
        isVideoChapter: Boolean = false,
        tapToScroll: Boolean = true,
    ) = target.tapAction(isVideoChapter = isVideoChapter, tapToScroll = tapToScroll)

    @Test
    fun `interactive and unclaimed targets do nothing`() {
        assertEquals(ReaderTapAction.NONE, tap(ReaderGestureTarget.BLOCKED))
        assertEquals(ReaderTapAction.NONE, tap(ReaderGestureTarget.BLOCKED, tapToScroll = false))
        assertEquals(ReaderTapAction.NONE, tap(ReaderGestureTarget.BLOCKED, isVideoChapter = true))
    }

    @Test
    fun `image taps bypass tap zones and toggle the reader menu`() {
        assertEquals(ReaderTapAction.TOGGLE_MENU, tap(ReaderGestureTarget.IMAGE))
    }

    @Test
    fun `reader surface uses tap zones only when tap-to-scroll is on and prose is scrollable`() {
        assertEquals(ReaderTapAction.TAP_ZONES, tap(ReaderGestureTarget.SURFACE))
        assertEquals(ReaderTapAction.TOGGLE_MENU, tap(ReaderGestureTarget.SURFACE, tapToScroll = false))
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
