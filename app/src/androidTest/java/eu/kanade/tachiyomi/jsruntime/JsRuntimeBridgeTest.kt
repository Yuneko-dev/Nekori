package eu.kanade.tachiyomi.jsruntime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Covers the bridge semantics that a sequential ping could never catch.
 *
 * Runs in `:app`, not `:js-runtime`: React Native's Gradle plugin only bundles JavaScript in an
 * application module, so a library module's test APK has no `assets/index.android.bundle`. It still
 * imports no `com.facebook.*` type — everything goes through [JsRuntime], which is the containment
 * gate holding where it would be easiest to break.
 */
@RunWith(AndroidJUnit4::class)
class JsRuntimeBridgeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val networkClient by lazy { Injekt.get<NetworkHelper>().client }

    @Test
    fun startReturnsOnlyWhenJavaScriptHasSubscribed() = runBlocking {
        val runtime = JsRuntime(context, networkClient)

        runtime.start()

        // HOST_STARTED would mean the bundle evaluated. JS_READY means JS is actually listening —
        // the distinction that stops a command being emitted into a void.
        assertEquals(JsRuntimeState.JS_READY, runtime.state)
    }

    @Test
    fun callsIntoJavaScriptAndGetsTheResultBack() = runBlocking {
        val runtime = JsRuntime(context, networkClient)

        val json = runtime.call("sum", """{"a":2,"b":40}""")

        assertEquals("""{"result":42}""", json)
        assertEquals("pending map must drain on success", 0, runtime.pendingCallCount())
    }

    @Test
    fun javaScriptThrowBecomesKotlinException() = runBlocking {
        val runtime = JsRuntime(context, networkClient)

        try {
            runtime.call("boom", """{"message":"plugin exploded"}""")
            fail("a JS throw must surface as JsRuntimeException")
        } catch (e: JsRuntimeException) {
            assertEquals("plugin exploded", e.message)
        }
        assertEquals("pending map must drain on rejection", 0, runtime.pendingCallCount())
    }

    @Test
    fun unknownMethodFailsLoudly() = runBlocking {
        val runtime = JsRuntime(context, networkClient)

        try {
            runtime.call("noSuchMethod", "{}")
            fail("an unregistered method must throw, not resolve with null")
        } catch (e: JsRuntimeException) {
            assertTrue(
                "message should name the method, was: ${e.message}",
                e.message.orEmpty().contains("noSuchMethod"),
            )
        }
    }

    @Test
    fun concurrentCallsDoNotCrossWires() = runBlocking {
        val runtime = JsRuntime(context, networkClient)

        // Each call carries its own id. A single shared continuation, a last-request-wins bug, or a
        // non-atomic pending mutation all show up here as answers landing on the wrong caller, and
        // none of them are visible to call → result → call → result.
        val results = (1..16)
            .map { n -> async { n to runtime.call("sum", """{"a":$n,"b":0}""") } }
            .awaitAll()

        results.forEach { (n, json) -> assertEquals("""{"result":$n}""", json) }
        assertEquals(0, runtime.pendingCallCount())
    }

    @Test
    fun evaluatesLoadedPluginExpressionsByRuntimeKey() = runBlocking {
        val runtime = JsRuntime(context, networkClient)
        val code = """
            exports.default = {
              id: 'compat.test',
              name: 'Compatibility test',
              version: '1',
              site: 'https://original.invalid',
              filters: { category: { value: 'all' } },
              value: async () => ({ site: 'ok' }),
            };
        """.trimIndent()

        runtime.call(
            "plugin.load",
            """{"id":"compat.test","key":"compat.test@v1","code":${quote(code)}}""",
        )

        assertEquals(
            """{"category":{"value":"all"}}""",
            runtime.call(
                "plugin.eval",
                """{"id":"compat.test","key":"compat.test@v1","expression":"plugin.filters"}""",
            ),
        )
        assertEquals(
            """{"site":"ok"}""",
            runtime.call(
                "plugin.eval",
                """{"id":"compat.test","key":"compat.test@v1","expression":"plugin.value()"}""",
            ),
        )
    }

    @Test
    fun secureRandomFillsTheRequestedBuffer() = runBlocking {
        val runtime = JsRuntime(context, networkClient)

        val result = runtime.call("secureRandom.sample", """{"size":32}""")

        assertTrue(result, result.contains("\"size\":32"))
        assertTrue(result, result.contains("\"sameObject\":true"))
        assertTrue(result, result.contains("\"prefixUntouched\":true"))
        assertTrue(result, result.contains("\"suffixUntouched\":true"))
        assertTrue(result, result.contains("\"different\":true"))
        assertTrue(result, result.contains("\"floatError\":\"TypeMismatchError\""))
        assertTrue(result, result.contains("\"quotaError\":\"QuotaExceededError\""))
    }

    @Test
    fun pluginModuleSurfaceUsesRealBrowserLibraries() = runBlocking {
        val runtime = JsRuntime(context, networkClient)
        val code = """
            const cheerio = require('cheerio');
            const htmlparser2 = require('htmlparser2');
            const dayjs = require('dayjs');
            const urlencode = require('urlencode');
            const { NodeHtmlMarkdown } = require('node-html-markdown');
            const aes = require('@libs/aes');
            const fetchHelpers = require('@libs/fetch');
            const utils = require('@libs/utils');

            exports.default = {
              id: 'modules.test',
              name: 'Module compatibility test',
              version: '1',
              site: 'https://example.invalid',
              probe: () => {
                let parsedText = '';
                const parser = new htmlparser2.Parser({ ontext: text => { parsedText += text; } });
                parser.end('<b>parsed</b>');
                const key = new Uint8Array(16);
                const nonce = new Uint8Array(12);
                const encrypted = aes.gcm(key, nonce).encrypt(new Uint8Array([1, 2, 3]));
                const decrypted = aes.gcm(key, nonce).decrypt(encrypted);
                return {
                  cheerio: cheerio.load('<h1>real</h1>')('h1').text(),
                  htmlparser2: parsedText,
                  dayjs: dayjs('2020-01-02').format('YYYY'),
                  urlencode: urlencode.decode(urlencode.encode('a b')),
                  markdown: NodeHtmlMarkdown.translate('<strong>bold</strong>'),
                  entities: utils.decodeHtmlEntities('&amp;'),
                  buffer: utils.Buffer.from('ok').toString('hex'),
                  crypto: utils.NodeCrypto.createHash('sha256').update('abc').digest('hex').slice(0, 8),
                  random: utils.NodeCrypto.randomBytes(8).length,
                  aes: Array.from(decrypted).join(','),
                  fetch: [
                    typeof fetchHelpers.fetchApi,
                    typeof fetchHelpers.fetchText,
                    typeof fetchHelpers.fetchFile,
                    typeof fetchHelpers.fetchProto,
                  ].join(','),
                };
              },
            };
        """.trimIndent()

        runtime.call("plugin.load", """{"id":"modules.test","code":${quote(code)}}""")
        val result = runtime.call(
            "plugin.eval",
            """{"id":"modules.test","expression":"plugin.probe()"}""",
        )

        assertTrue(result, result.contains("\"cheerio\":\"real\""))
        assertTrue(result, result.contains("\"htmlparser2\":\"parsed\""))
        assertTrue(result, result.contains("\"dayjs\":\"2020\""))
        assertTrue(result, result.contains("\"urlencode\":\"a b\""))
        assertTrue(result, result.contains("\"markdown\":\"**bold**\""))
        assertTrue(result, result.contains("\"entities\":\"&\""))
        assertTrue(result, result.contains("\"buffer\":\"6f6b\""))
        assertTrue(result, result.contains("\"crypto\":\"ba7816bf\""))
        assertTrue(result, result.contains("\"random\":8"))
        assertTrue(result, result.contains("\"aes\":\"1,2,3\""))
        assertTrue(result, result.contains("\"fetch\":\"function,function,function,function\""))
    }

    @Test
    fun pluginStoragePersistsAndStaysPluginScoped() = runBlocking {
        val runtime = JsRuntime(context, networkClient)
        val pluginId = "storage.test.${System.nanoTime()}"
        val secondPluginId = "$pluginId.other"

        runtime.call("plugin.load", pluginLoadPayload(pluginId, "first"))
        assertEquals(
            "\"stored\"",
            runtime.call(
                "plugin.eval",
                """{"id":"$pluginId","key":"first","expression":"plugin.write()"}""",
            ),
        )
        assertTrue(
            runtime.call(
                "plugin.eval",
                """{"id":"$pluginId","key":"first","expression":"plugin.readRaw()"}""",
            ).contains("\"value\":\"stored\""),
        )
        assertEquals(
            "null",
            runtime.call(
                "plugin.eval",
                """{"id":"$pluginId","key":"first","expression":"plugin.expired()"}""",
            ),
        )
        runtime.call("plugin.unload", """{"id":"$pluginId","key":"first"}""")

        runtime.call("plugin.load", pluginLoadPayload(pluginId, "reloaded"))
        assertEquals(
            "\"stored\"",
            runtime.call(
                "plugin.eval",
                """{"id":"$pluginId","key":"reloaded","expression":"plugin.read()"}""",
            ),
        )

        runtime.call("plugin.load", pluginLoadPayload(secondPluginId, "isolated"))
        assertEquals(
            "null",
            runtime.call(
                "plugin.eval",
                """{"id":"$secondPluginId","key":"isolated","expression":"plugin.read()"}""",
            ),
        )
    }

    @Test
    fun pluginSettingWritesThroughThePluginStorageContract() = runBlocking {
        val runtime = JsRuntime(context, networkClient)
        val pluginId = "settings.test.${System.nanoTime()}"
        val code = """
            const { storage } = require('@libs/storage');
            exports.default = {
              id: '$pluginId',
              name: 'Settings storage test',
              version: '1',
              site: 'https://example.invalid',
              readSetting: () => storage.get('enabled'),
            };
        """.trimIndent()

        runtime.call("plugin.load", """{"id":"$pluginId","code":${quote(code)}}""")
        runtime.call(
            "plugin.storageSet",
            """{"id":"$pluginId","storageKey":"enabled","value":true}""",
        )
        assertEquals(
            "true",
            runtime.call(
                "plugin.eval",
                """{"id":"$pluginId","expression":"plugin.readSetting()"}""",
            ),
        )

        runtime.call("plugin.unload", """{"id":"$pluginId"}""")
        runtime.call("plugin.load", """{"id":"$pluginId","code":${quote(code)}}""")
        assertEquals(
            "true",
            runtime.call(
                "plugin.eval",
                """{"id":"$pluginId","expression":"plugin.readSetting()"}""",
            ),
        )
    }

    @Test
    fun cloudflareCdpIsDeferredAndUnknownModulesFailLoudly() = runBlocking {
        val runtime = JsRuntime(context, networkClient)
        val deferredCode = """
            const webview = require('@libs/webview');
            exports.default = {
              id: 'deferred.test',
              name: 'Deferred capability test',
              version: '1',
              site: 'https://example.invalid',
              probe: () => {
                try {
                  webview.solveCloudflare();
                } catch (error) {
                  return { code: error.code, capability: error.capability };
                }
              },
            };
        """.trimIndent()
        runtime.call(
            "plugin.load",
            """{"id":"deferred.test","code":${quote(deferredCode)}}""",
        )
        assertEquals(
            """{"code":"UNSUPPORTED_CAPABILITY","capability":"cloudflare-cdp"}""",
            runtime.call(
                "plugin.eval",
                """{"id":"deferred.test","expression":"plugin.probe()"}""",
            ),
        )

        val missingCode = """
            require('@libs/does-not-exist');
            exports.default = { id: 'missing.test' };
        """.trimIndent()
        try {
            runtime.call("plugin.load", """{"id":"missing.test","code":${quote(missingCode)}}""")
            fail("missing modules must reject during plugin load")
        } catch (error: JsRuntimeException) {
            assertTrue(error.message, error.message.orEmpty().contains("@libs/does-not-exist"))
        }
    }

    @Test
    fun cancellingACallDrainsItAndIgnoresTheLateAnswer() = runBlocking {
        val runtime = JsRuntime(context, networkClient)
        runtime.start()
        val before = runtime.pendingCallCount()

        try {
            // "never" resolves on the JS side only when the process ends, so the only way out is the
            // timeout — which is exactly the shape of a plugin call that hangs on a dead site.
            withTimeout(500) { runtime.call("never", "{}") }
            fail("the call should have timed out")
        } catch (_: TimeoutCancellationException) {
            // expected
        }

        // Without invokeOnCancellation removing the entry, this leaks one continuation per abandoned
        // call and the map grows for the life of the process.
        assertEquals("cancelled call must be removed from the pending map", before, runtime.pendingCallCount())

        // A later answer for that id must be dropped rather than resuming anything, and the runtime
        // must still serve new work.
        delay(200)
        assertEquals("""{"result":7}""", runtime.call("sum", """{"a":7,"b":0}"""))
    }

    private fun quote(value: String): String = buildString(value.length + 32) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
        append('"')
    }

    private fun pluginLoadPayload(id: String, key: String): String {
        val code = """
            const { storage, localStorage, sessionStorage } = require('@libs/storage');
            exports.default = {
              id: '$id',
              name: 'Storage test',
              version: '1',
              site: 'https://example.invalid',
              write: () => { storage.set('value', 'stored'); return storage.get('value'); },
              read: () => storage.get('value'),
              readRaw: () => storage.get('value', true),
              expired: () => {
                storage.set('expired', 'old', Date.now() - 1);
                return storage.get('expired');
              },
              snapshots: () => ({ local: localStorage.get(), session: sessionStorage.get() }),
            };
        """.trimIndent()
        return """{"id":"$id","key":"$key","code":${quote(code)}}"""
    }
}
