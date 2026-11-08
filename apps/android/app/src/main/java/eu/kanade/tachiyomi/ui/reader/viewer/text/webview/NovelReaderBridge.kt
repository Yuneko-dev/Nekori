package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/** Owns a single main-frame document; chapter unloads only update its permitted identities. */
internal class NovelReaderBridge(
    private val webView: WebView,
    private val onReady: () -> Unit,
    private val onEvent: (String, JsonObject) -> Unit,
    private val onRequest: (String, JsonObject, (JsonElement?) -> Unit) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var proxy: JavaScriptReplyProxy? = null
    private var registered = false
    private var ready = false
    private var origin: String? = null
    private var chapterIds = emptySet<String>()
    private val pending = mutableMapOf<String, (String?) -> Unit>()
    private val queued = mutableListOf<String>()
    var documentId: String? = null
        private set

    fun beginDocument(baseUrl: String, chapters: Collection<Long>): String {
        val nextOrigin = requireNotNull(NovelReaderProtocol.origin(baseUrl))
        resetDocument(removeListener = origin != nextOrigin)
        check(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            "Nekori reader requires Android WebView 138.0.7204.181 with web messaging support."
        }
        check(
            NovelReaderProtocol.supportsVersion(WebViewCompat.getCurrentWebViewPackage(webView.context)?.versionName),
        ) {
            "Nekori reader requires Android WebView 138.0.7204.181 or newer."
        }
        origin = nextOrigin
        documentId = UUID.randomUUID().toString()
        updateChapters(chapters)
        // Keep the same-origin listener alive while the previous document finishes posting messages.
        if (registered) return requireNotNull(documentId)
        WebViewCompat.addWebMessageListener(
            webView,
            NovelReaderProtocol.TRANSPORT_NAME,
            setOf(requireNotNull(origin)),
        ) { _, message, sourceOrigin, isMainFrame, replyProxy ->
            if (!isMainFrame ||
                NovelReaderProtocol.origin(sourceOrigin.toString()) != origin
            ) {
                return@addWebMessageListener
            }
            val id = documentId ?: return@addWebMessageListener
            val decoded = NovelReaderProtocol.decode(message.data ?: return@addWebMessageListener, id)
                ?: return@addWebMessageListener
            receive(decoded, replyProxy)
        }
        registered = true
        return requireNotNull(documentId)
    }

    fun updateChapters(chapters: Collection<Long>) {
        chapterIds = chapters.map(Long::toString).toSet()
    }

    fun execute(command: ReaderCommand, callback: ((String?) -> Unit)? = null) {
        val id = documentId ?: run {
            callback?.invoke(null)
            return
        }
        val requestId = UUID.randomUUID().toString()
        if (callback != null) {
            pending[requestId] = callback
            handler.postDelayed({
                pending.remove(requestId)?.let {
                    send(envelope("cancel", requestId))
                    it(null)
                }
            }, REQUEST_TIMEOUT_MS)
        }
        val wire = envelope("command", requestId, command.method, command.payload, id)
        if (ready) send(wire) else queued.add(wire)
    }

    private fun receive(message: NovelReaderProtocol.Message, replyProxy: JavaScriptReplyProxy) {
        when (message.kind) {
            "boot" -> {
                if (proxy != null) return
                proxy = replyProxy
                send(envelope("boot", payload = buildJsonObject { put("ack", true) }))
            }
            "ready" -> {
                if (proxy == null || ready) return
                ready = true
                val commands = queued.toList()
                queued.clear()
                commands.forEach(::send)
                onReady()
            }
            "result" -> {
                if (proxy == null) return
                val callback = pending.remove(message.requestId) ?: return
                if (message.error != null) {
                    onError((message.error["message"] as? JsonPrimitive)?.content ?: "Reader command failed")
                    callback(null)
                } else {
                    callback((message.result ?: JsonNull).toString())
                }
            }
            "event", "command" -> {
                // Startup failures still need a native error surface when controllers could not initialize.
                if (!ready && !(message.kind == "event" && message.method == "reader.error" && proxy != null)) return
                val payload = message.payload ?: JsonObject(emptyMap())
                val chapterIdValue = payload["chapterId"]
                if (chapterIdValue != null) {
                    val chapterId = chapterIdValue as? JsonPrimitive ?: return
                    if (!chapterId.isString || chapterId.content !in chapterIds) return
                }
                val method = message.method ?: return
                if (message.kind == "event") {
                    onEvent(method, payload)
                } else {
                    val activeId = documentId
                    onRequest(method, payload) { result ->
                        if (documentId != activeId) return@onRequest
                        val error = if (result == null) {
                            buildJsonObject {
                                put("code", "UNSUPPORTED")
                                put("message", "Unsupported native reader capability: $method")
                            }
                        } else {
                            null
                        }
                        send(envelope("result", message.requestId, payload = result ?: JsonNull, error = error))
                    }
                }
            }
        }
    }

    fun endDocument() = resetDocument(removeListener = true)

    private fun resetDocument(removeListener: Boolean) {
        ready = false
        proxy = null
        origin = null
        documentId = null
        chapterIds = emptySet()
        queued.clear()
        val callbacks = pending.values.toList()
        pending.clear()
        handler.removeCallbacksAndMessages(null)
        if (registered && removeListener) {
            WebViewCompat.removeWebMessageListener(webView, NovelReaderProtocol.TRANSPORT_NAME)
            registered = false
        }
        callbacks.forEach { it(null) }
    }

    private fun send(wire: String) {
        runCatching { proxy?.postMessage(wire) }.onFailure { onError(it.message ?: "Reader transport failed") }
    }

    private fun envelope(
        kind: String,
        requestId: String? = null,
        method: String? = null,
        payload: JsonElement? = null,
        id: String? = documentId,
        error: JsonObject? = null,
    ): String = buildJsonObject {
        put("protocolVersion", NovelReaderProtocol.VERSION)
        put("documentId", id ?: "")
        put("kind", kind)
        requestId?.let { put("requestId", it) }
        method?.let { put("method", it) }
        payload?.let { put("payload", it) }
        error?.let { put("error", it) }
    }.toString()

    private companion object {
        const val REQUEST_TIMEOUT_MS = 15_000L
    }
}
