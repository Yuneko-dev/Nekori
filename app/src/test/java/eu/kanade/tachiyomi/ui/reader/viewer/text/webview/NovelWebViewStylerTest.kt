package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import eu.kanade.presentation.reader.settings.CodeSnippet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NovelWebViewStylerTest {

    @Test
    fun `not reapplying runs every enabled snippet regardless of baseline`() {
        val snippets = listOf(CodeSnippet(title = "a", code = "1", id = "a"))
        val toRun = NovelWebViewStyler.snippetsToRun(
            snippets,
            reapplyChangedOnly = false,
            lastAppliedSnippetCode = mapOf("a" to "1"),
        )
        assertEquals(snippets, toRun)
    }

    @Test
    fun `reapply skips a snippet whose code matches the baseline`() {
        val snippets = listOf(CodeSnippet(title = "a", code = "1", id = "a"))
        val toRun = NovelWebViewStyler.snippetsToRun(
            snippets,
            reapplyChangedOnly = true,
            lastAppliedSnippetCode = mapOf("a" to "1"),
        )
        assertTrue(toRun.isEmpty())
    }

    @Test
    fun `reapply runs a snippet whose code changed since the baseline`() {
        val snippets = listOf(CodeSnippet(title = "a", code = "2", id = "a"))
        val toRun = NovelWebViewStyler.snippetsToRun(
            snippets,
            reapplyChangedOnly = true,
            lastAppliedSnippetCode = mapOf("a" to "1"),
        )
        assertEquals(snippets, toRun)
    }

    @Test
    fun `reapply runs a snippet absent from the baseline`() {
        val snippets = listOf(CodeSnippet(title = "new", code = "1", id = "new"))
        val toRun = NovelWebViewStyler.snippetsToRun(
            snippets,
            reapplyChangedOnly = true,
            lastAppliedSnippetCode = emptyMap(),
        )
        assertEquals(snippets, toRun)
    }

    @Test
    fun `reapply only runs the changed snippet among several`() {
        val unchanged = CodeSnippet(title = "u", code = "1", id = "u")
        val changed = CodeSnippet(title = "c", code = "2", id = "c")
        val toRun = NovelWebViewStyler.snippetsToRun(
            listOf(unchanged, changed),
            reapplyChangedOnly = true,
            lastAppliedSnippetCode = mapOf("u" to "1", "c" to "1"),
        )
        assertEquals(listOf(changed), toRun)
    }

    // nextAppliedSnippetCode

    @Test
    fun `append-mode baseline update lets a later revert be detected as changed`() {
        var baseline = mapOf("a" to "v1")

        val editedToV2 = listOf(CodeSnippet(title = "a", code = "v2", id = "a"))
        NovelWebViewStyler.snippetsToRun(editedToV2, reapplyChangedOnly = true, baseline)
        baseline = NovelWebViewStyler.nextAppliedSnippetCode(baseline, editedToV2)

        val revertedToV1 = listOf(CodeSnippet(title = "a", code = "v1", id = "a"))
        val toRun = NovelWebViewStyler.snippetsToRun(revertedToV1, reapplyChangedOnly = true, baseline)

        assertEquals(revertedToV1, toRun)
    }

    @Test
    fun `nextAppliedSnippetCode preserves entries absent from the current snippet set`() {
        val baseline = NovelWebViewStyler.nextAppliedSnippetCode(
            mapOf("oneShot" to "code"),
            enabledSnippets = listOf(CodeSnippet(title = "append", code = "v2", id = "append")),
        )
        assertEquals(mapOf("oneShot" to "code", "append" to "v2"), baseline)
    }
}
