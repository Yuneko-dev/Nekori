@file:Suppress("ktlint:standard:filename")

package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.RegexReplacement
import eu.kanade.tachiyomi.ui.reader.setting.ReplacementTarget
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class ReplacementScopeTest {
    private val novel = ReplacementTarget(42, "/novel//one#part")
    private val global = RegexReplacement("Global", "old", "middle", isRegex = false)
    private val local = RegexReplacement(
        "Novel",
        "middle",
        "new",
        isRegex = false,
        scope = RegexReplacement.NOVEL,
        target = novel,
    )

    private fun preferences(vararg rules: RegexReplacement) = ReaderPreferences(InMemoryPreferenceStore()).apply {
        novelRegexReplacements.set(Json.encodeToString(rules.toList()))
    }

    @Test
    fun `global runs before matching novel and cache cannot leak to another novel`() {
        val prefs = preferences(local, global)
        assertEquals("new", RegexReplacementsProcessor.apply("old", prefs, novel))
        assertEquals("middle", RegexReplacementsProcessor.apply("old", prefs, novel.copy(source = 43)))
        assertEquals("middle", RegexReplacementsProcessor.apply("old", prefs, novel.copy(path = "/novel/one#part")))
        assertEquals("middle", RegexReplacementsProcessor.apply("old", prefs))
        assertEquals("new", RegexReplacementsProcessor.apply("old", prefs, novel))
    }

    @Test
    fun `disabled incomplete and unknown scopes do not run`() {
        val prefs = preferences(
            local.copy(id = "disabled", enabled = false),
            local.copy(id = "missing", target = null),
            local.copy(id = "empty", target = novel.copy(path = "")),
            local.copy(id = "unknown", scope = "PLUGIN"),
            global.copy(id = "inconsistent", target = novel),
        )
        assertEquals("old middle", RegexReplacementsProcessor.apply("old middle", prefs, novel))
    }

    @Test
    fun `legacy backup defaults to global and round trip preserves portable targets`() {
        val legacy = RegexReplacement.decode("""[{"title":"Old","pattern":"x","replacement":"y"}]""").single()
        assertEquals(RegexReplacement.GLOBAL, legacy.scope)
        assertTrue(legacy.appliesTo(null))
        val restored = RegexReplacement.decode(Json.encodeToString(listOf(legacy, local)))
        assertEquals(listOf(legacy, local), restored)
        assertEquals("/novel//one#part", restored.last().target?.path)
    }

    @Test
    fun `invalid saved data is rejected rather than replaced with an empty list`() {
        assertThrows(Exception::class.java) { RegexReplacement.decode("not json") }
        assertThrows(Exception::class.java) { RegexReplacement.decode(Json.encodeToString(listOf(local, local))) }
        assertThrows(Exception::class.java) { RegexReplacement.decode("""[{"title":"x"}]""") }
    }

    @Test
    fun `editor preview and reader use identical replacements`() {
        val cases = listOf(
            global.copy(pattern = "x", replacement = "\$5\\path"),
            global.copy(pattern = "(x)", replacement = "[$1]", isRegex = true),
            global.copy(pattern = "X", replacement = "y", isRegex = true, caseSensitive = true),
            global.copy(pattern = "x", replacement = "", matchWholeWord = true),
        )
        cases.forEach { rule ->
            assertEquals(rule.replace("x X xx"), RegexReplacementsProcessor.apply("x X xx", preferences(rule)))
        }
    }

    @Test
    fun `foreground and pretranslation pass identical scoped text to translation`() = runBlocking {
        val prefs = preferences(local, global)
        val pipeline = ContentPipeline(prefs)
        val config = ContentConfig.from(prefs, RenderTarget.WEB_VIEW, "chapter.txt", "Chapter", novel)
        val ahead = pipeline.preTranslate("old", config)
        var translationInput = ""
        pipeline.process("old", config) { text ->
            translationInput = text
            text
        }
        assertEquals(ahead.text, translationInput)
        assertTrue(translationInput.contains("new"))
        assertFalse(pipeline.preTranslate("old", config.copy(replacementTarget = null)).text.contains("new"))
    }

    @Test
    fun `large unrelated rule sets are filtered before regex compilation`() {
        val unrelated = (1..1000).map { index ->
            local.copy(id = "rule-$index", pattern = "[", target = novel.copy(path = "other-$index"))
        }
        val prefs = preferences(*(unrelated + global + local).toTypedArray())
        assertEquals("new", RegexReplacementsProcessor.apply("old", prefs, novel))
        prefs.novelRegexReplacements.set(Json.encodeToString(listOf(global)))
        assertEquals("middle", RegexReplacementsProcessor.apply("old", prefs, novel))
    }
}
