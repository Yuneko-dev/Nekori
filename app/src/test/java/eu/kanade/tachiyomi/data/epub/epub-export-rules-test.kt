@file:Suppress("ktlint:standard:filename")

package eu.kanade.tachiyomi.data.epub

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.RegexReplacement
import eu.kanade.tachiyomi.ui.reader.setting.ReplacementTarget
import kotlinx.serialization.json.Json
import mihon.core.archive.EpubWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class EpubExportRulesTest {
    @Test
    fun `original export applies Global then exact Novel once while translated cache stays as stored`() {
        val target = ReplacementTarget(Long.MAX_VALUE, "/Novel//A#part/")
        val preferences = ReaderPreferences(InMemoryPreferenceStore())
        val novel =
            RegexReplacement("novel", "AA", "B", isRegex = false, scope = RegexReplacement.NOVEL, target = target)
        val global = RegexReplacement("global", "A", "AA", isRegex = false)
        preferences.novelRegexReplacements.set(Json.encodeToString(listOf(novel, global)))
        val original = EpubWriter.Chapter("chapter", "<p>A</p>")
        assertEquals("<p>B</p>", EpubExportJob.applyOriginalExportRules(original, true, preferences, target).content)
        assertEquals(
            "<p>AA</p>",
            EpubExportJob.applyOriginalExportRules(
                original,
                true,
                preferences,
                target.copy(path = target.path.dropLast(1)),
            ).content,
        )
        val translated = original.copy(content = "<p>AA</p>")
        assertEquals(translated, EpubExportJob.applyOriginalExportRules(translated, false, preferences, target))
    }
}
