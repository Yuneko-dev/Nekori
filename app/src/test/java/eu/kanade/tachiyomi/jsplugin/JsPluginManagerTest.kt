package eu.kanade.tachiyomi.jsplugin

import eu.kanade.tachiyomi.jsplugin.model.JsPlugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JsPluginManagerTest {

    /**
     * The repository listing and the installed code disagreeing is the case this exists for: the
     * listing said v2 while the file on disk is still the v1 that runs. Believing the listing is
     * what made an update look applied when it was not.
     */
    @Test
    fun `loaded code decides what a plugin is, its listing decides where it came from`() {
        val listing = JsPlugin(
            id = "acme",
            name = "Acme Listing Name",
            site = "https://listing.example",
            lang = "English",
            version = "2.0.0",
            url = "https://repo.example/acme.js",
            iconUrl = "https://repo.example/acme.png",
            customJSFile = "abc.custom.js",
            repositoryUrl = "https://repo.example",
        )
        val loaded = JsPlugin(
            id = "acme",
            name = "Acme",
            site = "https://acme.example",
            version = "1.0.0",
        )

        val merged = JsPluginManager.mergePluginMetadata(engine = loaded, stored = listing)

        assertEquals("1.0.0", merged.version)
        assertEquals("Acme", merged.name)
        assertEquals("English", merged.lang)
        assertEquals("https://acme.example", merged.site)
        // Not the code's to declare, so the listing keeps these.
        assertEquals("https://repo.example/acme.js", merged.url)
        assertEquals("https://repo.example/acme.png", merged.iconUrl)
        assertEquals("abc.custom.js", merged.customJSFile)
        assertEquals("https://repo.example", merged.repositoryUrl)
        // A plugin with no listing at all is still fully described by its code.
        assertEquals(loaded, JsPluginManager.mergePluginMetadata(engine = loaded, stored = null))
    }

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

    @Test
    fun `repository duplicates keep the newest numeric version`() {
        val plugins = listOf(
            JsPlugin(id = "acme", name = "Acme", site = "", version = "1.10"),
            JsPlugin(id = "other", name = "Other", site = "", version = "1.0"),
            JsPlugin(id = "acme", name = "Acme", site = "", version = "2.9"),
        )

        val deduplicated = JsPluginManager.deduplicatePlugins(plugins)

        assertEquals(2, deduplicated.size)
        assertEquals("2.9", deduplicated.single { it.id == "acme" }.version)
    }

    @Test
    fun `plugin updates require a newer numeric version`() {
        assertTrue(JsPluginManager.isNewerVersion("1.10.0", "1.9.9"))
        assertTrue(JsPluginManager.isNewerVersion("2.0.0-beta", "1.9.9"))
        assertFalse(JsPluginManager.isNewerVersion("1.9.9", "1.10.0"))
        assertFalse(JsPluginManager.isNewerVersion("1.0.0-beta", "1.0.0"))
    }

    @Test
    fun `repository URL validation accepts HTTP(S) and rejects unsupported schemes`() {
        assertEquals(
            "https://example.com/plugins.json",
            JsPluginManager.validateRepositoryUrl(" https://example.com/plugins.json/ "),
        )
        assertEquals(
            "http://10.0.2.2/plugins.min.json",
            JsPluginManager.validateRepositoryUrl("http://10.0.2.2/plugins.min.json"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            JsPluginManager.validateRepositoryUrl("example.com/plugins.json")
        }
        assertThrows(IllegalArgumentException::class.java) {
            JsPluginManager.validateRepositoryUrl("ftp://example.com/plugins.json")
        }
    }

    @Test
    fun `repository manifest validation accepts LNReader fields and rejects invalid shapes`() {
        val manifest = """
            [
              {
                "id": "example",
                "name": "Example",
                "site": "domain",
                "lang": "English",
                "version": "1.0.0",
                "url": "https://example.com/example.js?x=1&y=2",
                "iconUrl": "https://example.com/icon[1].png",
                "unknownField": true
              }
            ]
        """.trimIndent()

        assertEquals("example", JsPluginManager.decodeRepositoryManifest(manifest, allowEmpty = false).single().id)
        assertEquals(emptyList<JsPlugin>(), JsPluginManager.decodeRepositoryManifest("[]", allowEmpty = true))
        assertThrows(IllegalArgumentException::class.java) {
            JsPluginManager.decodeRepositoryManifest("[]", allowEmpty = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            JsPluginManager.decodeRepositoryManifest("{}", allowEmpty = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            JsPluginManager.decodeRepositoryManifest(manifest.replace("\"site\": \"domain\",", ""), allowEmpty = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            JsPluginManager.decodeRepositoryManifest(
                manifest.replace("\"id\": \"example\"", "\"id\": \"../example\""),
                allowEmpty = true,
            )
        }
    }
}
