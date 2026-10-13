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
        assertTrue(html.contains("https://nekori.reader/assets/skeleton.css"))
        assertTrue(html.contains("https://nekori.reader/assets/skeleton.js"))
        assertTrue(html.contains("\"fontSize\":18"))
        assertTrue(html.contains("\"lineGap\":10.8"))
        assertTrue(html.contains("\"marginTop\":48"))
        assertTrue(html.contains("\"marginRight\":24"))
        assertTrue(html.contains("\"marginBottom\":16"))
        assertTrue(html.contains("\"marginLeft\":20"))
        assertFalse(html.contains("Math.random"))
        assertFalse(html.contains("<style>"))
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
    fun `buildHtml contains no inline executable scripts`() {
        val html = NovelWebViewLoadingSkeleton.buildHtml(style, "Loading...")

        val document = org.jsoup.Jsoup.parse(html)
        assertTrue(document.select("script:not([src]):not([type=application/json])").isEmpty())
        assertTrue(document.select("#nekori-skeleton-config[type=application/json]").isNotEmpty())
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
