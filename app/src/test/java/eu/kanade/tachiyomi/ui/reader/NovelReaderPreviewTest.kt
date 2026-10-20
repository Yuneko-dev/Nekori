package eu.kanade.tachiyomi.ui.reader

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.setting.CodeSnippet
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.RegexReplacement
import eu.kanade.tachiyomi.ui.reader.setting.ReplacementTarget
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.RegexReplacementsProcessor
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class NovelReaderPreviewTest {
    @Test
    fun `sample retains its novel-scoped rules and snippets across reopening`() {
        val preview = NovelReaderPreview { "<p>Lorem ipsum</p>" }
        val target = ReplacementTarget(preview.manga.source, preview.manga.url)
        val prefs = ReaderPreferences(InMemoryPreferenceStore())
        val rule = RegexReplacement(
            "Sample",
            "Lorem",
            "Preview",
            isRegex = false,
            scope = RegexReplacement.NOVEL,
            target = target,
        )
        val snippet = CodeSnippet(
            "Sample",
            "p { color: red; }",
            id = "preview",
            scope = RegexReplacement.NOVEL,
            target = target,
        )
        prefs.novelRegexReplacements.set(Json.encodeToString(listOf(rule)))
        prefs.novelCustomCssSnippets.set(CodeSnippet.encode(listOf(snippet)))
        val reopened = NovelReaderPreview { "<p>Lorem ipsum</p>" }
        val reopenedTarget = ReplacementTarget(reopened.manga.source, reopened.manga.url)
        reopenedTarget shouldBe target
        RegexReplacementsProcessor.apply("Lorem ipsum", prefs, reopenedTarget) shouldBe "Preview ipsum"
        RegexReplacementsProcessor.apply("Lorem ipsum", prefs, null) shouldBe "Lorem ipsum"
        val savedSnippets = CodeSnippet.decode(prefs.novelCustomCssSnippets.get())
        CodeSnippet.effective(savedSnippets, reopenedTarget) shouldBe listOf(snippet)
        CodeSnippet.effective(savedSnippets, null) shouldBe emptyList()
    }

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
