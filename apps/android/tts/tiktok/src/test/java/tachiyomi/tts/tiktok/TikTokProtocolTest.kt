package tachiyomi.tts.tiktok

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

class TikTokProtocolTest {
    @Test
    fun `start message contains voice text and pcm configuration`() {
        val root = Json.parseToJsonElement(TikTokProtocol.startMessage(" Hello ", "BV074_streaming")).jsonObject
        val payload = Json.parseToJsonElement(root.getValue("payload").jsonPrimitive.content).jsonObject
        val audio = payload.getValue("audio_config").jsonObject

        assertEquals("StartTask", root.getValue("event").jsonPrimitive.content)
        assertEquals("BV074_streaming", payload.getValue("speaker").jsonPrimitive.content)
        assertEquals("Hello", payload.getValue("text").jsonPrimitive.content)
        assertEquals("pcm", audio.getValue("format").jsonPrimitive.content)
        assertEquals(TikTokProtocol.SAMPLE_RATE, audio.getValue("sample_rate").jsonPrimitive.content.toInt())
    }

    @Test
    fun `events parse completion and failure while pcm is ignored`() {
        assertEquals("TaskFinished", TikTokProtocol.event("""{"event":"TaskFinished"}""")?.name)
        assertEquals(
            "TaskFailed: 401 - denied",
            TikTokProtocol.event("""{"event":"TaskFailed","status_code":401,"status_text":"denied"}""")?.error,
        )
        assertNull(TikTokProtocol.event("not-json"))
    }

    @Test
    fun `catalog uses locale then falls back to first voice`() {
        assertEquals("vi", TikTokVoiceCatalog.defaultFor(Locale.forLanguageTag("vi-VN")).languageTag)
        assertEquals(TikTokVoiceCatalog.voices.first(), TikTokVoiceCatalog.defaultFor(Locale.forLanguageTag("zz")))
        assertTrue(TikTokVoiceCatalog.voices.map(TikTokVoice::id).distinct().size == TikTokVoiceCatalog.voices.size)
    }
}
