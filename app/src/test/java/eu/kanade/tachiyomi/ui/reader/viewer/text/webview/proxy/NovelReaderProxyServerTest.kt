package eu.kanade.tachiyomi.ui.reader.viewer.text.webview.proxy

import com.sun.net.httpserver.HttpServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class NovelReaderProxyServerTest {

    private lateinit var upstream: HttpServer
    private lateinit var proxy: NovelReaderProxyServer
    private val client = OkHttpClient()

    @BeforeEach
    fun setUp() {
        upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/echo") { exchange ->
                val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
                val response = listOf(
                    exchange.requestMethod,
                    exchange.requestHeaders.getFirst("Authorization").orEmpty(),
                    exchange.requestHeaders.getFirst("Content-Type").orEmpty(),
                    body,
                ).joinToString("|").toByteArray()
                exchange.responseHeaders.add("X-Upstream", "visible")
                exchange.sendResponseHeaders(201, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            createContext("/binary") { exchange ->
                val response = byteArrayOf(0, 1, 2, 127, -1)
                exchange.responseHeaders.add("Content-Type", "application/octet-stream")
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            start()
        }
        proxy = NovelReaderProxyServer(client, flushCookies = {}).also { it.start() }
    }

    @AfterEach
    fun tearDown() {
        proxy.close()
        upstream.stop(0)
    }

    @Test
    fun `proxy preserves method body forwarded headers status and response headers`() {
        val target = "http://127.0.0.1:${upstream.address.port}/echo"
        val request = Request.Builder()
            .url("${proxy.endpoint}?url=${URLEncoder.encode(target, StandardCharsets.UTF_8)}")
            .header("Origin", "https://plugin.example")
            .header("x-ln-forward-header-authorization", "Bearer token")
            .post("payload".toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            assertEquals(201, response.code)
            assertEquals("POST|Bearer token|application/json; charset=utf-8|payload", response.body.string())
            assertEquals("visible", response.header("X-Upstream"))
            assertEquals("https://plugin.example", response.header("Access-Control-Allow-Origin"))
            assertTrue(
                response.header("Access-Control-Expose-Headers")
                    .orEmpty()
                    .contains("X-Upstream", ignoreCase = true),
            )
        }
    }

    @Test
    fun `forwarded content type controls the upstream request body`() {
        val target = "http://127.0.0.1:${upstream.address.port}/echo"
        val request = Request.Builder()
            .url("${proxy.endpoint}?url=${URLEncoder.encode(target, StandardCharsets.UTF_8)}")
            .header("x-ln-forward-header-content-type", "application/json")
            .post("payload".toRequestBody("text/plain".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            assertEquals(201, response.code)
            assertEquals("POST||application/json|payload", response.body.string())
        }
    }

    @Test
    fun `options is handled locally as browser preflight`() {
        val request = Request.Builder()
            .url("${proxy.endpoint}?url=https%3A%2F%2Fexample.com")
            .header("Origin", "https://plugin.example")
            .header("Access-Control-Request-Headers", "authorization, content-type")
            .method("OPTIONS", null)
            .build()

        client.newCall(request).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals(
                "GET, POST, PUT, DELETE, OPTIONS, PATCH",
                response.header("Access-Control-Allow-Methods"),
            )
            assertEquals(
                "authorization, content-type",
                response.header("Access-Control-Allow-Headers"),
            )
        }
    }

    @Test
    fun `all LNReader fetch methods are forwarded`() {
        val target = "http://127.0.0.1:${upstream.address.port}/echo"
        listOf("GET", "HEAD", "PUT", "PATCH", "DELETE").forEach { method ->
            val body = if (method in setOf("PUT", "PATCH", "DELETE")) {
                method.lowercase().toRequestBody("text/plain".toMediaType())
            } else {
                null
            }
            val request = Request.Builder()
                .url("${proxy.endpoint}?url=${URLEncoder.encode(target, StandardCharsets.UTF_8)}")
                .method(method, body)
                .build()

            client.newCall(request).execute().use { response ->
                assertEquals(201, response.code, method)
                if (method != "HEAD") {
                    assertTrue(response.body.string().startsWith("$method|"), method)
                }
            }
        }
    }

    @Test
    fun `unrecognized route cannot use the proxy`() {
        val privateRoute = proxy.endpoint.substringBeforeLast('/') + "/other?url=https%3A%2F%2Fexample.com"

        client.newCall(Request.Builder().url(privateRoute).build()).execute().use { response ->
            assertEquals(404, response.code)
        }
    }

    @Test
    fun `binary response is streamed without text encoding`() {
        val target = "http://127.0.0.1:${upstream.address.port}/binary"
        val request = Request.Builder()
            .url("${proxy.endpoint}?url=${URLEncoder.encode(target, StandardCharsets.UTF_8)}")
            .build()

        client.newCall(request).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals(
                listOf<Byte>(0, 1, 2, 127, -1),
                response.body.bytes().toList(),
            )
        }
    }

    @Test
    fun `methods outside the LNReader fetch surface are rejected`() {
        val target = "http://127.0.0.1:${upstream.address.port}/echo"
        val request = Request.Builder()
            .url("${proxy.endpoint}?url=${URLEncoder.encode(target, StandardCharsets.UTF_8)}")
            .method("TRACE", null)
            .build()

        client.newCall(request).execute().use { response ->
            assertEquals(400, response.code)
            assertEquals("Unsupported method", response.body.string())
        }
    }

    @Test
    fun `closing the proxy cancels an upstream call still in progress`() {
        val upstreamStarted = CountDownLatch(1)
        val upstreamCanceled = CountDownLatch(1)
        val blockingClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                upstreamStarted.countDown()
                while (!chain.call().isCanceled()) {
                    Thread.sleep(10)
                }
                upstreamCanceled.countDown()
                throw java.io.IOException("Canceled")
            }
            .build()
        val localProxy = NovelReaderProxyServer(blockingClient, flushCookies = {}).also { it.start() }
        val target = "http://127.0.0.1:${upstream.address.port}/echo"
        val request = Request.Builder()
            .url("${localProxy.endpoint}?url=${URLEncoder.encode(target, StandardCharsets.UTF_8)}")
            .build()

        val result = CompletableFuture.supplyAsync {
            runCatching { client.newCall(request).execute().use { it.code } }
        }
        assertTrue(upstreamStarted.await(2, TimeUnit.SECONDS))

        localProxy.close()

        assertTrue(upstreamCanceled.await(2, TimeUnit.SECONDS))
        result.get(2, TimeUnit.SECONDS)
    }

    @Test
    fun `closing the proxy does not close a streaming response from another thread`() {
        val streamingReadStarted = CountDownLatch(1)
        val releaseStream = CountDownLatch(1)
        val readingThread = AtomicReference<Thread>()
        val streamingClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val source = object : Source {
                    private var firstRead = true

                    override fun read(sink: Buffer, byteCount: Long): Long {
                        readingThread.compareAndSet(null, Thread.currentThread())
                        if (firstRead) {
                            firstRead = false
                            sink.writeByte(1)
                            return 1
                        }
                        streamingReadStarted.countDown()
                        releaseStream.await(5, TimeUnit.SECONDS)
                        return -1
                    }

                    override fun timeout() = Timeout.NONE

                    override fun close() {
                        check(readingThread.get() == Thread.currentThread()) {
                            "Streaming response was closed from a non-reader thread"
                        }
                    }
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        object : ResponseBody() {
                            override fun contentType() = null
                            override fun contentLength() = -1L
                            override fun source() = source.buffer()
                        },
                    )
                    .build()
            }
            .build()
        val localProxy = NovelReaderProxyServer(streamingClient, flushCookies = {}).also { it.start() }
        val request = Request.Builder()
            .url("${localProxy.endpoint}?url=https%3A%2F%2Fexample.com%2Fstream")
            .build()
        val result = CompletableFuture.runAsync {
            runCatching {
                client.newCall(request).execute().use { response ->
                    response.body.byteStream().readBytes()
                }
            }
        }

        try {
            assertTrue(streamingReadStarted.await(2, TimeUnit.SECONDS))
            assertDoesNotThrow { localProxy.close() }
        } finally {
            releaseStream.countDown()
            localProxy.close()
        }
        result.get(2, TimeUnit.SECONDS)
    }
}
