package eu.kanade.tachiyomi.jsruntime

import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.InetAddress
import java.net.ServerSocket

@RunWith(AndroidJUnit4::class)
class JsRuntimeProductionNetworkTest {

    private val networkHelper by lazy { Injekt.get<NetworkHelper>() }
    private val networkClient by lazy { networkHelper.client }
    private val runtime by lazy { Injekt.get<JsRuntime>() }

    @Test
    fun pluginFetchUsesProductionNetworkHelperClient() = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val response = async(Dispatchers.IO) {
                server.accept().use { socket ->
                    val request = socket.getInputStream().bufferedReader()
                    request.readLine()
                    val headers = buildMap {
                        while (true) {
                            val line = request.readLine()
                            if (line.isNullOrEmpty()) break
                            put(
                                line.substringBefore(':').lowercase(),
                                line.substringAfter(':').trim(),
                            )
                        }
                    }
                    socket.getOutputStream().bufferedWriter().use { output ->
                        output.write(
                            "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: text/html; charset=utf-8\r\n" +
                                "Content-Length: 24\r\n" +
                                "Connection: close\r\n\r\n" +
                                "<p>production-client</p>",
                        )
                    }
                    headers
                }
            }

            runtime.start()
            assertSame(networkClient, reactNativeNetworkingClient())

            runtime.call("plugin.load", """{"id":"network.test","code":${quote(PLUGIN_SOURCE)}}""")
            val result = runtime.call(
                "plugin.parseChapter",
                """{"id":"network.test","path":"http://127.0.0.1:${server.localPort}/chapter"}""",
            )

            val requestHeaders = response.await()
            assertEquals(networkHelper.defaultUserAgentProvider(), requestHeaders["user-agent"])
            assertEquals("<p>production-client</p>", result)
        }
    }

    private fun reactNativeNetworkingClient(): Any =
        Class.forName("com.facebook.react.modules.network.OkHttpClientProvider")
            .getMethod("getOkHttpClient")
            .invoke(null)

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

    private companion object {
        val PLUGIN_SOURCE = """
            const { fetchApi } = require('@libs/fetch');
            exports.default = {
              id: 'network.test',
              name: 'Network identity test',
              version: '1',
              site: 'http://127.0.0.1',
              parseChapter: async path => (await fetchApi(path)).text(),
            };
        """.trimIndent()
    }
}
