package eu.kanade.tachiyomi.ui.reader.viewer.navigation

import android.graphics.RectF
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.TappingInvertMode
import eu.kanade.tachiyomi.util.lang.invert
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ZoneOnlyNavigationTest {

    @Test
    fun `switching to centered zones clears every persisted inversion`() {
        listOf(ReaderPreferences.TAPZONE_CENTER_INDEX, ReaderPreferences.TAPZONE_CENTER_LARGE_INDEX).forEach { zone ->
            TappingInvertMode.entries.forEach { inversion ->
                assertEquals(TappingInvertMode.NONE, inversion.forNovelNavigation(zone))
            }
        }
    }

    @Test
    fun `switching to bottom zone retains vertical movement without horizontal inversion`() {
        val expected = mapOf(
            TappingInvertMode.NONE to TappingInvertMode.NONE,
            TappingInvertMode.HORIZONTAL to TappingInvertMode.NONE,
            TappingInvertMode.VERTICAL to TappingInvertMode.VERTICAL,
            TappingInvertMode.BOTH to TappingInvertMode.VERTICAL,
        )
        expected.forEach { (inversion, canonical) ->
            val effective = inversion.forNovelNavigation(ReaderPreferences.TAPZONE_BOTTOM_INDEX)
            assertEquals(canonical, effective)
            assertRectEquals(bottomZone.invert(inversion), bottomZone.invert(effective))
        }
    }

    @Test
    fun `ordinary disabled and unknown navigation modes preserve persisted inversion`() {
        (listOf(-1) + (0..5).toList() + 99).forEach { zone ->
            TappingInvertMode.entries.forEach { inversion ->
                assertEquals(inversion, inversion.forNovelNavigation(zone), "zone=$zone invert=$inversion")
            }
        }
    }

    private val centerZone = RectF(0.4f, 0.4f, 0.6f, 0.6f)
    private val centerLargeZone = RectF(0.3f, 0.3f, 0.7f, 0.7f)
    private val bottomZone = BottomNavigation.rect(0.12f)

    private fun assertRectEquals(expected: RectF, actual: RectF, message: String = "") {
        assertEquals(expected.left, actual.left, message)
        assertEquals(expected.top, actual.top, message)
        assertEquals(expected.right, actual.right, message)
        assertEquals(expected.bottom, actual.bottom, message)
    }

    @Test
    fun `center zone is unaffected by any invert mode`() {
        TappingInvertMode.entries.forEach { mode ->
            assertRectEquals(centerZone, centerZone.invert(mode), "invert=$mode")
        }
    }

    @Test
    fun `center large zone is unaffected by any invert mode`() {
        TappingInvertMode.entries.forEach { mode ->
            assertRectEquals(centerLargeZone, centerLargeZone.invert(mode), "invert=$mode")
        }
    }

    @Test
    fun `bottom zone horizontal invert is a no-op`() {
        assertRectEquals(bottomZone, bottomZone.invert(TappingInvertMode.HORIZONTAL))
    }

    @Test
    fun `bottom zone vertical invert moves it to the top with the same height`() {
        val inverted = bottomZone.invert(TappingInvertMode.VERTICAL)
        assertRectEquals(RectF(0f, 0f, 1f, 1f - bottomZone.top), inverted)
    }

    @Test
    fun `bottom zone both invert matches vertical invert alone`() {
        assertRectEquals(bottomZone.invert(TappingInvertMode.VERTICAL), bottomZone.invert(TappingInvertMode.BOTH))
    }

    @Test
    fun `bottom zone height scales with the configured fraction`() {
        assertRectEquals(RectF(0f, 1f - 0.3f, 1f, 1f), BottomNavigation.rect(0.3f))
        assertRectEquals(RectF(0f, 1f - 0.05f, 1f, 1f), BottomNavigation.rect(0.05f))
    }

    @Test
    fun `bottom zone height is coerced within bounds`() {
        assertRectEquals(RectF(0f, 1f - 0.02f, 1f, 1f), BottomNavigation.rect(0f))
        assertRectEquals(RectF(0f, 1f - 0.5f, 1f, 1f), BottomNavigation.rect(1f))
    }
}
