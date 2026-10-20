@file:Suppress("ktlint:standard:filename")

package eu.kanade.tachiyomi.ui.reader.setting

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.translation.model.NovelAiTask
import tachiyomi.domain.translation.model.NovelGuidelinesAssignment
import tachiyomi.domain.translation.model.NovelGuidelinesAssignments
import tachiyomi.domain.translation.model.NovelGuidelinesChoice
import tachiyomi.domain.translation.model.UserGuidelines

class NovelContentCleanupTest {
    private val deleted = ReplacementTarget(42, "/novel//one#part")
    private val slashVariant = deleted.copy(path = deleted.path + "/")
    private val otherSource = deleted.copy(source = 43)

    @Test
    fun `cleanup removes only exact confirmed novel targets from every scoped collection`() {
        val store = InMemoryPreferenceStore()
        val rules = listOf(
            RegexReplacement("Global", "a", "b"),
            RegexReplacement("Deleted", "a", "b", scope = "NOVEL", target = deleted),
            RegexReplacement("Slash", "a", "b", scope = "NOVEL", target = slashVariant),
        )
        store.getString(RegexReplacement.PREFERENCE_KEY).set(Json.encodeToString(rules))
        val snippets = listOf(
            CodeSnippet("Global", "global"),
            CodeSnippet("Deleted", "deleted", scope = "NOVEL", target = deleted),
            CodeSnippet("Other source", "other", scope = "NOVEL", target = otherSource),
        )
        listOf(CodeSnippet.CSS_KEY, CodeSnippet.JS_KEY).forEach {
            store.getString(it).set(CodeSnippet.encode(snippets))
        }
        val assignments = listOf(deleted, slashVariant, otherSource).map {
            NovelGuidelinesAssignment(it, NovelAiTask.TRANSLATION, NovelGuidelinesChoice("none"))
        }
        store.getString(NovelGuidelinesAssignments.PREFERENCE_KEY).set(NovelGuidelinesAssignments.encode(assignments))
        val library = Json.encodeToString(listOf(UserGuidelines("default", "Default", "Keep")))
        store.getString(UserGuidelines.PREFERENCE_KEY).set(library)
        val original = NovelContentPreferences.cleanupSnapshot(store)
        val failures = NovelContentPreferences.cleanupDeletedTargets(store, original, setOf(deleted))
        assertTrue(failures.isEmpty())
        assertEquals(
            listOf(rules[0], rules[2]),
            RegexReplacement.decode(store.getString(RegexReplacement.PREFERENCE_KEY).get()),
        )
        listOf(CodeSnippet.CSS_KEY, CodeSnippet.JS_KEY).forEach {
            assertEquals(listOf(snippets[0], snippets[2]), CodeSnippet.decode(store.getString(it).get()))
        }
        assertEquals(
            assignments.drop(1),
            NovelGuidelinesAssignments.decode(store.getString(NovelGuidelinesAssignments.PREFERENCE_KEY).get()),
        )
        assertEquals(library, store.getString(UserGuidelines.PREFERENCE_KEY).get())
    }

    @Test
    fun `edits and malformed collections survive while other collections can clean up`() {
        val store = InMemoryPreferenceStore()
        val original = CodeSnippet.encode(listOf(CodeSnippet("Remove", "code", scope = "NOVEL", target = deleted)))
        store.getString(CodeSnippet.CSS_KEY).set(original)
        store.getString(CodeSnippet.JS_KEY).set(original)
        store.getString(NovelGuidelinesAssignments.PREFERENCE_KEY).set("future format")
        val snapshot = NovelContentPreferences.cleanupSnapshot(store)
        val edited = CodeSnippet.encode(listOf(CodeSnippet("Edited", "new", scope = "NOVEL", target = deleted)))
        store.getString(CodeSnippet.CSS_KEY).set(edited)
        val failures = NovelContentPreferences.cleanupDeletedTargets(store, snapshot, setOf(deleted))
        assertEquals(setOf(CodeSnippet.CSS_KEY, NovelGuidelinesAssignments.PREFERENCE_KEY), failures)
        assertEquals(edited, store.getString(CodeSnippet.CSS_KEY).get())
        assertEquals("future format", store.getString(NovelGuidelinesAssignments.PREFERENCE_KEY).get())
        assertEquals("[]", store.getString(CodeSnippet.JS_KEY).get())
    }

    @Test
    fun `no confirmed deletion does not touch settings even malformed values`() {
        val store = InMemoryPreferenceStore()
        store.getString(CodeSnippet.CSS_KEY).set("broken")
        val snapshot = NovelContentPreferences.cleanupSnapshot(store)
        assertTrue(NovelContentPreferences.cleanupDeletedTargets(store, snapshot, emptySet()).isEmpty())
        assertEquals("broken", store.getString(CodeSnippet.CSS_KEY).get())
    }
}
