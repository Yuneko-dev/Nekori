@file:Suppress("ktlint:standard:filename")

package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.mockk.every
import io.mockk.spyk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference

class NovelSeparatorPreferenceTest {
    @Test
    fun `separator changes use the existing chapter restore path instead of shifting live CSS`() = runTest {
        val preferences = spyk(ReaderPreferences(InMemoryPreferenceStore()))
        val stored = preferences.novelHideChapterSeparator
        val updates = MutableStateFlow(stored.get())
        // InMemoryPreferenceStore emits only once; Android preferences emit subsequent changes.
        val reactive = object : Preference<Boolean> by stored {
            override fun changes() = updates
            override fun set(value: Boolean) {
                stored.set(value)
                updates.value = value
            }
        }
        every { preferences.novelHideChapterSeparator } returns reactive
        assertEquals(false, preferences.novelHideChapterSeparator.get())
        var reloads = 0
        var styleChanges = 0
        NovelWebViewPreferenceObserver(
            preferences = preferences,
            scope = backgroundScope,
            onStyleChanged = { styleChanges++ },
            onScriptChanged = {},
            onChapterReloadRequested = { reloads++ },
            onBlockMediaChanged = {},
            onTtsSettingsChanged = {},
            onTtsEngineChanged = {},
        ).observe()
        runCurrent()
        for ((index, hidden) in listOf(true, false).withIndex()) {
            preferences.novelHideChapterSeparator.set(hidden)
            advanceTimeBy(200)
            runCurrent()
            assertEquals(index + 1, reloads, "reload count (style events=$styleChanges)")
            assertEquals(0, styleChanges)
        }
    }
}
