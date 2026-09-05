package eu.kanade.tachiyomi.ui.reader

import eu.kanade.tachiyomi.source.model.Page
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class NovelReaderPreviewTest {
    @Test
    fun `sample reloads its asset and retains transient chapter identity`() = runTest {
        var html = "<p>First</p>"
        val preview = NovelReaderPreview { html }
        preview.load()

        val chapter = preview.chapter
        chapter.pages!!.single().apply {
            text shouldBe html
            status shouldBe Page.State.Ready
            this.chapter shouldBe chapter
        }
        chapter.chapter.manga_id shouldBe preview.manga.id
        preview.isLocal shouldBe true

        html = "<p>Reloaded</p>"
        preview.load()
        preview.chapter shouldBe chapter
        chapter.pages!!.single().text shouldBe html

        // Memory-pressure recovery uses the same loader as the real reader.
        val page = chapter.pages!!.single()
        page.text = null
        preview.loadPage(page)
        page.text shouldBe html

        preview.editedText = "<p>Edited</p>"
        preview.load()
        chapter.pages!!.single().text shouldBe "<p>Edited</p>"
    }
}
