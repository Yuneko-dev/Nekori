package eu.kanade.tachiyomi.ui.reader.setting

import eu.kanade.presentation.reader.settings.novelScrollbarMode
import eu.kanade.presentation.reader.settings.setNovelScrollbarMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class NovelScrollbarSettingsTest {

    @Test
    fun `hidden progress disables scrollbars regardless of stored direction`() {
        listOf(false, true).forEach { vertical ->
            listOf("left", "right", "unknown", "").forEach { position ->
                assertEquals("none", novelScrollbarMode(false, vertical, position))
            }
        }
    }

    @Test
    fun `horizontal mode ignores leftover vertical side`() {
        listOf("left", "right", "unknown", "").forEach { position ->
            assertEquals("horizontal", novelScrollbarMode(true, false, position))
        }
    }

    @Test
    fun `vertical scrollbar recognizes only valid sides with horizontal fallback`() {
        assertEquals("vertical_left", novelScrollbarMode(true, true, "left"))
        assertEquals("vertical_right", novelScrollbarMode(true, true, "right"))
        listOf("", "center", "LEFT", " right ").forEach { position ->
            assertEquals("horizontal", novelScrollbarMode(true, true, position))
        }
    }

    @Test
    fun `settings transitions resolve the same mode from persisted preferences`() {
        val preferences = ReaderPreferences(InMemoryPreferenceStore())
        val modes = listOf("none", "horizontal", "vertical_left", "vertical_right")
        modes.forEach { previous ->
            modes.forEach { selected ->
                preferences.setNovelScrollbarMode(previous)
                preferences.setNovelScrollbarMode(selected)

                assertEquals(
                    selected,
                    novelScrollbarMode(
                        preferences.novelShowProgressSlider.get(),
                        preferences.novelVerticalScrollbar.get(),
                        preferences.novelVerticalScrollbarPosition.get(),
                    ),
                    "$previous -> $selected",
                )
            }
        }
    }
}
