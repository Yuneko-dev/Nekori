package eu.kanade.tachiyomi.ui.reader.viewer.text.webview.proxy

import com.hippo.unifile.UniFile
import com.sun.net.httpserver.HttpServer
import eu.kanade.tachiyomi.network.AdditionalCookie
import io.mockk.every
import io.mockk.mockk
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSink
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
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
                exchange.responseHeaders.add("Set-Cookie", "upstream=secret")
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
            assertEquals(null, response.header("Set-Cookie"))
            assertEquals("https://plugin.example", response.header("Access-Control-Allow-Origin"))
            assertTrue(
                response.header("Access-Control-Expose-Headers")
                    .orEmpty()
                    .contains("X-Upstream", ignoreCase = true),
            )
        }
    }

    @Test
    fun `proxy forwards only the explicitly requested cookie`() {
        val forwardedCookie = AtomicReference<String?>()
        val proxyClient = client.newBuilder()
            .addInterceptor { chain ->
                forwardedCookie.set(chain.request().tag(AdditionalCookie::class)?.value)
                chain.proceed(chain.request())
            }
            .build()
        proxy.close()
        proxy = NovelReaderProxyServer(proxyClient, flushCookies = {}).also { it.start() }
        val target = "http://127.0.0.1:${upstream.address.port}/binary"

        fun request(explicitCookie: String? = null) {
            val request = Request.Builder()
                .url("${proxy.endpoint}?url=${URLEncoder.encode(target, StandardCharsets.UTF_8)}")
                .header("Cookie", "loopback=blocked")
                .apply { explicitCookie?.let { header("x-ln-forward-header-cookie", it) } }
                .build()
            client.newCall(request).execute().close()
        }

        request("plugin=allowed")
        assertEquals("plugin=allowed", forwardedCookie.get())
        request()
        assertEquals(null, forwardedCookie.get())
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
    fun `proxy reports request and response activity`() {
        val activity = AtomicLong()
        proxy.close()
        proxy = NovelReaderProxyServer(
            client,
            flushCookies = {},
            onNetworkActivity = { activity.incrementAndGet() },
        ).also { it.start() }
        val target = "http://127.0.0.1:${upstream.address.port}/binary"
        val request = Request.Builder()
            .url("${proxy.endpoint}?url=${URLEncoder.encode(target, StandardCharsets.UTF_8)}")
            .build()

        client.newCall(request).execute().use { it.body.bytes() }

        assertTrue(activity.get() >= 2)
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
    fun `reader mode does not expose sink routes`() {
        val request = Request.Builder()
            .url("${proxy.sinkEndpoint}/sink?container=mp4")
            .header("Origin", SINK_ORIGIN)
            .post(ByteArray(0).toRequestBody())
            .build()

        client.newCall(request).execute().use { response ->
            assertEquals(404, response.code)
        }
    }

    @Test
    fun `sink preflight pins its origin`(@TempDir tempDir: Path) {
        useSink(tempDir)

        val validRequest = Request.Builder()
            .url("${proxy.sinkEndpoint}/sink")
            .header("Origin", SINK_ORIGIN)
            .method("OPTIONS", null)
            .build()
        client.newCall(validRequest).execute().use { response ->
            // 200, not 204: NanoHTTPD sets Content-Length on every fixed-length response and RFC 7230
            // forbids that on a 204, which left Chromium dropping the connection intermittently.
            assertEquals(200, response.code)
            assertEquals(SINK_ORIGIN, response.header("Access-Control-Allow-Origin"))
            assertEquals("86400", response.header("Access-Control-Max-Age"))
        }

        val foreignRequest = validRequest.newBuilder().header("Origin", "https://evil.example").build()
        client.newCall(foreignRequest).execute().use { response ->
            assertEquals(403, response.code)
            assertEquals(null, response.header("Access-Control-Allow-Origin"))
        }
    }

    @Test
    fun `sink writes chunks and atomically commits the declared container`(@TempDir tempDir: Path) {
        val activity = AtomicLong()
        val sink = useSink(tempDir) { activity.incrementAndGet() }
        ready()

        assertEquals(200, put("abc".toByteArray()))
        assertEquals(200, put(byteArrayOf(0, 1, 2)))
        assertEquals(200, commit())

        assertEquals(listOf<Byte>(97, 98, 99, 0, 1, 2), Files.readAllBytes(tempDir.resolve("video.mp4")).toList())
        assertTrue(activity.get() > 0)
        assertEquals("video.mp4", sink.committedFile?.name)
        assertEquals(409, put(byteArrayOf(3)))
    }

    @Test
    fun `sink requires fixed length writes`(@TempDir tempDir: Path) {
        useSink(tempDir)
        ready()

        assertEquals(411, putStreaming(byteArrayOf(1)))
    }

    @Test
    fun `sink rejects missing invalid or duplicate containers`(@TempDir tempDir: Path) {
        useSink(tempDir)

        mapOf("" to 409, "?container=../mp4" to 400, "?container=mp4&container=ts" to 400)
            .forEach { (query, status) ->
                assertEquals(status, readyStatus(query), query)
            }
    }

    @Test
    fun `only one concurrent write is accepted`(@TempDir tempDir: Path) {
        useSink(tempDir)
        ready()
        val firstByteSent = CountDownLatch(1)
        val releaseFirstWrite = CountDownLatch(1)
        val slowBody = object : RequestBody() {
            override fun contentType() = OCTET_STREAM
            override fun contentLength() = 2L

            override fun writeTo(sink: BufferedSink) {
                sink.writeByte(1)
                sink.flush()
                firstByteSent.countDown()
                releaseFirstWrite.await(5, TimeUnit.SECONDS)
                sink.writeByte(2)
            }
        }
        val first = CompletableFuture.supplyAsync { put(slowBody) }
        assertTrue(firstByteSent.await(2, TimeUnit.SECONDS))

        val second = put(byteArrayOf(3))
        releaseFirstWrite.countDown()
        val statuses = listOf(first.get(2, TimeUnit.SECONDS), second).sorted()

        assertEquals(listOf(200, 409), statuses)
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

    private fun useSink(tempDir: Path, onNetworkActivity: () -> Unit = {}): NovelReaderProxyServer.Sink {
        proxy.close()
        val sink = NovelReaderProxyServer.Sink(mockDirectory(tempDir), SINK_ORIGIN)
        proxy = NovelReaderProxyServer(
            client,
            flushCookies = {},
            sink = sink,
            onNetworkActivity = onNetworkActivity,
        ).also { it.start() }
        return sink
    }

    private fun ready() = assertEquals(200, readyStatus("?container=mp4"))

    private fun readyStatus(query: String): Int {
        val request = Request.Builder()
            .url("${proxy.sinkEndpoint}/sink$query")
            .header("Origin", SINK_ORIGIN)
            .post(ByteArray(0).toRequestBody())
            .build()
        return client.newCall(request).execute().use { it.code }
    }

    private fun put(bytes: ByteArray): Int = put(bytes.toRequestBody(OCTET_STREAM))

    private fun put(body: RequestBody): Int {
        val request = Request.Builder()
            .url("${proxy.sinkEndpoint}/sink")
            .header("Origin", SINK_ORIGIN)
            .put(body)
            .build()
        return client.newCall(request).execute().use { it.code }
    }

    private fun putStreaming(bytes: ByteArray): Int =
        put(
            object : RequestBody() {
                override fun contentType() = OCTET_STREAM
                override fun writeTo(sink: BufferedSink) = sink.write(bytes).let {}
            },
        )

    private fun commit(): Int {
        val request = Request.Builder()
            .url("${proxy.sinkEndpoint}/sink")
            .header("Origin", SINK_ORIGIN)
            .post(ByteArray(0).toRequestBody())
            .build()
        return client.newCall(request).execute().use { it.code }
    }

    private fun mockDirectory(path: Path): UniFile = mockk {
        every { listFiles() } answers {
            Files.list(path).use { files -> files.map(::mockFile).toList().toTypedArray() }
        }
        every { createFile(any()) } answers {
            val child = path.resolve(firstArg<String>())
            if (!Files.exists(child)) Files.createFile(child)
            mockFile(child)
        }
    }

    private fun mockFile(initialPath: Path): UniFile {
        val path = AtomicReference(initialPath)
        return mockk {
            every { name } answers { path.get().fileName.toString() }
            every { delete() } answers { Files.deleteIfExists(path.get()) }
            every { openOutputStream() } answers {
                Files.newOutputStream(
                    path.get(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                )
            }
            every { renameTo(any()) } answers {
                val destination = path.get().resolveSibling(firstArg<String>())
                runCatching {
                    Files.move(path.get(), destination)
                    path.set(destination)
                }.isSuccess
            }
        }
    }

    companion object {
        private const val SINK_ORIGIN = "https://tsundoku.reader"
        private val OCTET_STREAM = "application/octet-stream".toMediaType()
    }
}
