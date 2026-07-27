package eu.kanade.tachiyomi.jsruntime

import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.jsplugin.model.InstalledJsPlugin
import eu.kanade.tachiyomi.jsplugin.model.JsPlugin
import eu.kanade.tachiyomi.jsplugin.source.JsSource
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
