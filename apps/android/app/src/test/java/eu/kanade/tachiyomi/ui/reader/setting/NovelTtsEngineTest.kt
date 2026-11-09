package eu.kanade.tachiyomi.ui.reader.setting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class NovelTtsEngineTest {

    @Test
    fun `preference value round trips every supported engine`() {
        val engines = listOf(
            NovelTtsEngine.SystemDefault,
            NovelTtsEngine.Android("com.example.tts"),
            NovelTtsEngine.TikTok,
        )

        engines.forEach { engine ->
            assertEquals(engine, NovelTtsEngine.fromPreference(engine.preferenceValue))
        }
    }

    @Test
    fun `invalid preference value falls back to system default`() {
        listOf("", "android:", "unknown", "android:   ").forEach { value ->
            assertEquals(NovelTtsEngine.SystemDefault, NovelTtsEngine.fromPreference(value))
        }
    }

    @Test
    fun `legacy TikTok preference migrates once to engine selection`() {
        val store = InMemoryPreferenceStore()
        val legacyPreference = store.getBoolean("pref_novel_tts_use_tiktok", false).apply { set(true) }

        val preferences = ReaderPreferences(store)

        assertEquals(NovelTtsEngine.TikTok.preferenceValue, preferences.novelTtsEngine.get())
        assertFalse(legacyPreference.isSet())
    }
}
