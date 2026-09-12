package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.TtsController
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NovelWebViewTtsSessionTest {

    @Test
    fun `chapter handoff remains active after the speech queue stops`() {
        val controller = mockk<TtsController>(relaxed = true)
        val viewer = mockk<NovelWebViewViewer>()
        every { viewer.isTtsActive() } answers { callOriginal() }
        every { viewer["currentTtsState"]() } answers { callOriginal() }
        viewer.setField("ttsController", controller)

        assertFalse(viewer.isTtsActive())
        viewer.setField("pendingTtsAutoStartOnLoad", true)
        assertTrue(viewer.isTtsActive(), "Keep the media service alive while the next document loads")
        viewer.setField("pendingTtsAutoStartOnLoad", false)
        every { controller.isTtsAutoPlay } returns true
        assertTrue(viewer.isTtsActive())
        every { controller.isTtsAutoPlay } returns false
        assertFalse(viewer.isTtsActive())
        every { controller.isPaused() } returns true
        assertTrue(viewer.isTtsActive())
    }

    @Test
    fun `viewer playback events immediately update notification and UI state`() {
        val activity = mockk<ReaderActivity>(relaxed = true)
        val controller = mockk<TtsController>(relaxed = true)
        val viewer = mockk<NovelWebViewViewer>()
        val state = MutableStateFlow(NovelWebViewViewer.TtsPlaybackState.STOPPED)
        every { viewer["currentTtsState"]() } answers { callOriginal() }
        every { viewer["dispatchTtsState"]() } answers { callOriginal() }
        every { viewer["dispatchTsundokuEvent"](any<String>(), any<String>(), any<String>()) } returns Unit
        viewer.setField("activity", activity)
        viewer.setField("ttsController", controller)
        viewer.setField("_ttsPlaybackState", state)

        every { controller.isStarting() } returns true
        val dispatch = NovelWebViewViewer::class.java.getDeclaredMethod("dispatchTtsState")
            .apply { isAccessible = true }
        dispatch.invoke(viewer)
        assertEquals(NovelWebViewViewer.TtsPlaybackState.PLAYING, state.value)
        verify(exactly = 1) { activity.onNovelTtsStateChanged() }

        every { controller.isStarting() } returns false
        dispatch.invoke(viewer)
        assertEquals(NovelWebViewViewer.TtsPlaybackState.STOPPED, state.value)
        verify(exactly = 2) { activity.onNovelTtsStateChanged() }
    }

    private fun NovelWebViewViewer.setField(name: String, value: Any) {
        NovelWebViewViewer::class.java.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
    }
}
