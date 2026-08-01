package eu.kanade.tachiyomi.jsplugin.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JsPluginModelTest {

    @Test
    fun `repository name uses GitHub owner and repository`() {
        assertEquals(
            "LNReader/lnreader-plugins",
            JsPluginRepository.nameFromUrl(
                "https://raw.githubusercontent.com/LNReader/lnreader-plugins/plugins/v3.0.0/.dist/plugins.min.json",
            ),
        )
    }

    @Test
    fun `content metadata matches LNReader display rules`() {
        val plugin = plugin(contentWarning = 2, contentType = "image")

        assertEquals("🖼️ Example", plugin.displayName())
        assertTrue(plugin.hasAdultContentWarning())
        assertFalse(plugin(contentWarning = 1).hasAdultContentWarning())
    }

    @Test
    fun `mixed and video plugins disable infinite scroll`() {
        assertFalse(plugin(contentType = "mixed").allowsInfiniteScroll())
        assertFalse(plugin(contentType = "video").allowsInfiniteScroll())
        assertTrue(plugin(contentType = "image").allowsInfiniteScroll())
        assertTrue(plugin().allowsInfiniteScroll())
    }

    private fun plugin(
        contentWarning: Int = 0,
        contentType: String? = null,
    ) = JsPlugin(
        id = "example",
        name = "Example",
        site = "https://example.com",
        lang = "English",
        version = "1.0.0",
        url = "https://example.com/plugin.js",
        iconUrl = "https://example.com/icon.png",
        contentWarning = contentWarning,
        contentType = contentType,
    )
}
