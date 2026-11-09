package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import java.util.concurrent.TimeUnit

/** Sends invalid-generation, unknown-chapter, and subframe traffic through the real page transport. */
internal object NovelReaderTransportAttackFixture {
    fun assertBound(
        documentId: String,
        chapterId: String,
        evaluate: (String) -> String,
        awaitEvent: (String) -> JsonObject,
        observedEvents: List<Pair<String, JsonObject>>,
    ) {
        val stale = eventWire("stale-document", chapterId, "stale")
        val unknown = eventWire(documentId, "999999999999999999", "unknown")
        val barrier = eventWire(documentId, chapterId, "barrier")
        evaluate(
            """window.__nekoriNativeTransport.postMessage(${JsonPrimitive(stale)});
                window.__nekoriNativeTransport.postMessage(${JsonPrimitive(unknown)});
                window.__nekoriNativeTransport.postMessage(${JsonPrimitive(barrier)});""",
        )
        assertEquals("barrier", awaitEvent("chapter.visible")["marker"]?.toString()?.trim('"'))
        assertFalse(observedEvents.any { marker(it) in setOf("stale", "unknown") })

        val frameEvent = JsonPrimitive(eventWire(documentId, chapterId, "frame"))
        val frameScript = "window.__nekoriNativeTransport.postMessage($frameEvent);" + "parent.__frameMessageSent=true;"
        val frameHtml = "<script>$frameScript</script>"
        val createFrame = "const f=document.createElement('iframe');" +
            "f.srcdoc=${JsonPrimitive(frameHtml)};document.body.append(f)"
        evaluate(createFrame)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (System.nanoTime() < deadline && evaluate("window.__frameMessageSent === true") != "true") {
            Thread.sleep(100)
        }
        assertEquals(
            "iframe did not attempt its transport message",
            "true",
            evaluate("window.__frameMessageSent === true"),
        )
        val barrierEvent = JsonPrimitive(eventWire(documentId, chapterId, "frame-barrier"))
        evaluate("window.__nekoriNativeTransport.postMessage($barrierEvent)")
        assertEquals("frame-barrier", awaitEvent("chapter.visible")["marker"]?.toString()?.trim('"'))
        // Cross-frame callbacks have no synchronous acknowledgement; observe after the main-frame barrier.
        val quietDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (System.nanoTime() < quietDeadline && observedEvents.none { marker(it) == "frame" }) Thread.sleep(25)
        assertFalse("subframe message reached native listener", observedEvents.any { marker(it) == "frame" })
    }

    private fun eventWire(documentId: String, chapterId: String, marker: String) = buildJsonObject {
        put("protocolVersion", 1)
        put("documentId", documentId)
        put("kind", "event")
        put("method", "chapter.visible")
        put(
            "payload",
            buildJsonObject {
                put("chapterId", chapterId)
                put("progress", 0.5)
                put("marker", marker)
            },
        )
    }.toString()

    private fun marker(event: Pair<String, JsonObject>) = event.second["marker"]?.toString()?.trim('"')
}
