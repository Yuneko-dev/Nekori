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
                "https://tsundoku.reader/assets/player/core-player.js",
            ),
        )
    }

    @Test
    fun `foreign origins and non asset paths are rejected`() {
        assertNull(NovelWebViewAssetLoader.resolveAssetPath("https://example.com/assets/reader.css"))
        assertNull(NovelWebViewAssetLoader.resolveAssetPath("http://tsundoku.reader/assets/reader.css"))
        assertNull(NovelWebViewAssetLoader.resolveAssetPath("https://tsundoku.reader/reader.css"))
    }

    @Test
    fun `literal and encoded traversal cannot escape the asset root`() {
        listOf(
            "https://tsundoku.reader/assets/../secret",
            "https://tsundoku.reader/assets/%2e%2e/secret",
            "https://tsundoku.reader/assets/%2Fsecret",
            "https://tsundoku.reader/assets/%5csecret",
            "https://tsundoku.reader/assets/a%2fb.js",
            "https://tsundoku.reader/assets/a%5cb.js",
            "https://tsundoku.reader/assets/%00.js",
            "https://tsundoku.reader/assets/",
        ).forEach { assertNull(NovelWebViewAssetLoader.resolveAssetPath(it), it) }
    }

    @Test
    fun `invalid paths still belong to the private asset origin and cannot fall through to network`() {
        assertTrue(NovelWebViewAssetLoader.isReaderAssetUrl("https://tsundoku.reader/assets/%2e%2e/secret"))
        assertFalse(NovelWebViewAssetLoader.isReaderAssetUrl("https://example.com/assets/%2e%2e/secret"))
    }
}
