package eu.kanade.tachiyomi.jsruntime

import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.jsplugin.model.InstalledJsPlugin
import eu.kanade.tachiyomi.jsplugin.model.JsPlugin
import eu.kanade.tachiyomi.jsplugin.source.JsSource
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureTimeMillis

@RunWith(AndroidJUnit4::class)
class JsSourceMainThreadTest {

    @Test
    fun coldFilterLookupNeverBlocksTheMainThread() {
        val source = createSource()

        var elapsedMillis = 0L
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            elapsedMillis = measureTimeMillis {
                source.getFilterList()
            }
        }

        assertTrue("Main-thread filter lookup took ${elapsedMillis}ms", elapsedMillis < 1_000)
    }

    @Test
    fun coldPluginSettingsSetupNeverBlocksTheMainThread() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val source = createSource()
        var elapsedMillis = 0L

        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val screen = PreferenceManager(context).createPreferenceScreen(context)
            elapsedMillis = measureTimeMillis {
                source.setupPreferenceScreen(screen)
            }
        }

        assertTrue("Main-thread plugin settings setup took ${elapsedMillis}ms", elapsedMillis < 1_000)
    }

    @Test
    fun runtimeGetterSiteReplacesMissingRepositoryMetadata() = runBlocking {
        val source = JsSource(
            InstalledJsPlugin(
                plugin = JsPlugin(
                    id = "getter-site.source.test",
                    name = "Getter site source test",
                    site = "",
                    lang = "English",
                    version = "1",
                    url = "https://example.com/plugin.js",
                    iconUrl = "",
                ),
                code = """
                    exports.default = {
                      id: 'getter-site.source.test',
                      name: 'Getter site source test',
                      version: '1',
                      get site() { return 'https://selected.invalid/'; },
                      parseNovel: async path => ({
                        name: path,
                        path,
                        chapters: [],
                      }),
                      resolveUrl: (path, isNovel) =>
                        'https://selected.invalid' + (isNovel ? '/novel' : '') + path,
                    };
                """.trimIndent(),
                installedVersion = "1",
                repositoryUrl = "https://example.com/plugins.json",
            ),
        )

        source.getMangaDetails(SManga.create().apply { url = "/book" })

        assertEquals("https://selected.invalid", source.baseUrl)
        assertEquals("https://selected.invalid/novel/book", source.resolveUrl("/book", isNovel = true))
    }

    @Test
    fun parseNovelReceivesThePluginPathUnchanged() = runBlocking {
        val source = JsSource(
            InstalledJsPlugin(
                plugin = JsPlugin(
                    id = "path.source.test",
                    name = "Path source test",
                    site = "https://example.invalid",
                    lang = "English",
                    version = "1",
                    url = "https://example.com/plugin.js",
                    iconUrl = "",
                ),
                code = """
                    exports.default = {
                      id: 'path.source.test',
                      name: 'Path source test',
                      version: '1',
                      site: 'https://example.invalid',
                      parseNovel: async path => ({
                        name: path,
                        path,
                        chapters: [],
                      }),
                    };
                """.trimIndent(),
                installedVersion = "1",
                repositoryUrl = "https://example.com/plugins.json",
            ),
        )

        val details = source.getMangaDetails(SManga.create().apply { url = "/works/123" })

        assertEquals("/works/123", details.title)
    }

    private fun createSource() =
        JsSource(
            InstalledJsPlugin(
                plugin = JsPlugin(
                    id = "main-thread.test",
                    name = "Main thread test",
                    site = "https://example.com",
                    lang = "English",
                    version = "1",
                    url = "https://example.com/plugin.js",
                    iconUrl = "",
                ),
                code = """
                    exports.default = {
                      id: 'main-thread.test',
                      name: 'Main thread test',
                      version: '1',
                      site: 'https://example.com',
                      filters: {
                        query: { type: 'TextInput', label: 'Query', value: '' },
                      },
                      pluginSettings: {
                        enabled: { type: 'Switch', label: 'Enabled', value: true },
                      },
                    };
                """.trimIndent(),
                installedVersion = "1",
                repositoryUrl = "https://example.com/plugins.json",
            ),
        )
}
