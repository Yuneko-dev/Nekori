package eu.kanade.tachiyomi.ui.reader.setting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class NovelPagedReaderSettingsTest {

    @Test
    fun `paged reader preferences use compatible defaults`() {
        val preferences = ReaderPreferences(InMemoryPreferenceStore())

        assertEquals(NovelReadingLayout.SCROLL, preferences.novelReadingLayout.get())
        assertEquals(NovelPageSpread.AUTO, preferences.novelPageSpread.get())
        assertEquals(NovelPageEffect.SLIDE, preferences.novelPageEffect.get())
        assertEquals(true, preferences.novelPagedSwipeNavigation.get())
        assertEquals(5, preferences.novelAutoPageIntervalSeconds.get())
    }

    @Test
    fun `paged reader preferences round trip enum values`() {
        val preferences = ReaderPreferences(InMemoryPreferenceStore())

        preferences.novelReadingLayout.set(NovelReadingLayout.PAGED)
        preferences.novelPageSpread.set(NovelPageSpread.DOUBLE)
        preferences.novelPageEffect.set(NovelPageEffect.CURL)

        assertEquals(NovelReadingLayout.PAGED, preferences.novelReadingLayout.get())
        assertEquals(NovelPageSpread.DOUBLE, preferences.novelPageSpread.get())
        assertEquals(NovelPageEffect.CURL, preferences.novelPageEffect.get())

        preferences.novelPageEffect.set(NovelPageEffect.HORIZONTAL)
        assertEquals(NovelPageEffect.HORIZONTAL, preferences.novelPageEffect.get())
    }
}
