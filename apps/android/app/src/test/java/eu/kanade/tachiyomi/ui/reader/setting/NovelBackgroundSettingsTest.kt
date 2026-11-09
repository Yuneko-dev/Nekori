package eu.kanade.tachiyomi.ui.reader.setting

import eu.kanade.tachiyomi.data.background.ReaderBackgroundManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NovelBackgroundSettingsTest {
    @Test
    fun `switching display modes clears tile repeat and keeps the image and effects`() {
        val original = NovelBackgroundSettings(image = "photo.png", blur = 4, opacity = 70)
        val tile = original.withMode("tile")
        assertEquals("auto", tile.size)
        assertTrue(tile.repeat)
        assertEquals("top left", tile.position)
        val stretch = tile.withMode("stretch")
        assertEquals("stretch", stretch.mode)
        assertEquals("stretch", stretch.size)
        assertFalse(stretch.repeat)
        assertEquals("center", stretch.position)
        assertEquals(original.image, stretch.image)
        assertEquals(original.blur, stretch.blur)
        assertEquals(original.opacity, stretch.opacity)
        assertEquals("contain", stretch.withMode("fit").size)
        assertEquals("auto", stretch.withMode("center").size)
        assertEquals("cover", stretch.withMode("fill").size)
    }

    @Test
    fun `malformed preferences fall back and numeric css values are bounded`() {
        assertEquals(NovelBackgroundSettings(), NovelBackgroundSettings.decode("invalid"))
        val decoded = NovelBackgroundSettings.decode(
            """{"image":"saved.png","size":"bad; color:red","position":"bad","opacity":999,"blur":-5}""",
        )
        assertEquals("saved.png", decoded.image)
        assertEquals("cover", decoded.size)
        assertEquals("center", decoded.position)
        assertEquals(100, decoded.opacity)
        assertEquals(0, decoded.blur)
    }

    @Test
    fun `stored IDs cannot address paths or arbitrary content URIs`() {
        listOf(
            "../cover.png",
            "a/b.svg",
            "a\\b.jpg",
            "content://secret/image.png",
            "file:secret.png",
            "a\u0000.png",
            "x.html",
            "",
        )
            .forEach { assertFalse(ReaderBackgroundManager.validFileName(it), it) }
        assertTrue(ReaderBackgroundManager.validFileName("Ảnh bầu trời.PNG"))
        assertEquals("My_Image", ReaderBackgroundManager.safeName(" My/Image "))
        assertEquals("Background", ReaderBackgroundManager.safeName("..."))
    }
}
