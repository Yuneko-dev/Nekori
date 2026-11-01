@file:Suppress("ktlint:standard:filename")

package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.NovelPagePosition
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.ChapterQueue
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.TtsController
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class SearchEndEvidenceTest {
    @Test
    fun `ordinary TTS completion does not force progress to one hundred`() {
        val fixture = Fixture()
        val model = fixture.activity.viewModel
        every { model.isSearchJumpGuarded(1) } returns false
        fixture.call("onTtsChapterCompleted", 1L)
        verify(exactly = 0) { model.saveNovelProgress(any<ReaderChapter>(), any(), any(), any()) }
    }

    @Test
    fun `guarded TTS completion only saves for the live playback chapter`() {
        val fixture = Fixture()
        val model = fixture.activity.viewModel
        fixture.call("onTtsChapterCompleted", 1L)
        fixture.call("onTtsChapterCompleted", 2L)
        fixture.set("isDestroyed", true)
        fixture.call("onTtsChapterCompleted", 1L)
        verify(exactly = 1) { model.saveNovelProgress(fixture.page.chapter, 100, any(), true) }
    }

    @Test
    fun `initial programmatic last page sample and exit are not completion`() {
        val fixture = Fixture()
        fixture.persist(1f, true)
        fixture.call("flushProgress")
        verify(exactly = 0) { fixture.activity.saveNovelProgress(any(), any(), any(), true) }
        fixture.call("noteReadingInteraction")
        fixture.persist(1f, true)
        verify(exactly = 1) { fixture.activity.saveNovelProgress(fixture.page, 100, any(), true) }
    }

    @Test
    fun `TTS bottom and stop cannot turn a stale sample into completion`() {
        val fixture = Fixture()
        fixture.call("noteReadingInteraction")
        fixture.call("resetReadingEndProof") // The accepted TTS start/resume paths reset this latch.
        every { fixture.tts.isTtsAutoPlay } returns true
        fixture.call("noteReadingInteraction")
        fixture.persist(1f, true)
        every { fixture.tts.isTtsAutoPlay } returns false
        fixture.call("flushProgress")
        fixture.persist(1f, true)
        verify(exactly = 0) { fixture.activity.saveNovelProgress(any(), any(), any(), true) }
    }

    @Test
    fun `initial short chapter schedules append but only a manual next certifies it`() {
        val fixture = Fixture()
        fixture.call("markCurrentChapterShort")
        verify(exactly = 0) { fixture.activity.saveNovelProgress(any(), any(), any(), true) }
        verify(exactly = 1) { fixture.viewer["startNextChapterAppend"]() }
        fixture.viewer.onManualChapterAdvance()
        verify(exactly = 1) { fixture.activity.saveNovelProgress(fixture.page, 100, any(), true) }
    }

    @Test
    fun `video timeupdate one hundred is not the ended event`() {
        val fixture = Fixture()
        every { fixture.viewer["isVideoChapter"]() } returns true
        fixture.call("saveVideoProgress", 100, false)
        verify(exactly = 0) { fixture.activity.saveNovelProgress(any(), any(), any(), true) }
        fixture.call("saveVideoProgress", 100, true)
        verify(exactly = 1) { fixture.activity.saveNovelProgress(fixture.page, 100, 100, true) }
    }

    @Test
    fun `chapter handoff does not inherit a previous chapters touch or short fit`() {
        val fixture = Fixture()
        val second = ReaderPage(0).apply { chapter = ReaderChapter(Chapter.create().copy(id = 2)) }
        val queue = ChapterQueue<ReaderChapter> { it.chapter.id }.apply {
            append(fixture.page.chapter)
            append(second.chapter)
        }
        fixture.set("chapterQueue", queue)
        every { fixture.viewer getProperty "loadedChapters" } answers { callOriginal() }
        every { fixture.viewer getProperty "currentChapterIndex" } answers { callOriginal() }
        every { fixture.viewer setProperty "currentChapterIndex" value any<Int>() } answers { callOriginal() }
        every { fixture.viewer["onChapterVisible"](any<Long>(), any<Float>()) } answers { callOriginal() }
        every { fixture.activity.viewModel.state } returns MutableStateFlow(ReaderViewModel.State())
        fixture.call("markCurrentChapterShort")
        fixture.call("noteReadingInteraction")
        fixture.call("onChapterVisible", 2L, 0f)
        fixture.set("currentPage", second)
        fixture.viewer.onManualChapterAdvance()
        fixture.persist(1f, true)
        verify(exactly = 0) { fixture.activity.saveNovelProgress(any(), any(), any(), true) }
        fixture.call("noteReadingInteraction")
        fixture.persist(1f, true)
        verify(exactly = 1) { fixture.activity.saveNovelProgress(second, 100, any(), true) }
    }

    private class Fixture {
        val activity = mockk<ReaderActivity>(relaxed = true)
        val tts = mockk<TtsController>(relaxed = true)
        val viewer = mockk<NovelWebViewViewer>(relaxed = true)
        val page = ReaderPage(0).apply { chapter = ReaderChapter(Chapter.create().copy(id = 1)) }

        init {
            val paged = mockk<NovelWebViewPagedController>(relaxed = true)
            every { paged.position } returns MutableStateFlow<NovelPagePosition?>(null)
            every { activity.viewModel.isSearchJumpGuarded(any()) } returns true
            every { tts.ttsPlaybackChapterId } returns 1L
            every { viewer getProperty "loadedChapters" } answers { callOriginal() }
            every { viewer["onTtsChapterCompleted"](any<Long>()) } answers { callOriginal() }
            every { viewer["isVideoChapter"]() } returns false
            every { viewer getProperty "webChapterContentReady" } returns true
            every { viewer["onScrollProgress"](any<Float>(), any<Boolean>()) } answers { callOriginal() }
            every { viewer["saveProgress"]() } answers { callOriginal() }
            every { viewer["noteReadingInteraction"]() } answers { callOriginal() }
            every { viewer["resetReadingEndProof"]() } answers { callOriginal() }
            every { viewer["markCurrentChapterShort"]() } answers { callOriginal() }
            every { viewer["saveVideoProgress"](any<Int>(), any<Boolean>()) } answers { callOriginal() }
            every { viewer.flushProgress() } answers { callOriginal() }
            every { viewer.onManualChapterAdvance(any()) } answers { callOriginal() }
            set("activity", activity)
            set("ttsController", tts)
            set("pagedController", paged)
            set("currentPage", page)
            set("chapterQueue", ChapterQueue<ReaderChapter> { it.chapter.id }.apply { reset(page.chapter) })
            set("lastPersistedPercent", -1)
            val ready = NovelWebViewViewer::class.java.declaredClasses.single { it.simpleName == "DocState" }
                .enumConstants.single { it.toString() == "READY" }
            set("docState", ready)
        }

        fun persist(progress: Float, atEnd: Boolean) = call("onScrollProgress", progress, atEnd)

        fun call(name: String, vararg arguments: Any) {
            val types = arguments.map {
                when (it) {
                    is Float -> Float::class.javaPrimitiveType
                    is Boolean -> Boolean::class.javaPrimitiveType
                    is Int -> Int::class.javaPrimitiveType
                    is Long -> Long::class.javaPrimitiveType
                    else -> it.javaClass
                }
            }.toTypedArray()
            NovelWebViewViewer::class.java.getDeclaredMethod(name, *types)
                .apply { isAccessible = true }.invoke(viewer, *arguments)
        }

        fun set(name: String, value: Any) {
            NovelWebViewViewer::class.java.getDeclaredField(name).apply { isAccessible = true }.set(viewer, value)
        }
    }
}
