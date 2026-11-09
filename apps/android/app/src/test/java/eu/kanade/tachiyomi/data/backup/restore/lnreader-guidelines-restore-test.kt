@file:Suppress("ktlint:standard:filename")

package eu.kanade.tachiyomi.data.backup.restore

import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.translation.model.UserGuidelines
import tachiyomi.domain.translation.service.TranslationPreferences

class LNReaderGuidelinesRestoreTest {
    private val store = InMemoryPreferenceStore()
    private val preferences = TranslationPreferences(store)
    private val restorer = LNReaderSettingsRestorer(
        json = Json { ignoreUnknownKeys = true },
        uiPreferences = mockk(),
        libraryPreferences = mockk(),
        readerPreferences = mockk(),
        translationPreferences = preferences,
        networkPreferences = mockk(),
        aiSettingsStore = mockk(),
    )

    @Test
    fun `absent guidelines keep local while explicit empty clears`() {
        val local = """[{"id":"default","name":"Default","guidelines":"edited"},{"id":"local","name":"Local"}]"""
        preferences.userGuidelinesJson().set(local)
        restore("""{"TRANSLATE_SETTINGS":{}}""")
        assertEquals(local, preferences.userGuidelinesJson().get())
        restore("""{"TRANSLATE_SETTINGS":{"llmSystemPrompts":[]}}""")
        assertEquals(listOf(UserGuidelines.DEFAULT), UserGuidelines.decode(preferences.userGuidelinesJson().get()))
    }

    @Test
    fun `one malformed entry preserves complete existing library and reports key`() {
        val local = preferences.userGuidelinesJson().get()
        val result = restore(
            """{"TRANSLATE_SETTINGS":{"llmSystemPrompts":[{"id":"valid","title":"Valid"},{"id":"invalid"}]}}""",
        )
        assertEquals(local, preferences.userGuidelinesJson().get())
        assertEquals(listOf(UserGuidelines.PREFERENCE_KEY), result.failedSettings)
    }

    @Test
    fun `duplicate ids rejected rather than silently dropping a definition`() {
        val local = preferences.userGuidelinesJson().get()
        val result = restore(
            """{"TRANSLATE_SETTINGS":{"llmSystemPrompts":[{"id":"same","title":"One"},{"id":"same","title":"Two"}]}}""",
        )
        assertEquals(local, preferences.userGuidelinesJson().get())
        assertEquals(listOf(UserGuidelines.PREFERENCE_KEY), result.failedSettings)
    }

    @Test
    fun `foreign prompt bytes and Default survive conversion`() {
        val result = restore(
            """{"TRANSLATE_SETTINGS":{"llmSystemPrompts":[{"id":"default","title":"Default","content":"  saved\n"}],"activeSystemPromptId":"missing"}}""",
        )
        assertTrue(result.failedSettings.isEmpty())
        assertEquals("  saved\n", UserGuidelines.decode(preferences.userGuidelinesJson().get()).single().guidelines)
        assertEquals("missing", preferences.translationGuidelinesId().get())
        assertEquals("missing", preferences.chapterSummaryGuidelinesId().get())
    }

    private fun restore(raw: String) = restorer.restore(Json.parseToJsonElement(raw).jsonObject, emptyMap(), false)
}
