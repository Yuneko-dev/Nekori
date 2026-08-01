package eu.kanade.tachiyomi.ui.reader.viewer.text.webview.proxy

import android.webkit.CookieManager
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
import java.io.Closeable
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

internal class NovelReaderProxyServer(
    private val client: OkHttpClient,
    private val flushCookies: () -> Unit = { CookieManager.getInstance().flush() },
) : NanoHTTPD(HOST, 0), Closeable {

    private val route = "/${randomToken()}/proxy"
    private val lifecycleLock = Any()
    private val activeCalls = mutableSetOf<Call>()
    private val closed = AtomicBoolean()

    val endpoint: String
        get() = "http://$HOST:$listeningPort$route"

    override fun start() {
        check(!closed.get()) { "Proxy server is closed" }
        if (!wasStarted()) super.start(SOCKET_READ_TIMEOUT, false)
    }

    override fun serve(session: IHTTPSession): Response {
        if (session.uri != route) return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        if (session.method == Method.OPTIONS) return preflight(session)

        return runCatching { proxy(session) }
            .getOrElse {
                cors(
                    session,
                    newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Network request failed"),
                )
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
            return if (method in BODY_REQUIRED_METHODS) EMPTY_BYTES.toRequestBody(null) else null
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
                ClosingInputStream(upstream.body.byteStream()) { closeUpstream(upstream, call) },
            )
        }

        val exposed = linkedSetOf<String>()
        upstream.headers.forEach { (name, value) ->
            if (name.lowercase() !in BLOCKED_RESPONSE_HEADERS) {
                response.addHeader(name, value)
                exposed += name
            }
        }
        if (exposed.isNotEmpty()) response.addHeader("Access-Control-Expose-Headers", exposed.joinToString(", "))
        return cors(session, response)
    }

    private fun preflight(session: IHTTPSession): Response =
        cors(session, newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")).apply {
            addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS, PATCH")
            addHeader("Access-Control-Allow-Headers", session.headers["access-control-request-headers"] ?: "*")
        }

    private fun cors(session: IHTTPSession, response: Response): Response = response.apply {
        addHeader("Access-Control-Allow-Origin", session.headers["origin"] ?: "*")
        addHeader("Access-Control-Allow-Credentials", "true")
    }

    private fun closeUpstream(response: okhttp3.Response, call: Call) {
        synchronized(lifecycleLock) { activeCalls -= call }
        response.close()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val calls = synchronized(lifecycleLock) {
            activeCalls.toList().also { activeCalls.clear() }
        }
        calls.forEach(Call::cancel)
        stop()
    }

    private class ClosingInputStream(
        input: InputStream,
        private val onClose: () -> Unit,
    ) : FilterInputStream(input) {
        private val closed = AtomicBoolean()

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            try {
                super.close()
            } finally {
                onClose()
            }
        }
    }

    companion object {
        private const val HOST = "127.0.0.1"
        private val EMPTY_BYTES = ByteArray(0)
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

        private fun randomToken(): String {
            val bytes = ByteArray(24)
            SecureRandom().nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

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
