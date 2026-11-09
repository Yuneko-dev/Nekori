@file:Suppress("ktlint:standard:filename")

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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TtsCompletionTest {
    @Test
    fun `last chunk start is not completion and final done completes once with auto next off`() = withController {
            controller,
            callbacks,
            drain,
        ->
        controller.speak("First.\nLast.", chapterId = 9)
        val token = token(controller, 1)
        invoke(controller, "handleUtteranceStart", token)
        drain()
        verify(exactly = 0) { callbacks.onChapterCompleted(any()) }
        invoke(controller, "handleUtteranceDone", token)
        invoke(controller, "handleUtteranceDone", token)
        drain()
        verify(exactly = 1) { callbacks.onChapterCompleted(9) }
        verify(exactly = 0) { callbacks.onLastChunkDone() }
    }

    @Test
    fun `completion queued before stop or chapter replacement cannot complete the new session`() = withController {
            controller,
            callbacks,
            drain,
        ->
        controller.speak("Old.", chapterId = 9)
        invoke(controller, "handleUtteranceDone", token(controller, 0))
        controller.stop()
        drain()
        verify(exactly = 0) { callbacks.onChapterCompleted(any()) }
        controller.speak("Old again.", chapterId = 9)
        val oldToken = token(controller, 0)
        invoke(controller, "handleUtteranceDone", oldToken)
        controller.speak("New.", chapterId = 10)
        controller.isTtsAutoPlay = true
        drain()
        assertTrue(controller.isTtsAutoPlay, "A queued old completion must not stop the replacement session")
        invoke(controller, "handleUtteranceDone", oldToken)
        drain()
        verify(exactly = 0) { callbacks.onChapterCompleted(any()) }
        invoke(controller, "handleUtteranceDone", token(controller, 0))
        drain()
        verify(exactly = 1) { callbacks.onChapterCompleted(10) }
    }

    private fun token(controller: TtsController, index: Int): String {
        val generation = TtsController::class.java.getDeclaredField("playbackGeneration")
            .apply { isAccessible = true }.getLong(controller)
        return "tts_utterance_$generation:$index"
    }

    private fun invoke(controller: TtsController, method: String, token: String) {
        TtsController::class.java.getDeclaredMethod(method, String::class.java)
            .apply { isAccessible = true }.invoke(controller, token)
    }

    private fun withController(block: (TtsController, TtsController.Callbacks, () -> Unit) -> Unit) {
        val context = mockk<Context> { every { applicationContext } returns this }
        val preferences = mockk<ReaderPreferences> {
            every { novelTtsVoice.get() } returns ""
            every { novelTtsSpeed.get() } returns 1f
            every { novelTtsPitch.get() } returns 1f
            every { novelTtsEnableHighlight.get() } returns false
            every { novelTtsAutoNextChapter.get() } returns false
        }
        val callbacks = mockk<TtsController.Callbacks>(relaxed = true)
        val pending = ArrayDeque<() -> Unit>()
        every { callbacks.runOnUiThread(any()) } answers { pending.addLast(firstArg()) }
        val controller = TtsController(context, preferences, mockk(), CoroutineScope(Dispatchers.Unconfined), callbacks)
        TtsController::class.java.getDeclaredField("tts").apply { isAccessible = true }
            .set(controller, mockk<TextToSpeech>(relaxed = true))
        TtsController::class.java.getDeclaredField("ttsInitialized").apply { isAccessible = true }.set(controller, true)
        mockkStatic(TextToSpeech::class)
        try {
            every { TextToSpeech.getMaxSpeechInputLength() } returns 4000
            block(controller, callbacks) { while (pending.isNotEmpty()) pending.removeFirst().invoke() }
        } finally {
            unmockkStatic(TextToSpeech::class)
        }
    }
}
