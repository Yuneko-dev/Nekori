package eu.kanade.tachiyomi.ui.reader.viewer.text.webview.proxy

import android.webkit.CookieManager
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.network.AdditionalCookie
import fi.iki.elonen.NanoHTTPD
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class NovelReaderProxyServer(
    private val client: OkHttpClient,
    private val flushCookies: () -> Unit = { CookieManager.getInstance().flush() },
    private val sink: Sink? = null,
    private val onNetworkActivity: () -> Unit = {},
) : NanoHTTPD(HOST, 0), Closeable {

    private val baseRoute = "/${randomToken()}"
    private val proxyRoute = "$baseRoute/proxy"
    private val sinkRoute = "$baseRoute/sink"
    private val lifecycleLock = Any()
    private val activeCalls = mutableSetOf<Call>()
    private val closed = AtomicBoolean()
    private val sinkLock = ReentrantLock()
    private val sinkDrained = sinkLock.newCondition()
    private var sinkState = SinkState.IDLE
    private var sinkSession: SinkSession? = null

    val endpoint: String
        get() = "http://$HOST:$listeningPort$proxyRoute"

    val sinkEndpoint: String
        get() = "http://$HOST:$listeningPort$baseRoute"

    override fun start() {
        check(!closed.get()) { "Proxy server is closed" }
        if (!wasStarted()) super.start(SOCKET_READ_TIMEOUT, false)
    }

    override fun serve(session: IHTTPSession): Response {
        if (session.uri == sinkRoute) return serveSink(session)
        if (session.uri != proxyRoute) return response(Response.Status.NOT_FOUND, "Not found")
        if (session.method == Method.OPTIONS) return proxyPreflight(session)

        return runCatching { proxy(session) }
            .getOrElse {
                cors(
                    session,
                    newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Network request failed"),
                )
            }
    }

    private fun serveSink(request: IHTTPSession): Response {
        val sink = this.sink ?: return response(Response.Status.NOT_FOUND, "Not found")
        if (request.headers["origin"] != sink.origin) {
            return closeConnection(response(Response.Status.FORBIDDEN, "Forbidden"))
        }
        if (request.method == Method.OPTIONS) return sinkPreflight()

        return runCatching {
            when (request.method) {
                Method.PUT -> writeSink(request)
                Method.POST -> if ("container" in request.parameters) ready(request) else commit()
                Method.DELETE -> deleteSink()
                else -> methodNotAllowed()
            }
        }.getOrElse {
            abortSink()
            sinkCors(response(Response.Status.INTERNAL_ERROR, "Sink request failed"))
        }
    }

    private fun ready(request: IHTTPSession): Response {
        val sink = checkNotNull(sink)
        val container = request.parameters["container"]
            ?.singleOrNull()
            ?.lowercase()
            ?.takeIf(CONTAINER_PATTERN::matches)
            ?: return sinkBadRequest("Invalid container")

        sinkLock.withLock {
            if (sinkState != SinkState.IDLE) return sinkConflict()

            val partName = "video.$container.part"
            val opened = runCatching {
                sink.directory.listFiles()
                    .orEmpty()
                    .filter { it.name?.startsWith("video.") == true && it.name?.endsWith(".part") == true }
                    .forEach { check(it.delete()) { "Unable to remove stale sink file" } }
                val part = checkNotNull(sink.directory.createFile(partName)) { "Unable to create sink file" }
                // A raw SAF stream turns every copy-loop write into a round trip through the
                // DocumentsProvider; at hundreds of megabytes that dominates the download.
                part to BufferedOutputStream(part.openOutputStream(), SINK_WRITE_BUFFER_BYTES)
            }.getOrElse {
                sinkState = SinkState.ABORTED
                return sinkCors(response(Response.Status.INTERNAL_ERROR, "Unable to open sink"))
            }

            sinkSession = SinkSession(container, opened.first, opened.second)
            sinkState = SinkState.OPEN
        }
        return sinkCors(response(Response.Status.OK))
    }

    private fun writeSink(request: IHTTPSession): Response {
        if (request.headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
            return sinkCors(response(Response.Status.LENGTH_REQUIRED, "Content-Length required"))
        }
        val length = request.headers["content-length"]?.toLongOrNull()?.takeIf { it >= 0 }
            ?: return sinkCors(response(Response.Status.LENGTH_REQUIRED, "Content-Length required"))

        val current = sinkLock.withLock {
            if (sinkState != SinkState.OPEN) return sinkConflict()
            val session = checkNotNull(sinkSession)
            if (session.activeWrites != 0) return sinkConflict()
            session.activeWrites++
            session
        }

        val failure = runCatching { copyExactly(request.inputStream, current.stream, length) }.exceptionOrNull()

        val accepted = sinkLock.withLock {
            current.activeWrites--
            sinkDrained.signalAll()
            failure == null && sinkState == SinkState.OPEN
        }
        if (failure != null) {
            abortSink()
            return sinkCors(response(Response.Status.INTERNAL_ERROR, "Unable to write sink"))
        }
        return if (accepted) sinkCors(response(Response.Status.OK)) else sinkConflict()
    }

    private fun commit(): Response {
        val current = sinkLock.withLock {
            if (sinkState != SinkState.OPEN) return sinkConflict()
            val session = checkNotNull(sinkSession)
            if (session.activeWrites != 0) return sinkConflict()
            sinkState = SinkState.COMMITTING
            session.finalizationActive = true
            session
        }

        val finalizationError = runCatching {
            current.stream.flush()
            current.stream.close()
        }.exceptionOrNull()

        val result = sinkLock.withLock {
            current.finalizationActive = false
            val result = when {
                finalizationError != null -> {
                    sinkState = SinkState.ABORTED
                    current.cleanupStarted = true
                    current.part.delete()
                    response(Response.Status.INTERNAL_ERROR, "Unable to finalize sink")
                }
                sinkState != SinkState.COMMITTING -> response(Response.Status.CONFLICT, "Sink is not open")
                !current.part.renameTo("video.${current.container}") -> {
                    sinkState = SinkState.ABORTED
                    response(Response.Status.INTERNAL_ERROR, "Unable to commit sink")
                }
                else -> {
                    sinkState = SinkState.COMMITTED
                    sink?.committedFile = current.part
                    response(Response.Status.OK)
                }
            }
            sinkDrained.signalAll()
            result
        }
        return sinkCors(result)
    }

    private fun deleteSink(): Response {
        sinkLock.withLock {
            if (sinkState != SinkState.OPEN) return sinkConflict()
        }
        abortSink()
        return sinkCors(response(Response.Status.OK))
    }

    private fun abortSink() {
        val session = sinkLock.withLock {
            val session = sinkSession
            // A committed session is terminal: close() must not demote it back to ABORTED, or the
            // finished video would be treated as garbage and deleted.
            if (session == null || sinkState == SinkState.COMMITTED) {
                if (sinkState != SinkState.COMMITTED) sinkState = SinkState.ABORTED
                return
            }
            if (session.cleanupStarted) return
            sinkState = SinkState.ABORTED
            // Bounded, never indefinite: a PUT thread blocked on a dead renderer's socket would
            // otherwise hang close(), and close() runs inside the downloader's NonCancellable
            // teardown. On timeout we close the stream anyway; the stuck write fails with IOException
            // and takes its own error path.
            var remaining = SINK_DRAIN_TIMEOUT_NANOS
            while ((session.activeWrites > 0 || session.finalizationActive) && remaining > 0) {
                remaining = sinkDrained.awaitNanos(remaining)
            }
            session.cleanupStarted = true
            session
        }
        runCatching(session.stream::close)
        runCatching(session.part::delete)
    }

    private fun copyExactly(input: InputStream, output: OutputStream, length: Long) {
        var remaining = length
        val buffer = ByteArray(SINK_COPY_BUFFER_BYTES)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw IOException("Unexpected end of sink body")
            output.write(buffer, 0, read)
            remaining -= read
            runCatching { sink?.onBytesWritten?.invoke(read.toLong()) }
        }
    }

    private fun proxy(session: IHTTPSession): Response {
        val target = session.parameters["url"]
            ?.firstOrNull()
            ?.toHttpUrlOrNull()
            ?: return cors(
                session,
                newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing or invalid url"),
            )
        val method = session.method.name
        if (method !in FORWARDED_METHODS) {
            return cors(
                session,
                newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Unsupported method"),
            )
        }

        val headers = mergeForwardedHeaders(session.headers)
        val request = Request.Builder()
            .url(target)
            .apply {
                headers.forEach { (name, value) ->
                    if (name !in BLOCKED_REQUEST_HEADERS) header(name, value)
                }
                headers["cookie"]?.takeIf(String::isNotBlank)?.let {
                    tag(AdditionalCookie::class, AdditionalCookie(it))
                }
            }
            .method(method, requestBody(session, method, headers["content-type"]))
            .build()

        onNetworkActivity()
        val call = client.newCall(request)
        synchronized(lifecycleLock) {
            check(!closed.get()) { "Proxy server is closed" }
            activeCalls += call
        }
        val upstream = try {
            call.execute()
        } catch (error: Exception) {
            synchronized(lifecycleLock) { activeCalls -= call }
            throw error
        }
        synchronized(lifecycleLock) {
            if (closed.get()) {
                activeCalls -= call
                call.cancel()
                upstream.close()
                throw IOException("Proxy server is closed")
            }
        }
        runCatching(flushCookies)
        return try {
            buildResponse(session, method, upstream, call)
        } catch (error: Exception) {
            closeUpstream(upstream, call)
            throw error
        }
    }

    private fun requestBody(session: IHTTPSession, method: String, forwardedContentType: String?): RequestBody? {
        if (method !in BODY_PERMITTED_METHODS) return null

        val length = session.headers["content-length"]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        if (length == 0L) {
            return if (method in BODY_REQUIRED_METHODS) ByteArray(0).toRequestBody(null) else null
        }

        val contentType = forwardedContentType?.toMediaTypeOrNull()
            ?: session.headers["content-type"]?.toMediaTypeOrNull()
        return object : RequestBody() {
            override fun contentType() = contentType
            override fun contentLength() = length

            override fun writeTo(sink: BufferedSink) {
                var remaining = length
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (remaining > 0) {
                    val read = session.inputStream.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) break
                    sink.write(buffer, 0, read)
                    remaining -= read
                }
            }
        }
    }

    private fun buildResponse(
        session: IHTTPSession,
        method: String,
        upstream: okhttp3.Response,
        call: Call,
    ): Response {
        val status = Response.Status.lookup(upstream.code) ?: object : Response.IStatus {
            override fun getDescription() = "${upstream.code} ${upstream.message}"
            override fun getRequestStatus() = upstream.code
        }
        val contentType = upstream.header("Content-Type") ?: "application/octet-stream"
        val response = if (method == Method.HEAD.name) {
            closeUpstream(upstream, call)
            newFixedLengthResponse(status, contentType, "")
        } else {
            newChunkedResponse(
                status,
                contentType,
                ClosingInputStream(
                    upstream.body.byteStream(),
                    onRead = onNetworkActivity,
                    onClose = { closeUpstream(upstream, call) },
                ),
            )
        }

        val exposed = linkedSetOf<String>()
        upstream.headers.forEach { (name, value) ->
            if (name.lowercase() !in BLOCKED_RESPONSE_HEADERS) {
                response.addHeader(name, value)
                exposed += name
            }
        }
        // content-length is on the block list because NanoHTTPD writes its own for the chunked
        // response it produces. The upstream figure is the only thing a download can compute
        // progress from, so it is re-exposed under a name the block list does not cover.
        upstream.header("Content-Length")?.let {
            response.addHeader(UPSTREAM_LENGTH_HEADER, it)
            exposed += UPSTREAM_LENGTH_HEADER
        }
        if (exposed.isNotEmpty()) response.addHeader("Access-Control-Expose-Headers", exposed.joinToString(", "))
        return cors(session, response)
    }

    private fun proxyPreflight(session: IHTTPSession): Response =
        cors(session, newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")).apply {
            addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS, PATCH")
            addHeader("Access-Control-Allow-Headers", session.headers["access-control-request-headers"] ?: "*")
        }

    // 200 with an empty body, not 204: NanoHTTPD attaches Content-Length to every fixed-length
    // response, and RFC 7230 forbids that on a 204. The proxy route has always answered preflights
    // with 200 and has never shown this problem, so the sink matches it.
    private fun sinkPreflight(): Response =
        sinkCors(response(Response.Status.OK)).apply {
            addHeader("Access-Control-Allow-Methods", "PUT, POST, DELETE, OPTIONS")
            addHeader("Access-Control-Max-Age", "86400")
        }

    private fun cors(session: IHTTPSession, response: Response): Response = response.apply {
        addHeader("Access-Control-Allow-Origin", session.headers["origin"] ?: "*")
        addHeader("Access-Control-Allow-Credentials", "true")
    }

    private fun sinkCors(response: Response): Response = response.apply {
        addHeader("Access-Control-Allow-Origin", sink?.origin ?: DEFAULT_SINK_ORIGIN)
        addHeader("Connection", "close")
    }

    private fun methodNotAllowed(): Response =
        sinkCors(response(Response.Status.METHOD_NOT_ALLOWED, "Unsupported method"))

    private fun sinkBadRequest(message: String): Response =
        sinkCors(response(Response.Status.BAD_REQUEST, message))

    private fun sinkConflict(): Response =
        sinkCors(response(Response.Status.CONFLICT, "Sink is not open"))

    private fun closeConnection(response: Response): Response = response.apply { addHeader("Connection", "close") }

    private fun response(status: Response.IStatus, body: String = ""): Response =
        newFixedLengthResponse(status, MIME_PLAINTEXT, body)

    private fun closeUpstream(response: okhttp3.Response, call: Call) {
        synchronized(lifecycleLock) { activeCalls -= call }
        response.close()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        abortSink()
        val calls = synchronized(lifecycleLock) {
            activeCalls.toList().also { activeCalls.clear() }
        }
        calls.forEach(Call::cancel)
        stop()
    }

    private class ClosingInputStream(
        input: InputStream,
        private val onRead: () -> Unit,
        private val onClose: () -> Unit,
    ) : FilterInputStream(input) {
        private val closed = AtomicBoolean()

        override fun read(): Int = super.read().also { if (it >= 0) onRead() }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also { if (it > 0) onRead() }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            try {
                super.close()
            } finally {
                onClose()
            }
        }
    }

    internal class Sink(
        val directory: UniFile,
        /** Exact Origin allowed to write this sink. */
        val origin: String,
        val onBytesWritten: (Long) -> Unit = {},
    ) {
        @Volatile
        var committedFile: UniFile? = null
            internal set
    }

    private enum class SinkState {
        IDLE,
        OPEN,
        COMMITTING,
        COMMITTED,
        ABORTED,
    }

    private class SinkSession(
        val container: String,
        val part: UniFile,
        val stream: OutputStream,
        var activeWrites: Int = 0,
        var finalizationActive: Boolean = false,
        var cleanupStarted: Boolean = false,
    )

    companion object {
        private const val HOST = "127.0.0.1"
        const val DEFAULT_SINK_ORIGIN = "https://tsundoku.reader"
        private const val UPSTREAM_LENGTH_HEADER = "X-Tsundoku-Upstream-Length"
        private const val SINK_WRITE_BUFFER_BYTES = 256 * 1024
        private const val SINK_COPY_BUFFER_BYTES = 64 * 1024
        private const val SINK_DRAIN_TIMEOUT_NANOS = 10_000_000_000L
        private val CONTAINER_PATTERN = Regex("[a-z0-9]{1,16}")
        private val FORWARDED_METHODS = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE")
        private val BODY_PERMITTED_METHODS = setOf("POST", "PUT", "PATCH", "DELETE")
        private val BODY_REQUIRED_METHODS = setOf("POST", "PUT", "PATCH")
        private val BLOCKED_REQUEST_HEADERS =
            setOf("host", "origin", "accept-encoding", "content-length", "content-type", "cookie")
        private val BLOCKED_RESPONSE_HEADERS = setOf(
            "content-encoding",
            "transfer-encoding",
            "content-length",
            "content-type",
            "access-control-allow-origin",
            "access-control-allow-credentials",
            "access-control-expose-headers",
        )

        private fun randomToken(): String = UUID.randomUUID().toString()

        private fun mergeForwardedHeaders(headers: Map<String, String>): Map<String, String> = buildMap {
            headers.forEach { (name, value) ->
                if (!name.startsWith(FORWARDED_HEADER_PREFIX, true)) put(name.lowercase(), value)
            }
            headers.forEach { (name, value) ->
                if (name.startsWith(FORWARDED_HEADER_PREFIX, true)) {
                    put(name.substring(FORWARDED_HEADER_PREFIX.length).lowercase(), value)
                }
            }
        }

        private const val FORWARDED_HEADER_PREFIX = "x-ln-forward-header-"
    }
}
