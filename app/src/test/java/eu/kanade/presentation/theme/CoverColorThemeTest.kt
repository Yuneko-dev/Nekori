package eu.kanade.presentation.theme

import android.os.Trace
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CoverColorThemeTest {
    @Test
    fun `cover colors preserve reader text size and parent typography`() {
        // Compose's Android tracing has no JVM implementation; theme composition itself is real.
        mockkStatic(Trace::class)
        every { Trace.beginSection(any()) } returns Unit
        every { Trace.endSection() } returns Unit
        val recomposer = Recomposer(Dispatchers.Unconfined)
        val composition = Composition(NoOpApplier(), recomposer)
        val typography = Typography(titleLarge = TextStyle(fontSize = 21.sp))
        var observedSize = 0.sp
        var originalSize = 0.sp
        try {
            composition.setContent {
                MaterialExpressiveTheme(typography = typography) {
                    val readerTextStyle = MaterialTheme.typography.bodySmall
                    val fallback = MaterialTheme.colorScheme
                    val shapes = MaterialTheme.shapes
                    val motion = MaterialTheme.motionScheme
                    CompositionLocalProvider(LocalTextStyle provides readerTextStyle) {
                        MaterialExpressiveTheme(colorScheme = darkColorScheme()) {
                            originalSize = LocalTextStyle.current.fontSize
                        }
                        // Both a cover palette and the disabled/missing-cover fallback use this wrapper.
                        for (scheme in listOf(darkColorScheme(), fallback)) {
                            CoverColorTheme(scheme) {
                                observedSize = LocalTextStyle.current.fontSize
                                assertEquals(readerTextStyle, LocalTextStyle.current)
                                assertEquals(typography, MaterialTheme.typography)
                                assertEquals(shapes, MaterialTheme.shapes)
                                assertEquals(motion, MaterialTheme.motionScheme)
                                assertEquals(scheme, MaterialTheme.colorScheme)
                                assertEquals(scheme.onBackground, LocalContentColor.current)
                            }
                        }
                    }
                }
            }
            assertEquals(12.sp, observedSize)
            assertEquals(16.sp, originalSize)
        } finally {
            composition.dispose()
            recomposer.cancel()
            unmockkStatic(Trace::class)
        }
    }
}

private class NoOpApplier : AbstractApplier<Unit>(Unit) {
    override fun insertBottomUp(index: Int, instance: Unit) = Unit
    override fun insertTopDown(index: Int, instance: Unit) = Unit
    override fun move(from: Int, to: Int, count: Int) = Unit
    override fun remove(index: Int, count: Int) = Unit
    override fun onClear() = Unit
}
