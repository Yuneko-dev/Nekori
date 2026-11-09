@file:Suppress("ktlint:standard:filename")

package eu.kanade.tachiyomi.ui.reader.setting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class CodeSnippetsScopeTest {
    private val target = ReplacementTarget(Long.MAX_VALUE, "/Novel//A?q=X#chapter/")

    @Test
    fun `safe writes retain an explicit ID even when it equals the legacy default`() {
        val snippet = CodeSnippet("existing", "code")
        val encoded = CodeSnippet.encode(listOf(snippet))
        val decoded = CodeSnippet.decode(encoded)
        assertTrue(encoded.contains("\"id\":"))
        assertEquals(listOf(snippet), CodeSnippet.repairIds(decoded, encoded))
    }

    @Test
    fun `legacy defaults and scoped bytes survive codec`() {
        val legacy = CodeSnippet.decode("""[{"title":"old","code":"x"}]""").single()
        assertEquals(RegexReplacement.GLOBAL, legacy.scope)
        assertEquals(null, legacy.target)
        val snippet = legacy.copy(
            code = "\r\n  x();\n ",
            runOnAppend = true,
            scope = RegexReplacement.NOVEL,
            target = target,
        )
        assertEquals(listOf(snippet), CodeSnippet.decode(CodeSnippet.encode(listOf(snippet))))
    }

    @Test
    fun `effective order partitions Global before exact Novel without normalizing identity`() {
        val novel = CodeSnippet("novel", "n", scope = RegexReplacement.NOVEL, target = target)
        val global = CodeSnippet("global", "g", runOnAppend = true)
        val another = novel.copy(id = "other", target = target.copy(path = target.path.dropLast(1)))
        val disabled = global.copy(id = "off", enabled = false)
        assertEquals(listOf(global, novel), CodeSnippet.effective(listOf(novel, another, disabled, global), target))
        assertEquals(listOf(global), CodeSnippet.effective(listOf(novel, global), target, isAppend = true))
        assertEquals(listOf(global), CodeSnippet.effective(listOf(novel, global), null))
    }

    @Test
    fun `invalid and unknown formats refuse writes instead of becoming Global`() {
        listOf(
            """[{"title":"x","code":"y","scope":"NEW"}]""",
            """[{"title":"x","code":"y","scope":"NOVEL"}]""",
            """[{"title":"x","code":"y","target":{"source":1,"path":"/a"}}]""",
            """[{"title":"x","code":"y","future":true}]""",
            "{}",
            "",
        ).forEach { raw -> assertThrows(Exception::class.java) { CodeSnippet.decode(raw) } }
    }

    @Test
    fun `draft repairs duplicate IDs but cancel leaves raw untouched and save writes once`() {
        val preference = InMemoryPreferenceStore().getString("css", "[]")
        val raw = """[{"title":"same","code":"\r\nx"},{"title":"same","code":"\r\nx"}]"""
        preference.set(raw)
        val decoded = CodeSnippet.decode(raw)
        val draft = CodeSnippetsDraft(raw, decoded)
        assertEquals(decoded[0].id, decoded[1].id)
        assertNotEquals(draft.snippets.value[0].id, draft.snippets.value[1].id)
        assertEquals(raw, preference.get())
        assertTrue(draft.save(preference))
        assertEquals(listOf("\r\nx", "\r\nx"), CodeSnippet.decode(preference.get()).map { it.code })
    }

    @Test
    fun `unique missing legacy ID is repaired in draft while explicit legacy ID stays stable`() {
        val preference = InMemoryPreferenceStore().getString("css", "[]")
        val raw = """[{"title":"missing","code":"x"},{"title":"explicit","code":"y","id":"legacy-kept"}]"""
        preference.set(raw)
        val decoded = CodeSnippet.decode(raw)
        val draft = CodeSnippetsDraft(raw, decoded)
        assertNotEquals(decoded[0].id, draft.snippets.value[0].id)
        assertEquals("legacy-kept", draft.snippets.value[1].id)
        assertEquals(raw, preference.get())
        assertTrue(draft.save(preference))
        assertEquals(draft.snippets.value, CodeSnippet.decode(preference.get()))
    }

    @Test
    fun `draft cannot overwrite edits in another scope`() {
        val preference = InMemoryPreferenceStore().getString("css", "[]")
        val draft = CodeSnippetsDraft("[]", emptyList())
        draft.update(CodeSnippet("mine", "a", id = "mine"))
        val current = CodeSnippet.encode(listOf(CodeSnippet("theirs", "b", id = "theirs")))
        preference.set(current)
        assertFalse(draft.save(preference))
        assertEquals(current, preference.get())
        assertEquals("mine", draft.snippets.value.single().id)
    }

    @Test
    fun `reorder keeps other scopes and disabled positions while scope move appends`() {
        val a = CodeSnippet("a", "a", id = "a")
        val b = CodeSnippet("b", "b", id = "b", scope = RegexReplacement.NOVEL, target = target)
        val c = CodeSnippet("c", "c", id = "c", enabled = false)
        assertEquals(listOf(c, b, a), reorderScope(listOf(a, b, c), listOf(a, c), listOf(c, a)))
        assertThrows(IllegalArgumentException::class.java) { reorderScope(listOf(a, b, c), listOf(a, c), listOf(a, b)) }
        val draft = CodeSnippetsDraft("[]", listOf(a, b, c))
        val moved = a.copy(scope = RegexReplacement.NOVEL, target = target)
        draft.update(moved)
        assertEquals(listOf(b, c, moved), draft.snippets.value)
    }
}
