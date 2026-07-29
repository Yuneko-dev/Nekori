package eu.kanade.tachiyomi.jsplugin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JsPluginManagerTest {

    @Test
    fun `custom asset names are content addressed and path safe`() {
        val first = JsPluginManager.customAssetFileName("js", "document.body.dataset.plugin = 'one'")
        val same = JsPluginManager.customAssetFileName("js", "document.body.dataset.plugin = 'one'")
        val changed = JsPluginManager.customAssetFileName("js", "document.body.dataset.plugin = 'two'")
        val css = JsPluginManager.customAssetFileName("css", "document.body.dataset.plugin = 'one'")

        assertEquals(first, same)
        assertNotEquals(first, changed)
        assertNotEquals(first, css)
        assertTrue(first.endsWith(".custom.js"))
        assertTrue(JsPluginManager.isCustomAssetFileName(first))
        assertTrue(
            JsPluginManager.isCustomAssetFileName(
                first.replace(".custom.js", ".custom-js"),
            ),
        )
        assertTrue(JsPluginManager.isSafePluginId("testplugin"))
        assertFalse(JsPluginManager.isSafePluginId("../testplugin"))
    }
}
