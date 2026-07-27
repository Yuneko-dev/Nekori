package eu.kanade.tachiyomi.jsruntime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * The question M0 exists to answer: does a real LNReader plugin run in this runtime?
 *
 * Not a golden-output test. The site's content changes, so comparing against LNReader at a different
 * moment proves nothing, and with both apps on the same Hermes engine parity is true by construction
 * rather than something to measure. What is worth proving is that the plugin evaluates at all — every
 * LNReader plugin is fetched as source and run through `Function(...)`, so a Hermes build without
 * eval would make the architecture unusable with no workaround — and that its whole exported surface
 * works against the live site, not just the one entry point that happens to be easiest to call.
 *
 * **Push the plugin before running:**
 * ```
 * adb push baomoi.com.js \
 *   /sdcard/Android/data/app.tsundoku.dev/files/baomoi.com.js
 * ```
 * The app's own external files directory needs no runtime permission and keeps the plugin source out
 * of this repository. The test is skipped, not failed, when the file is absent.
 *
 * Network-dependent by design. A failure here is either the runtime or the site — the assertion
 * messages carry the payload so the two can be told apart.
 */
@RunWith(AndroidJUnit4::class)
class BaoMoiPluginSpikeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val json = Json { ignoreUnknownKeys = true }
    private val networkClient by lazy { Injekt.get<NetworkHelper>().client }

    private fun pluginSource(): String? =
        File(context.getExternalFilesDir(null), PLUGIN_FILE)
            .takeIf { it.isFile }
            ?.readText()

    @Test
    fun runsEveryExportedEntryPointOfARealPlugin() = runBlocking {
        val code = pluginSource()
        assumeTrue("push $PLUGIN_FILE to the app's external files dir first", code != null)

        val runtime = JsRuntime(context, networkClient)
        assertSame(
            "production RN Networking must use NetworkHelper.client",
            networkClient,
            reactNativeNetworkingClient(),
        )

        // 1. Evaluation — the load-bearing step. `Function('require', 'module', code)` on Hermes.
        val meta = runtime.call("plugin.load", """{"id":"$PLUGIN_ID","code":${quote(code!!)}}""")
        assertTrue("metadata should name the plugin, was: $meta", meta.contains(PLUGIN_ID))

        // 2. popularNovels — network through OkHttp, parsing through real cheerio.
        val popular = runtime.call("plugin.popularNovels", """{"id":"$PLUGIN_ID","page":1}""")
        val novels = novelsOf(popular)
        assertTrue("popularNovels returned nothing: ${popular.take(200)}", novels.isNotEmpty())
        val novelPath = novels.first().stringField("path")

        // 3. searchNovels — a different request shape and a different parse path.
        val search = runtime.call("plugin.searchNovels", """{"id":"$PLUGIN_ID","query":"$SEARCH_QUERY","page":1}""")
        assertTrue("searchNovels should return an array, was: ${search.take(200)}", search.contains("\"novels\""))

        // 4. parseNovel — detail page, and the source of the chapter list.
        val novelJson = runtime.call("plugin.parseNovel", """{"id":"$PLUGIN_ID","path":${quote(novelPath)}}""")
        val novel = json.parseToJsonElement(novelJson).jsonObject["novel"]!!.jsonObject
        assertTrue("parseNovel returned no name: ${novelJson.take(200)}", novel["name"] != null)

        // 5. parsePage — the paged/volume surface that ordinary novel plugins do not expose.
        val chapters = novel["chapters"]?.jsonArray
        assumeTrue("novel exposes no chapters to read", !chapters.isNullOrEmpty())

        val pageJson = runtime.call(
            "plugin.parsePage",
            """{"id":"$PLUGIN_ID","path":${quote(novelPath)},"page":"1"}""",
        )
        val pageChapters = json.parseToJsonElement(pageJson).jsonObject["page"]!!.jsonObject["chapters"]?.jsonArray
        assertTrue("parsePage returned no chapters: ${pageJson.take(200)}", !pageChapters.isNullOrEmpty())

        // 6. parseChapter — the actual reading path, and the largest payload.
        val chapterPath = chapters!!.first().jsonObject.stringFieldOf("path")

        val chapter = runtime.call("plugin.parseChapter", """{"id":"$PLUGIN_ID","path":${quote(chapterPath)}}""")
        val length = json.parseToJsonElement(chapter).jsonObject["length"]!!.jsonPrimitive.content.toInt()
        assertTrue("chapter body is implausibly short ($length chars): ${chapter.take(200)}", length > 200)

        assertEquals("every call must be drained", 0, runtime.pendingCallCount())
    }

    private fun novelsOf(payload: String) =
        json.parseToJsonElement(payload).jsonObject["novels"]!!.jsonArray

    private fun kotlinx.serialization.json.JsonElement.stringField(name: String): String =
        jsonObject.stringFieldOf(name)

    private fun kotlinx.serialization.json.JsonObject.stringFieldOf(name: String): String =
        this[name]?.jsonPrimitive?.content
            ?: error("expected a \"$name\" on $this")

    /**
     * Keep React Native types out of :app source while still proving the provider's object identity.
     * The subsequent plugin operation proves that this provider is the transport used by Hermes.
     */
    private fun reactNativeNetworkingClient(): Any =
        Class.forName("com.facebook.react.modules.network.OkHttpClientProvider")
            .getMethod("getOkHttpClient")
            .invoke(null)

    /** Minimal JSON string escaping — the plugin source is 8 KB of minified JavaScript. */
    private fun quote(value: String): String = buildString(value.length + 32) {
        append('"')
        value.forEach { c ->
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }

    private companion object {
        const val PLUGIN_ID = "baomoi.com"
        const val PLUGIN_FILE = "baomoi.com.js"
        const val SEARCH_QUERY = "kinh tế"
    }
}
