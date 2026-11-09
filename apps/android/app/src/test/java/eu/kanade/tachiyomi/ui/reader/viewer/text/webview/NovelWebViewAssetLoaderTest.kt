package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NovelWebViewAssetLoaderTest {

    @Test
    fun `reader assets resolve below the fixed asset root`() {
        assertEquals(
            "novel-reader/player/core-player.js",
            NovelWebViewAssetLoader.resolveAssetPath(
                "https://nekori.reader/assets/player/core-player.js",
            ),
        )
    }

    @Test
    fun `foreign origins and non asset paths are rejected`() {
        assertNull(NovelWebViewAssetLoader.resolveAssetPath("https://example.com/assets/reader.css"))
        assertNull(NovelWebViewAssetLoader.resolveAssetPath("http://nekori.reader/assets/reader.css"))
        assertNull(NovelWebViewAssetLoader.resolveAssetPath("https://nekori.reader/reader.css"))
        assertNull(NovelWebViewAssetLoader.resolveAssetPath("https://nekori.reader:8443/assets/reader.css"))
        assertNull(NovelWebViewAssetLoader.resolveAssetPath("https://user@nekori.reader/assets/reader.css"))
    }

    @Test
    fun `literal and encoded traversal cannot escape the asset root`() {
        listOf(
            "https://nekori.reader/assets/../secret",
            "https://nekori.reader/assets/%2e%2e/secret",
            "https://nekori.reader/assets/%2Fsecret",
            "https://nekori.reader/assets/%5csecret",
            "https://nekori.reader/assets/a%2fb.js",
            "https://nekori.reader/assets/a%5cb.js",
            "https://nekori.reader/assets/%00.js",
            "https://nekori.reader/assets/",
        ).forEach { assertNull(NovelWebViewAssetLoader.resolveAssetPath(it), it) }
    }

    @Test
    fun `invalid paths still belong to the private asset origin and cannot fall through to network`() {
        assertTrue(NovelWebViewAssetLoader.isReaderAssetUrl("https://nekori.reader/assets/%2e%2e/secret"))
        assertFalse(NovelWebViewAssetLoader.isReaderAssetUrl("https://example.com/assets/%2e%2e/secret"))
    }
}
