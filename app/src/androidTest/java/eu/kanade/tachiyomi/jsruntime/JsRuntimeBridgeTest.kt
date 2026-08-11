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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
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
    private val networkHelper by lazy { Injekt.get<NetworkHelper>() }
    private val networkClient by lazy { networkHelper.jsPluginClient }

    private fun createRuntime() =
        JsRuntime(context, networkClient, networkHelper::defaultUserAgentProvider)

    @Test
    fun startReturnsOnlyWhenJavaScriptHasSubscribed() = runBlocking {
        val runtime = createRuntime()

        runtime.start()

        // HOST_STARTED would mean the bundle evaluated. JS_READY means JS is actually listening —
        // the distinction that stops a command being emitted into a void.
        assertEquals(JsRuntimeState.JS_READY, runtime.state)
    }

    @Test
    fun callsIntoJavaScriptAndGetsTheResultBack() = runBlocking {
        val runtime = createRuntime()

        val json = runtime.call("sum", """{"a":2,"b":40}""")

        assertEquals("""{"result":42}""", json)
        assertEquals("pending map must drain on success", 0, runtime.pendingCallCount())
    }

    @Test
    fun javaScriptThrowBecomesKotlinException() = runBlocking {
        val runtime = createRuntime()

        try {
            runtime.call("boom", """{"message":"plugin exploded"}""")
            fail("a JS throw must surface as JsRuntimeException")
        } catch (e: JsRuntimeException) {
            assertEquals("plugin exploded", e.message)
            assertTrue(e.jsStack, e.jsStack.contains("plugin exploded"))
            assertTrue(e.stackTraceToString(), e.stackTraceToString().contains("JavaScript stack:"))
        }
        assertEquals("pending map must drain on rejection", 0, runtime.pendingCallCount())
    }

    @Test
    fun pluginThrowKeepsThePluginSourceInTheJavaScriptStack() = runBlocking {
        val runtime = createRuntime()
        val code = """
            function explodeFromPlugin() {
              throw new Error('plugin stack exploded');
            }
            exports.default = {
              id: 'stack.test',
              name: 'Stack test',
              version: '1',
              site: 'https://example.invalid',
              parseNovel: async () => explodeFromPlugin(),
            };
        """.trimIndent()

        runtime.call("plugin.load", """{"id":"stack.test","code":${quote(code)}}""")

        try {
            runtime.call("plugin.parseNovel", """{"id":"stack.test","path":"/novel"}""")
            fail("Expected plugin error")
        } catch (e: JsRuntimeException) {
            assertEquals("plugin stack exploded", e.message)
            assertTrue(e.jsStack, e.jsStack.contains("explodeFromPlugin"))
            assertTrue(e.jsStack, e.jsStack.contains("lnreader-plugin://stack.test.js"))
        }
    }

    @Test
    fun unknownMethodFailsLoudly() = runBlocking {
        val runtime = createRuntime()

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
        val runtime = createRuntime()

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
        val runtime = createRuntime()
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
    fun inspectingAPluginDoesNotRequireKnowingItsId() = runBlocking {
        val runtime = createRuntime()
        val code = """
            exports.default = {
              id: 'actual.test',
              name: 'Inspection test',
              version: '1',
              site: 'https://example.invalid',
            };
        """.trimIndent()

        val inspected = runtime.call(
            "plugin.load",
            """{"id":"unknown.test","validateId":false,"code":${quote(code)}}""",
        )
        assertEquals("actual.test", Json.parseToJsonElement(inspected).jsonObject["id"]?.jsonPrimitive?.content)

        try {
            runtime.call(
                "plugin.load",
                """{"id":"unknown.test","key":"strict.test","code":${quote(code)}}""",
            )
            fail("normal plugin loads must still reject a mismatched id")
        } catch (error: JsRuntimeException) {
            assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("Plugin id mismatch"))
        }
    }

    @Test
    fun missingParsePageFailsWithTheOptionalMethodContract() = runBlocking {
        val runtime = createRuntime()
        val code = """
            exports.default = {
              id: 'optional-page.test',
              name: 'Optional page test',
              version: '1',
              site: 'https://example.invalid',
            };
        """.trimIndent()

        runtime.call("plugin.load", """{"id":"optional-page.test","code":${quote(code)}}""")

        try {
            runtime.call(
                "plugin.parsePage",
                """{"id":"optional-page.test","path":"/novel","page":"2"}""",
            )
            fail("Expected a plugin without parsePage to reject")
        } catch (e: JsRuntimeException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("does not implement parsePage"))
        }
    }

    @Test
    fun parseNovelNormalizesPagedChaptersWithoutCallingParsePage() = runBlocking {
        val runtime = createRuntime()
        val code = """
            exports.default = {
              id: 'paged.test',
              name: 'Paged test',
              version: '1',
              site: 'https://example.invalid',
              parsePageCalls: 0,
              parseNovel: async () => ({
                name: 'Novel',
                path: '/novel',
                totalPages: 3,
                chapters: [{
                  name: 'Chapter 1',
                  path: '/chapter-1',
                  scanlator: [' Team A ', '', 'Team B'],
                }],
              }),
              parsePage: async function () {
                this.parsePageCalls += 1;
                return { chapters: [] };
              },
            };
        """.trimIndent()

        runtime.call("plugin.load", """{"id":"paged.test","code":${quote(code)}}""")
        val result = Json.parseToJsonElement(
            runtime.call("plugin.parseNovel", """{"id":"paged.test","path":"/novel"}"""),
        ).jsonObject

        assertEquals("PAGED", result.getValue("__tsundokuLayout").jsonPrimitive.content)
        assertEquals("3", result.getValue("totalPages").jsonPrimitive.content)
        val chapter = result.getValue("chapters").jsonArray.single().jsonObject
        assertEquals("1", chapter.getValue("page").jsonPrimitive.content)
        assertEquals("Team A, Team B", chapter.getValue("scanlator").jsonPrimitive.content)
        assertEquals("0", runtime.call("plugin.eval", """{"id":"paged.test","expression":"plugin.parsePageCalls"}"""))
    }

    @Test
    fun parsePageForcesEveryChapterOntoTheCanonicalRequestedPage() = runBlocking {
        val runtime = createRuntime()
        val code = """
            exports.default = {
              id: 'page-result.test',
              name: 'Page result test',
              version: '1',
              site: 'https://example.invalid',
              parsePage: async () => ({
                chapters: [
                  { name: 'Chapter 1', path: '/chapter-1' },
                  { name: 'Chapter 2', path: '/chapter-2', page: 'wrong' },
                ],
              }),
            };
        """.trimIndent()

        runtime.call("plugin.load", """{"id":"page-result.test","code":${quote(code)}}""")
        val result = Json.parseToJsonElement(
            runtime.call(
                "plugin.parsePage",
                """{"id":"page-result.test","path":"/novel","page":"2"}""",
            ),
        ).jsonObject

        result.getValue("chapters").jsonArray.forEach { chapter ->
            assertEquals("2", chapter.jsonObject.getValue("page").jsonPrimitive.content)
        }
    }

    @Test
    fun parseNovelRejectsPagedPluginWithoutValidTotalPages() = runBlocking {
        val runtime = createRuntime()
        val code = """
            exports.default = {
              id: 'invalid-paged.test',
              name: 'Invalid paged test',
              version: '1',
              site: 'https://example.invalid',
              parseNovel: async () => ({ name: 'Novel', path: '/novel', chapters: [] }),
              parsePage: async () => ({ chapters: [] }),
            };
        """.trimIndent()

        runtime.call("plugin.load", """{"id":"invalid-paged.test","code":${quote(code)}}""")
        try {
            runtime.call("plugin.parseNovel", """{"id":"invalid-paged.test","path":"/novel"}""")
            fail("Expected invalid totalPages to reject")
        } catch (e: JsRuntimeException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("invalid totalPages"))
        }
    }

    @Test
    fun parseNovelClassifiesVolumeAndNormalizesMissingPage() = runBlocking {
        val runtime = createRuntime()
        val code = """
            exports.default = {
              id: 'volume.test',
              name: 'Volume test',
              version: '1',
              site: 'https://example.invalid',
              parseNovel: async () => ({
                name: 'Novel',
                path: '/novel',
                totalPages: 1,
                chapters: [
                  { name: 'Chapter 1', path: '/chapter-1', page: 'Volume 1' },
                  { name: 'Chapter 2', path: '/chapter-2' },
                ],
              }),
            };
        """.trimIndent()

        runtime.call("plugin.load", """{"id":"volume.test","code":${quote(code)}}""")
        val result = Json.parseToJsonElement(
            runtime.call("plugin.parseNovel", """{"id":"volume.test","path":"/novel"}"""),
        ).jsonObject

        assertEquals("VOLUME", result.getValue("__tsundokuLayout").jsonPrimitive.content)
        assertEquals("0", result.getValue("totalPages").jsonPrimitive.content)
        val chapters = result.getValue("chapters").jsonArray
        assertEquals("Volume 1", chapters[0].jsonObject.getValue("page").jsonPrimitive.content)
        assertEquals("Default", chapters[1].jsonObject.getValue("page").jsonPrimitive.content)
    }

    @Test
    fun parseNovelRejectsNonCanonicalPagedChapterNumber() = runBlocking {
        val runtime = createRuntime()
        val code = """
            exports.default = {
              id: 'invalid-page.test',
              name: 'Invalid page test',
              version: '1',
              site: 'https://example.invalid',
              parseNovel: async () => ({
                name: 'Novel',
                path: '/novel',
                totalPages: 2,
                chapters: [{ name: 'Chapter', path: '/chapter', page: '01' }],
              }),
              parsePage: async () => ({ chapters: [] }),
            };
        """.trimIndent()

        runtime.call("plugin.load", """{"id":"invalid-page.test","code":${quote(code)}}""")
        try {
            runtime.call("plugin.parseNovel", """{"id":"invalid-page.test","path":"/novel"}""")
            fail("Expected non-canonical page to reject")
        } catch (e: JsRuntimeException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("positive integer string"))
        }
    }

    @Test
    fun resolveUrlReadsAGetterSiteAtCallTime() = runBlocking {
        val runtime = createRuntime()
        val code = """
            let selectedDomain = 'https://first.invalid';
            exports.default = {
              id: 'getter-site.test',
              name: 'Getter site test',
              version: '1',
              get site() { return selectedDomain; },
              selectDomain: value => { selectedDomain = value; },
              resolveUrl(path, isNovel) {
                return this.site + (isNovel ? '/novel' : '') + path;
              },
            };
        """.trimIndent()

        runtime.call("plugin.load", """{"id":"getter-site.test","code":${quote(code)}}""")
        runtime.call(
            "plugin.eval",
            """{"id":"getter-site.test","expression":"plugin.selectDomain('https://second.invalid')"}""",
        )

        assertEquals(
            """{"url":"https://second.invalid/novel/book"}""",
            runtime.call(
                "plugin.resolveUrl",
                """{"id":"getter-site.test","path":"/book","isNovel":true}""",
            ),
        )
    }

    @Test
    fun resolveUrlFallsBackToTheCurrentGetterSiteAndRawPath() = runBlocking {
        val runtime = createRuntime()
        val code = """
            exports.default = {
              id: 'fallback-site.test',
              name: 'Fallback site test',
              version: '1',
              get site() { return 'https://fallback.invalid/'; },
            };
        """.trimIndent()

        runtime.call("plugin.load", """{"id":"fallback-site.test","code":${quote(code)}}""")

        assertEquals(
            """{"url":"https://fallback.invalid//book"}""",
            runtime.call(
                "plugin.resolveUrl",
                """{"id":"fallback-site.test","path":"/book","isNovel":true}""",
            ),
        )
    }

    @Test
    fun resolveUrlReturnsTheRawPathWhenThePluginResolverFails() = runBlocking {
        val runtime = createRuntime()
        val code = """
            exports.default = {
              id: 'failing-resolver.test',
              name: 'Failing resolver test',
              version: '1',
              site: 'https://fallback.invalid',
              resolveUrl() { throw new Error('resolver failed'); },
            };
        """.trimIndent()

        runtime.call("plugin.load", """{"id":"failing-resolver.test","code":${quote(code)}}""")

        assertEquals(
            """{"url":"/book"}""",
            runtime.call(
                "plugin.resolveUrl",
                """{"id":"failing-resolver.test","path":"/book","isNovel":true}""",
            ),
        )
    }

    @Test
    fun secureRandomFillsTheRequestedBuffer() = runBlocking {
        val runtime = createRuntime()

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
        val runtime = createRuntime()
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
    fun pluginUtilsUseTheCurrentDefaultUserAgent() = runBlocking {
        val expected = networkHelper.defaultUserAgentProvider()
        val runtime = createRuntime()
        val code = """
            const { getUserAgent } = require('@libs/utils');
            exports.default = {
              id: 'user-agent.test',
              name: 'User agent test',
              version: '1',
              site: 'https://example.invalid',
              probe: () => getUserAgent(),
            };
        """.trimIndent()

        runtime.call("plugin.load", """{"id":"user-agent.test","code":${quote(code)}}""")

        assertEquals(
            quote(expected),
            runtime.call(
                "plugin.eval",
                """{"id":"user-agent.test","expression":"plugin.probe()"}""",
            ),
        )
    }

    @Test
    fun pluginCookieModuleUsesTheSharedAndroidCookieStore() = runBlocking {
        val runtime = createRuntime()
        val suffix = System.nanoTime().toString()
        val pluginId = "cookie.test.$suffix"
        val url = "https://cookie-$suffix.example.invalid/"
        val code = """
            const cookies = require('@libs/cookie');
            exports.default = {
              id: '$pluginId',
              name: 'Cookie test',
              version: '1',
              site: '$url',
              probe: async () => {
                const fromResponse = await cookies.setFromResponse(
                  '$url',
                  'responseCookie=responseValue; Path=/',
                );
                const fromObject = await cookies.set(
                  '$url',
                  {
                    name: 'objectCookie',
                    value: 'objectValue',
                    path: '/',
                    secure: true,
                    sameSite: 'lax',
                    maxAge: 60,
                  },
                );
                await cookies.flush();
                const stored = await cookies.get('$url');
                const storedAsArray = await cookies.getAsArray('$url');
                return {
                  fromResponse,
                  fromObject,
                  responseValue: stored.responseCookie?.value,
                  objectValue: stored.objectCookie?.value,
                  arrayValues: storedAsArray.map(cookie => cookie.value).sort().join(','),
                  header: await cookies.getCookieHeader('$url'),
                  fullApi: [
                    cookies.getAll,
                    cookies.getAllAsArray,
                    cookies.clearAll,
                    cookies.clearAllStores,
                    cookies.clearByName,
                    cookies.getAsArray,
                    cookies.getCookieHeader,
                    cookies.getFromResponse,
                    cookies.removeSessionCookies,
                  ].every(value => typeof value === 'function'),
                };
              },
              cleanup: async () => {
                await cookies.setFromResponse(
                  '$url',
                  'responseCookie=; Path=/; Max-Age=0',
                );
                await cookies.setFromResponse(
                  '$url',
                  'objectCookie=; Path=/; Max-Age=0',
                );
              },
            };
        """.trimIndent()

        runtime.call("plugin.load", """{"id":"$pluginId","code":${quote(code)}}""")
        try {
            val result = runtime.call(
                "plugin.eval",
                """{"id":"$pluginId","expression":"plugin.probe()"}""",
            )

            assertTrue(result, result.contains("\"fromResponse\":true"))
            assertTrue(result, result.contains("\"fromObject\":true"))
            assertTrue(result, result.contains("\"responseValue\":\"responseValue\""))
            assertTrue(result, result.contains("\"objectValue\":\"objectValue\""))
            assertTrue(result, result.contains("\"arrayValues\":\"objectValue,responseValue\""))
            assertTrue(result, result.contains("responseCookie=responseValue"))
            assertTrue(result, result.contains("objectCookie=objectValue"))
            assertTrue(result, result.contains("\"fullApi\":true"))

            val sharedCookies = networkHelper.cookieJar.get(url.toHttpUrl()).associate { it.name to it.value }
            assertEquals("responseValue", sharedCookies["responseCookie"])
            assertEquals("objectValue", sharedCookies["objectCookie"])
        } finally {
            runCatching {
                runtime.call(
                    "plugin.eval",
                    """{"id":"$pluginId","expression":"plugin.cleanup()"}""",
                )
            }
        }
    }

    @Test
    fun pluginStoragePersistsAndStaysPluginScoped() = runBlocking {
        val runtime = createRuntime()
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
    fun pluginWebStorageSnapshotsPersistAndRefreshTheLoadedModule() = runBlocking {
        val runtime = createRuntime()
        val pluginId = "web-storage.test.${System.nanoTime()}"
        val code = """
            const { localStorage, sessionStorage } = require('@libs/storage');
            exports.default = {
              id: '$pluginId',
              name: 'Web storage test',
              version: '1',
              site: 'https://example.invalid',
              webStorageUtilized: true,
              imageRequestInit: {
                method: 'POST',
                headers: { Referer: 'https://example.invalid/' },
                body: 'image=1',
              },
              read: () => ({
                local: localStorage.get(),
                session: sessionStorage.get(),
              }),
            };
        """.trimIndent()

        val loaded = runtime.call("plugin.load", """{"id":"$pluginId","code":${quote(code)}}""")
        assertTrue(loaded, loaded.contains("\"webStorageUtilized\":true"))
        assertTrue(loaded, loaded.contains("\"method\":\"POST\""))
        assertTrue(loaded, loaded.contains("\"Referer\":\"https://example.invalid/\""))
        runtime.call(
            "plugin.webStorageSet",
            """
                {
                  "id":"$pluginId",
                  "localStorage":{"token":"local"},
                  "sessionStorage":{"nonce":"session"}
                }
            """.trimIndent(),
        )
        assertEquals(
            """{"local":{"token":"local"},"session":{"nonce":"session"}}""",
            runtime.call(
                "plugin.eval",
                """{"id":"$pluginId","expression":"plugin.read()"}""",
            ),
        )

        runtime.call("plugin.unload", """{"id":"$pluginId"}""")
        runtime.call("plugin.load", """{"id":"$pluginId","code":${quote(code)}}""")
        assertEquals(
            """{"local":{"token":"local"},"session":{"nonce":"session"}}""",
            runtime.call(
                "plugin.eval",
                """{"id":"$pluginId","expression":"plugin.read()"}""",
            ),
        )
    }

    @Test
    fun pluginSettingWritesThroughThePluginStorageContract() = runBlocking {
        val runtime = createRuntime()
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
        val runtime = createRuntime()
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
        val runtime = createRuntime()
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
