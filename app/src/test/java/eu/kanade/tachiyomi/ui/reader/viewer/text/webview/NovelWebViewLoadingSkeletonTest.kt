package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NovelWebViewLoadingSkeletonTest {

    private val style = NovelWebViewLoadingSkeleton.Style(
        backgroundColor = 0xFF121212.toInt(),
        fontSize = 18,
        lineHeight = 1.6f,
        marginLeft = 20,
        marginRight = 24,
        marginTop = 48,
        marginBottom = 16,
    )

    @Test
    fun `buildHtml renders an animated reader-sized skeleton`() {
        val html = NovelWebViewLoadingSkeleton.buildHtml(style, "Loading...")

        assertTrue(html.contains("""class="skeleton""""))
        assertTrue(html.contains("flex-direction: column"))
        assertTrue(html.contains("flex-shrink: 0"))
        assertTrue(html.contains("@keyframes shimmer"))
        assertTrue(html.contains("animation: shimmer 1000ms infinite"))
        assertTrue(html.contains("cubic-bezier(0.42, 0, 1, 1)"))
        assertTrue(html.contains("cubic-bezier(0, 0, 0.58, 1)"))
        assertTrue(html.contains("height: 18px"))
        assertTrue(html.contains("margin: 0 0 10.8px"))
        assertTrue(html.contains("padding: 48px 24px 16px 20px"))
        assertTrue(html.contains("Math.random() * 4 > 1"))
        assertTrue(html.contains("Math.max(0.1, Math.random()) * 90"))
        assertTrue(html.contains("""element.style.width = `${'$'}{width}vw`"""))
        assertFalse(html.contains("<body>\n    <div>Loading...</div>"))
    }

    @Test
    fun `buildHtml retains an escaped accessible loading status`() {
        val html = NovelWebViewLoadingSkeleton.buildHtml(style, "Loading <chapter>")

        assertTrue(html.contains("""role="status""""))
        assertTrue(html.contains("Loading &lt;chapter&gt;"))
        assertFalse(html.contains("Loading <chapter>"))
    }

    @Test
    fun `buildHtml disables shimmer when reduced motion is requested`() {
        val html = NovelWebViewLoadingSkeleton.buildHtml(style, "Loading...")

        assertTrue(html.contains("@media (prefers-reduced-motion: reduce)"))
        assertTrue(html.contains(".shimmer { animation: none; }"))
    }

    @Test
    fun `buildHtml uses the same HSL shimmer colors as LNReader`() {
        val darkHtml = NovelWebViewLoadingSkeleton.buildHtml(style, "Loading...")
        val blackHtml = NovelWebViewLoadingSkeleton.buildHtml(
            style.copy(backgroundColor = 0xFF000000.toInt()),
            "Loading...",
        )
        val lightHtml = NovelWebViewLoadingSkeleton.buildHtml(
            style.copy(backgroundColor = 0xFFFFFFFF.toInt()),
            "Loading...",
        )

        assertTrue(darkHtml.contains("hsl(0, 0%, 7.8%)"))
        assertTrue(darkHtml.contains("hsl(0, 0%, 9.9%)"))
        assertTrue(blackHtml.contains("hsl(0, 0%, 2%)"))
        assertTrue(blackHtml.contains("hsl(0, 0%, 8%)"))
        assertTrue(lightHtml.contains("hsl(0, 0%, 96%)"))
        assertTrue(lightHtml.contains("hsl(0, 0%, 92%)"))
    }
}
