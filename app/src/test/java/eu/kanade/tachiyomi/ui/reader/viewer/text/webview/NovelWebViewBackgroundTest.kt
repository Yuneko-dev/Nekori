package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import eu.kanade.tachiyomi.ui.reader.setting.NovelBackgroundSettings
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NovelWebViewBackgroundTest {
    @Test
    fun `serialized native config always includes an empty image when no image is selected`() {
        val config = NovelWebViewBackground.buildConfigJson(NovelBackgroundSettings(), "failed").jsonObject

        assertEquals("", config.getValue("image").jsonPrimitive.content)
        assertEquals("failed", config.getValue("errorMessage").jsonPrimitive.content)
    }

    @Test
    fun `private image URLs round trip Unicode and reserved filename characters`() {
        listOf("asset:default_1.png", "asset:default_2.svg", "Ảnh trời sao + #1.png", "cloud & stars.svg").forEach {
            assertEquals(it, NovelWebViewBackground.imageId(NovelWebViewBackground.imageUrl(it)))
        }
        assertEquals("", NovelWebViewBackground.imageUrl(""))
    }

    @Test
    fun `private image origin rejects path traversal other origins and ambiguous queries`() {
        listOf(
            "https://example.com/image?id=a.png",
            "http://nekori.background/image?id=a.png",
            "https://nekori.background:443/image?id=a.png",
            "https://user@nekori.background/image?id=a.png",
            "https://nekori.background/other?id=a.png",
            "https://nekori.background/image?id=..%2Fa.png",
            "https://nekori.background/image?id=..%5Ca.png",
            "https://nekori.background/image?id=content%3A%2F%2Fprivate%2Fa.png",
            "https://nekori.background/image?id=a.png&id=b.png",
            "https://nekori.background/image?id=%zz",
        ).forEach { assertNull(NovelWebViewBackground.imageId(it), it) }
    }
}
