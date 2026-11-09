package eu.kanade.presentation.theme

import androidx.compose.ui.graphics.Color
import com.materialkolor.PaletteStyle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class CoverBasedThemeTest {
    @Test
    fun `every cover style supports light dark and amoled schemes`() {
        PaletteStyle.entries.forEach { style ->
            val light = coverColorScheme(Color.Red, style, dark = false, amoled = false)
            val dark = coverColorScheme(Color.Red, style, dark = true, amoled = false)
            val amoled = coverColorScheme(Color.Red, style, dark = true, amoled = true)
            assertNotEquals(light.background, dark.background, style.name)
            assertNotEquals(light.primary, light.onPrimary, style.name)
            assertNotEquals(dark.primary, dark.onPrimary, style.name)
            assertEquals(Color.Black, amoled.background, style.name)
            val lightAmoled = coverColorScheme(Color.Red, style, dark = false, amoled = true)
            assertEquals(light.background, lightAmoled.background, style.name)
            assertEquals(light.primary, lightAmoled.primary, style.name)
        }
    }

    @Test
    fun `cover seed and selected style affect the palette`() {
        val red = coverColorScheme(Color.Red, PaletteStyle.Vibrant, dark = false, amoled = false)
        val blue = coverColorScheme(Color.Blue, PaletteStyle.Vibrant, dark = false, amoled = false)
        val neutral = coverColorScheme(Color.Red, PaletteStyle.Neutral, dark = false, amoled = false)
        assertNotEquals(red.primary, blue.primary)
        assertNotEquals(red.primary, neutral.primary)
    }
}
