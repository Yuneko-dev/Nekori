package eu.kanade.tachiyomi.network

import com.sun.net.httpserver.HttpServer
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

class AdditionalCookieInterceptorTest {

    @Test
    fun `plugin cookie is prepended to WebView cookies`() {
        assertEquals(
            "plugin=one; webview=two",
            mergeCookieHeaders("plugin=one", "webview=two"),
        )
    }

    @Test
    fun `blank cookie values are omitted`() {
        assertEquals("webview=two", mergeCookieHeaders("", "webview=two"))
        assertEquals("plugin=one", mergeCookieHeaders("plugin=one", null))
        assertNull(mergeCookieHeaders(" ", null))
    }

    @Test
    fun `network interceptor prepends plugin cookie after cookie jar bridge`() {
        val receivedCookie = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                receivedCookie.set(exchange.requestHeaders.getFirst("Cookie"))
                exchange.sendResponseHeaders(204, -1)
                exchange.close()
            }
            start()
        }
        val cookieJar = object : CookieJar {
            override fun loadForRequest(url: HttpUrl): List<Cookie> = listOf(
                Cookie.Builder()
                    .name("webview")
                    .value("two")
                    .hostOnlyDomain(url.host)
                    .build(),
            )

            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
        }
        val client = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .addNetworkInterceptor(AdditionalCookieInterceptor())
            .build()

        try {
            val request = Request.Builder()
                .url("http://127.0.0.1:${server.address.port}/")
                .tag(AdditionalCookie::class, AdditionalCookie("plugin=one"))
                .build()

            client.newCall(request).execute().close()

            assertEquals("plugin=one; webview=two", receivedCookie.get())
        } finally {
            server.stop(0)
        }
    }
}
