package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.ChapterQueue
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.TtsController
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.TtsHandoffState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.service.TranslationPreferences

class NovelWebViewTtsSessionTest {

    @Test
    fun `TTS read ahead translates before handoff only when translation preload is enabled`() = runTest {
        for (translateAhead in listOf(true, false)) {
            val activity = mockk<ReaderActivity>(relaxed = true)
            val viewModel = mockk<ReaderViewModel>(relaxed = true)
            every { activity.viewModel } returns viewModel
            every { activity.isTranslationEnabled() } returns true
            val preferences = mockk<ReaderPreferences> {
                every { novelAutoLoadNextChapterAt.get() } returns 0
            }
            val translations = mockk<TranslationPreferences> {
                every { autoTranslateNextChapter().get() } returns translateAhead
            }
            val controller = mockk<TtsController>(relaxed = true) {
                every { isTtsAutoPlay } returns true
                every { ttsChunks } returns listOf("Chapter ten")
            }
            val anchor = ReaderChapter(mockk<Chapter> { every { id } returns 10L })
            val next = ReaderChapter(mockk<Chapter> { every { id } returns 11L })
            val page = ReaderPage(0, text = "<p>Chapter eleven</p>").apply { chapter = next }
            next.state = ReaderChapter.State.Loaded(listOf(page))
            next.pageLoader = mockk()
            coEvery { viewModel.prepareNextChapterForInfiniteScroll(anchor) } returns next

            val viewer = mockk<NovelWebViewViewer>(relaxed = true)
            every { viewer.activity } returns activity
            every { viewer["preFetchNextChapterForTts"](any<Boolean>()) } answers { callOriginal() }
            every { viewer.isInfiniteScrollEnabled() } returns true
            every { viewer getProperty "webChapterContentReady" } answers { callOriginal() }
            every { viewer getProperty "loadedChapters" } answers { callOriginal() }
            every { viewer getProperty "preferences" } returns preferences
            every { viewer getProperty "translationPreferences" } returns translations
            coEvery { viewer["awaitPageText"](any<ReaderPage>(), any<PageLoader>(), any<Long>()) } returns true
            val queue = ChapterQueue<ReaderChapter> { it.chapter.id }.apply { reset(anchor) }
            val handoff = TtsHandoffState<Pair<ReaderChapter, ReaderPage>>(backgroundScope)
            viewer.setField("activity", activity)
            viewer.setField("ttsController", controller)
            viewer.setField("chapterQueue", queue)
            viewer.setField("handoffState", handoff)
            val ready = NovelWebViewViewer::class.java.declaredClasses.single { it.simpleName == "DocState" }
                .enumConstants!!.single { it.toString() == "READY" }
            viewer.setField("docState", ready)

            val prefetch = NovelWebViewViewer::class.java
                .getDeclaredMethod("preFetchNextChapterForTts", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }
            repeat(3) { prefetch.invoke(viewer, false) }

            val prepared = handoff.take(10)
            coVerify(exactly = 1) { viewModel.prepareNextChapterForInfiniteScroll(anchor) }
            coVerify(exactly = 1) { viewer["awaitPageText"](page, next.pageLoader!!, 30_000L) }
            coVerify(exactly = if (translateAhead) 1 else 0) { viewModel.translateChapterAhead(next, 11L) }
            assertEquals(next to page, prepared)
            assertEquals(anchor, queue.current(), "Preparing the next chapter must not select it")
            assertEquals(1, queue.size, "Preparation must not append the next chapter")
        }
    }

    @Test
    fun `translation reload restores the displayed chapter queue without requesting a loading screen`() = runTest {
        val activity = mockk<ReaderActivity>(relaxed = true)
        val displayed = mockk<ReaderChapter>(relaxed = true)
        val page = ReaderPage(0, text = "<p>Chapter ten</p>").apply { chapter = displayed }
        val viewer = mockk<NovelWebViewViewer>(relaxed = true)
        every { viewer.reloadWithTranslation() } answers { callOriginal() }
        every { viewer["displayContent"](displayed, page, false) } returns Unit
        viewer.setField("activity", activity)
        viewer.setField("scope", backgroundScope)
        viewer.setField("currentPage", page)
        viewer.setField("currentChapters", mockk<ViewerChapters>(relaxed = true))

        viewer.reloadWithTranslation()

        verify(exactly = 1) { viewer["displayContent"](displayed, page, false) }
    }

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
