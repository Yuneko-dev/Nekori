package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

import android.content.Context
import android.speech.tts.TextToSpeech
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class TtsControllerTextTest {
    @Test
    fun `filtered paragraphs keep queue and seek indices aligned including single and empty chapters`() {
        val context = mockk<Context> { every { applicationContext } returns this }
        val preferences = mockk<ReaderPreferences> {
            every { novelTtsVoice.get() } returns ""
            every { novelTtsSpeed.get() } returns 1f
            every { novelTtsPitch.get() } returns 1f
            every { novelTtsEnableHighlight.get() } returns true
        }
        val engine = mockk<TextToSpeech>(relaxed = true)
        val callbacks = mockk<TtsController.Callbacks>(relaxed = true)
        val controller = TtsController(
            context,
            preferences,
            mockk(),
            CoroutineScope(Dispatchers.Unconfined),
            callbacks,
        )
        TtsController::class.java.getDeclaredField("tts").apply { isAccessible = true }.set(controller, engine)
        TtsController::class.java.getDeclaredField("ttsInitialized").apply { isAccessible = true }.set(controller, true)
        mockkStatic(TextToSpeech::class)
        try {
            every { TextToSpeech.getMaxSpeechInputLength() } returns 20
            controller.speak("***\nFirst paragraph.\n\"\"\"\nSecond paragraph with enough words to split.\n---")
            assertEquals(listOf(0, 1, 1, 1), controller.ttsChunkParagraphIndexes)
            controller.seekToParagraph(1)
            assertEquals(1, controller.ttsCurrentChunkIndex)
            verify { callbacks.onHighlightChunk(1, any(), any(), 1) }
            controller.speak("***\n[\"Only text\"]\n---")
            assertEquals(listOf("Only text"), controller.ttsChunks)
            assertEquals(listOf(0), controller.ttsChunkParagraphIndexes)
            verify { engine.speak("Only text", TextToSpeech.QUEUE_FLUSH, null, any()) }
            controller.isTtsAutoPlay = true
            controller.speak("***\n\"\"\"\n---")
            assertEquals(emptyList<String>(), controller.ttsChunks)
            assertFalse(controller.isStarting())
            verify { engine.stop() }
        } finally {
            unmockkStatic(TextToSpeech::class)
        }
    }
}
