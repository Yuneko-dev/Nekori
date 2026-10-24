package eu.kanade.tachiyomi.ui.reader.model

import eu.kanade.tachiyomi.source.model.ChapterContent
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class ReaderChapterTest {
    @Test
    fun `checkpoint is recognized before rendering`() {
        val chapter = ReaderChapter(Chapter.create())
        chapter.state = ReaderChapter.State.Loaded(
            listOf(ReaderPage(0).apply { chapterContent = checkpoint() }),
        )

        assertTrue(chapter.isCheckpoint)
    }

    @Test
    fun `rendered checkpoint survives no-cache content disposal and page recycling`() {
        val chapter = ReaderChapter(Chapter.create())
        val page = ReaderPage(0).apply { chapterContent = checkpoint() }
        chapter.state = ReaderChapter.State.Loaded(listOf(page))
        chapter.isCheckpoint = page.chapterContent!!.isCheckpoint

        page.text = null
        assertTrue(chapter.isCheckpoint)
        chapter.state = ReaderChapter.State.Wait
        assertTrue(chapter.isCheckpoint)
    }

    @Test
    fun `ready content replaces checkpoint policy after reload`() {
        val chapter = ReaderChapter(Chapter.create())
        chapter.isCheckpoint = true
        val page = ReaderPage(0).apply { chapterContent = ChapterContent(html = "<p>Chapter</p>") }
        chapter.state = ReaderChapter.State.Loaded(listOf(page))

        assertFalse(chapter.isCheckpoint)
        chapter.isCheckpoint = page.chapterContent!!.isCheckpoint
        page.text = null
        assertFalse(chapter.isCheckpoint)
    }

    @Test
    fun `ordinary chapters allow reading progress`() {
        val chapter = ReaderChapter(Chapter.create())
        assertFalse(chapter.isCheckpoint)
        chapter.state = ReaderChapter.State.Loaded(listOf(ReaderPage(0, text = "Chapter")))
        assertFalse(chapter.isCheckpoint)
    }

    private fun checkpoint() = ChapterContent(state = "checkpoint", html = "<p>Verify access</p>")
}
