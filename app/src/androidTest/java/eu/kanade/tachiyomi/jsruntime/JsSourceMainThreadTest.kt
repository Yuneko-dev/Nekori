package eu.kanade.tachiyomi.jsruntime

import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.jsplugin.model.InstalledJsPlugin
import eu.kanade.tachiyomi.jsplugin.model.JsPlugin
import eu.kanade.tachiyomi.jsplugin.source.JsSource
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
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
        val forwarding = Injekt.get<NetworkHelper>().domainForwarding
        forwarding.put("https://selected.invalid", "https://forwarded.invalid", global = false)
        try {
            assertEquals("https://forwarded.invalid/novel/book", source.resolveUrl("/book", isNovel = true))
        } finally {
            forwarding.remove("https://selected.invalid")
        }
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

    @Test
    fun emptyPageRemainsRetryable() = runBlocking {
        val source = JsSource(
            InstalledJsPlugin(
                plugin = JsPlugin(
                    id = "empty-page.source.test",
                    name = "Empty page source test",
                    site = "https://example.invalid",
                    lang = "English",
                    version = "1",
                    url = "https://example.com/plugin.js",
                    iconUrl = "",
                ),
                code = """
                    let pageCalls = 0;
                    exports.default = {
                      id: 'empty-page.source.test',
                      name: 'Empty page source test',
                      version: '1',
                      site: 'https://example.invalid',
                      parseNovel: async path => ({
                        name: 'Novel',
                        path,
                        totalPages: 2,
                        chapters: [],
                      }),
                      parsePage: async () => ({
                        chapters: ++pageCalls === 1
                          ? []
                          : [{ name: 'Chapter 1', path: '/chapter-1' }],
                      }),
                    };
                """.trimIndent(),
                installedVersion = "1",
                repositoryUrl = "https://example.com/plugins.json",
            ),
        )

        val error = runCatching { source.getPage("/book", "2", forceRefresh = false) }.exceptionOrNull()
        val chapters = source.getPage("/book", "2", forceRefresh = false)

        assertTrue(error?.message.orEmpty().contains("parsePage(2) returned no chapters"))
        assertEquals(listOf("/chapter-1"), chapters.map { it.url })
    }

    @Test
    fun exposesEveryLnReaderFilterType() = runBlocking {
        val filters = createSource().getFilterListAsync()

        assertTrue(filters.any { it is JsSource.JsTextFilter })
        assertTrue(filters.any { it is JsSource.JsSelectFilter })
        assertTrue(filters.any { it is JsSource.JsCheckboxGroup })
        assertTrue(filters.any { it is JsSource.JsSwitchFilter })
        assertTrue(filters.any { it is JsSource.JsTriStateGroup })
    }

    @Test
    fun exposesSelectAndCheckboxGroupPluginSettings() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val screen = PreferenceManager(context).createPreferenceScreen(context)

        createSource().setupPreferenceScreenAsync(screen)

        instrumentation.runOnMainSync {
            val select = screen.findPreference<ListPreference>("quality")
            val groups = screen.findPreference<MultiSelectListPreference>("genres")
            assertEquals("high", select?.value)
            assertEquals(setOf("action"), groups?.values)
        }
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
                        query: { type: 'Text', label: 'Query', value: '' },
                        category: {
                          type: 'Picker',
                          label: 'Category',
                          value: 'all',
                          options: [{ label: 'All', value: 'all' }],
                        },
                        genres: {
                          type: 'Checkbox',
                          label: 'Genres',
                          value: ['action'],
                          options: [{ label: 'Action', value: 'action' }],
                        },
                        completed: { type: 'Switch', label: 'Completed', value: false },
                        tags: {
                          type: 'XCheckbox',
                          label: 'Tags',
                          value: { include: [], exclude: [] },
                          options: [{ label: 'Fantasy', value: 'fantasy' }],
                        },
                      },
                      pluginSettings: {
                        enabled: { type: 'Switch', label: 'Enabled', value: true },
                        username: { type: 'Text', label: 'Username', value: '' },
                        quality: {
                          type: 'Select',
                          label: 'Quality',
                          value: 'high',
                          options: [
                            { label: 'High', value: 'high' },
                            { label: 'Low', value: 'low' },
                          ],
                        },
                        genres: {
                          type: 'CheckboxGroup',
                          label: 'Genres',
                          value: ['action'],
                          options: [
                            { label: 'Action', value: 'action' },
                            { label: 'Fantasy', value: 'fantasy' },
                          ],
                        },
                      },
                    };
                """.trimIndent(),
                installedVersion = "1",
                repositoryUrl = "https://example.com/plugins.json",
            ),
        )
}
