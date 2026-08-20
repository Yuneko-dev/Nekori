package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import logcat.LogPriority
import logcat.logcat
import okhttp3.OkHttpClient
import tachiyomi.tts.tiktok.TikTokTtsEngine
import tachiyomi.tts.tiktok.TikTokVoiceCatalog
import java.util.Locale
import kotlin.math.roundToInt

class TtsController(
    private val context: Context,
    private val preferences: ReaderPreferences,
    private val networkClient: OkHttpClient,
    private val scope: CoroutineScope,
    private val callbacks: Callbacks,
) : TextToSpeech.OnInitListener {

    interface Callbacks {
        fun onInitialized(pendingRequest: StartRequest?)
        fun onHighlightChunk(chunkIndex: Int, chunk: String, startOffset: Int, paragraphIndex: Int)
        fun onClearHighlights()
        fun onLastChunkDone()
        fun onError(error: Throwable)
        fun runOnUiThread(action: () -> Unit)
    }

    enum class StartRequest { NORMAL, VIEWPORT }

    private var tts: TextToSpeech? = null
    private var tikTokTts: TikTokTtsEngine? = null
    private val tikTokListener = object : TikTokTtsEngine.Listener {
        override fun onStart(utteranceId: String) = handleUtteranceStart(utteranceId)

        override fun onDone(utteranceId: String) = handleUtteranceDone(utteranceId)

        override fun onError(utteranceId: String, error: Throwable) {
            handleUtteranceError(utteranceId, error)
        }
    }
    var ttsInitialized = false
        private set

    var isTtsAutoPlay = false
    var ttsPaused = false

    var ttsChunks: List<String> = emptyList()
        private set
    var ttsChunkParagraphIndexes: List<Int> = emptyList()
        private set
    var ttsChunkStartOffsets: List<Int> = emptyList()
        private set
    var ttsPlaybackChapterIndex: Int = 0
        private set
    var ttsPlaybackChapterId: Long? = null
        private set

    @Volatile var ttsCurrentChunkIndex = 0

    @Volatile var ttsResumeChunkIndex: Int = 0
    private var playbackGeneration = 0L
    var ttsViewportParagraphIndex: Int = 0
    var hasViewportStartOverride: Boolean = false
    var pendingStartRequest: StartRequest? = null

    fun ensureInitialized() {
        if (usesTikTok()) {
            if (tikTokTts == null) tikTokTts = TikTokTtsEngine(networkClient)
            ttsInitialized = true
            return
        }
        if (tts == null) {
            try {
                tts = TextToSpeech(context, this)
                logcat(LogPriority.DEBUG) { "TTS: Initialization started" }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR) { "TTS: Failed to create instance: ${e.message}" }
            }
        }
    }

    override fun onInit(status: Int) {
        if (usesTikTok()) {
            tts?.shutdown()
            tts = null
            return
        }
        if (status == TextToSpeech.SUCCESS) {
            ttsInitialized = true
            applySettings()
            setupListener()
            val pending = pendingStartRequest
            pendingStartRequest = null
            callbacks.runOnUiThread {
                callbacks.onInitialized(pending)
            }
        } else {
            ttsInitialized = false
            logcat(LogPriority.ERROR) { "TTS initialization failed with status: $status" }
        }
    }

    private fun setupListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                utteranceId?.let(::handleUtteranceStart)
            }

            override fun onDone(utteranceId: String?) {
                utteranceId?.let(::handleUtteranceDone)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                handleUtteranceError(
                    utteranceId.orEmpty(),
                    IllegalStateException("Android TTS failed for utterance $utteranceId"),
                )
            }
        })
    }

    private fun handleUtteranceStart(utteranceId: String) {
        val chunkIndex = utteranceId.chunkIndex() ?: return
        ttsCurrentChunkIndex = chunkIndex
        if (!preferences.novelTtsEnableHighlight.get()) return
        val chunk = ttsChunks.getOrNull(chunkIndex) ?: return
        val startOffset = ttsChunkStartOffsets.getOrElse(chunkIndex) { 0 }
        val paragraphIndex = ttsChunkParagraphIndexes.getOrElse(chunkIndex) { chunkIndex }
        callbacks.runOnUiThread {
            callbacks.onHighlightChunk(chunkIndex, chunk, startOffset, paragraphIndex)
        }
    }

    private fun handleUtteranceDone(utteranceId: String) {
        val finishedIndex = utteranceId.chunkIndex() ?: return
        if (usesTikTok() && finishedIndex < ttsChunks.lastIndex) {
            if (!ttsPaused && finishedIndex == ttsCurrentChunkIndex) {
                speakTikTokChunk(finishedIndex + 1)
            }
            return
        }
        if (finishedIndex < ttsChunks.lastIndex) return

        if (isTtsAutoPlay && preferences.novelTtsAutoNextChapter.get()) {
            callbacks.runOnUiThread {
                scope.launch {
                    delay(LAST_CHUNK_DONE_DELAY_MS)
                    if (!isSpeaking()) callbacks.onLastChunkDone()
                }
            }
        } else {
            isTtsAutoPlay = false
            callbacks.runOnUiThread(callbacks::onClearHighlights)
        }
    }

    private fun handleUtteranceError(utteranceId: String, error: Throwable) {
        if (utteranceId.chunkIndex() == null) return
        logcat(LogPriority.ERROR) {
            "TTS error on utterance $utteranceId: ${error.stackTraceToString()}"
        }
        if (usesTikTok()) {
            callbacks.runOnUiThread { callbacks.onError(error) }
            return
        }
        stop()
        callbacks.runOnUiThread { callbacks.onError(error) }
    }

    private fun String.chunkIndex(): Int? {
        val parts = removePrefix(UTTERANCE_PREFIX).split(':', limit = 2)
        val generation = parts.getOrNull(0)?.toLongOrNull() ?: return null
        if (generation != playbackGeneration) return null
        return parts.getOrNull(1)?.toIntOrNull()
    }

    private fun utteranceId(chunkIndex: Int) = "$UTTERANCE_PREFIX$playbackGeneration:$chunkIndex"

    fun speak(text: String, chapterIndex: Int = 0, chapterId: Long? = null) {
        val engineUnavailable = if (usesTikTok()) tikTokTts == null else tts == null
        if (!ttsInitialized || engineUnavailable) {
            logcat(LogPriority.WARN) { "TTS not initialized, cannot speak" }
            return
        }
        ttsPlaybackChapterIndex = chapterIndex
        ttsPlaybackChapterId = chapterId
        playbackGeneration++
        applySettings()
        ttsPaused = false

        val maxLength = TextToSpeech.getMaxSpeechInputLength().takeIf { it > 0 } ?: 4000
        val paragraphs = text.lineSequence()
            .map(TtsTextUtils::normalizeText)
            .filter(String::isNotEmpty)
            .toList()
        val chunkParagraphIndexes = mutableListOf<Int>()

        val chunks = if (paragraphs.size > 1) {
            paragraphs.flatMapIndexed { paragraphIndex, para ->
                val c = if (para.length <= maxLength) {
                    listOf(para)
                } else {
                    TtsTextUtils.splitTextForTts(para, maxLength)
                }
                repeat(c.size) { chunkParagraphIndexes.add(paragraphIndex) }
                c
            }
        } else if (text.length <= maxLength) {
            chunkParagraphIndexes.add(0)
            listOf(text)
        } else {
            val c = TtsTextUtils.splitTextForTts(text, maxLength)
            repeat(c.size) { chunkParagraphIndexes.add(0) }
            c
        }
        ttsChunks = chunks
        ttsChunkParagraphIndexes = chunkParagraphIndexes

        // Compute char offset per chunk so Spannable-based highlight can locate the right occurrence.
        val offsets = mutableListOf<Int>()
        var searchFrom = 0
        for (chunk in ttsChunks) {
            val idx = text.indexOf(chunk, searchFrom)
            if (idx >= 0) {
                offsets.add(idx)
                searchFrom = idx + chunk.length
            } else {
                offsets.add(searchFrom)
            }
        }
        ttsChunkStartOffsets = offsets

        ttsCurrentChunkIndex = 0
        val startIndex = if (hasViewportStartOverride) {
            ttsChunkParagraphIndexes.indexOfFirst { it >= ttsViewportParagraphIndex }
                .takeIf { it >= 0 } ?: 0
        } else {
            0
        }
        hasViewportStartOverride = false

        speakChunksFrom(startIndex.coerceIn(0, (ttsChunks.size - 1).coerceAtLeast(0)))
    }

    fun speakChunksFrom(startIndex: Int) {
        if (ttsChunks.isEmpty() || startIndex >= ttsChunks.size) return
        if (usesTikTok()) {
            speakTikTokChunk(startIndex)
            return
        }
        ttsChunks.drop(startIndex).forEachIndexed { i, chunk ->
            val actualIndex = startIndex + i
            val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts?.speak(chunk, mode, null, utteranceId(actualIndex))
        }
    }

    private fun speakTikTokChunk(chunkIndex: Int) {
        val chunk = ttsChunks.getOrNull(chunkIndex) ?: return
        val engine = tikTokTts ?: return
        val voice = selectedTikTokVoice()
        ttsCurrentChunkIndex = chunkIndex
        engine.speak(
            text = chunk,
            voice = voice,
            rate = preferences.novelTtsSpeed.get(),
            pitch = preferences.novelTtsPitch.get(),
            utteranceId = utteranceId(chunkIndex),
            listener = tikTokListener,
        )
        engine.preload(
            texts = ttsChunks.drop(chunkIndex + 1).take(TIKTOK_PRELOAD_COUNT),
            voice = voice,
        )
    }

    fun pause() {
        if (ttsInitialized && isSpeaking()) {
            ttsPaused = true
            ttsResumeChunkIndex = ttsCurrentChunkIndex
            if (usesTikTok()) {
                tikTokTts?.pause()
            } else {
                playbackGeneration++
                tts?.stop()
            }
        }
    }

    fun resume() {
        if (ttsPaused && ttsChunks.isNotEmpty()) {
            ttsPaused = false
            if (usesTikTok()) {
                tikTokTts?.resume()
            } else {
                speakChunksFrom(ttsResumeChunkIndex)
            }
        }
    }

    fun stop() {
        playbackGeneration++
        isTtsAutoPlay = false
        ttsPaused = false
        pendingStartRequest = null
        ttsChunks = emptyList()
        ttsChunkParagraphIndexes = emptyList()
        ttsChunkStartOffsets = emptyList()
        ttsCurrentChunkIndex = 0
        ttsResumeChunkIndex = 0
        hasViewportStartOverride = false
        if (ttsInitialized) {
            tts?.stop()
            tikTokTts?.stop()
        }
        ttsPlaybackChapterIndex = 0
        ttsPlaybackChapterId = null
        callbacks.runOnUiThread { callbacks.onClearHighlights() }
    }

    fun stepParagraph(delta: Int, onEmpty: () -> Unit) {
        if (delta == 0) return
        if (ttsChunks.isEmpty()) {
            onEmpty()
            return
        }

        val target = TtsTextUtils.computeTtsStepTargetChunk(
            delta,
            ttsPaused,
            ttsResumeChunkIndex,
            ttsCurrentChunkIndex,
            ttsChunks,
            ttsChunkParagraphIndexes,
        )
        ttsResumeChunkIndex = target
        ttsCurrentChunkIndex = target
        ttsPaused = false
        if (!usesTikTok()) {
            playbackGeneration++
            tts?.stop()
        }
        speakChunksFrom(target)
        if (preferences.novelTtsEnableHighlight.get()) {
            val chunk = ttsChunks.getOrNull(target) ?: return
            callbacks.onHighlightChunk(
                target,
                chunk,
                ttsChunkStartOffsets.getOrElse(target) { 0 },
                ttsChunkParagraphIndexes.getOrElse(target) { 0 },
            )
        }
    }

    fun isSpeaking(): Boolean = ttsInitialized && if (usesTikTok()) {
        tikTokTts?.isSpeaking == true
    } else {
        tts?.isSpeaking == true
    }
    fun isPaused(): Boolean = ttsPaused
    fun isStarting(): Boolean =
        pendingStartRequest != null ||
            (!ttsInitialized && (tts != null || tikTokTts != null)) ||
            (ttsChunks.isEmpty() && isTtsAutoPlay)

    fun getProgressPercent(): Int {
        if (ttsChunks.isEmpty()) return 0
        val current = (if (ttsPaused) ttsResumeChunkIndex else ttsCurrentChunkIndex)
            .coerceIn(0, ttsChunks.size - 1)
        return (((current + 1) * 100f) / ttsChunks.size).roundToInt().coerceIn(0, 100)
    }

    fun applySettings() {
        if (usesTikTok()) {
            selectedTikTokVoice()
            return
        }
        tts?.let { engine ->
            val voicePref = preferences.novelTtsVoice.get()
            if (voicePref.isNotEmpty()) {
                val selected = engine.voices?.find { it.name == voicePref }
                if (selected != null) {
                    engine.voice = selected
                } else {
                    try {
                        engine.language = Locale.forLanguageTag(voicePref)
                    } catch (e: Exception) {
                        engine.language = Locale.getDefault()
                    }
                }
            } else {
                engine.language = Locale.getDefault()
            }
            engine.setSpeechRate(preferences.novelTtsSpeed.get())
            engine.setPitch(preferences.novelTtsPitch.get())
        }
    }

    fun onEngineChanged() {
        stop()
        tts?.shutdown()
        tts = null
        tikTokTts?.close()
        tikTokTts = null
        ttsInitialized = false
    }

    private fun usesTikTok(): Boolean = preferences.novelTtsUseTikTok.get()

    private fun selectedTikTokVoice(): String {
        val selected = preferences.novelTtsTikTokVoice.get()
        if (TikTokVoiceCatalog.find(selected) != null) return selected
        return TikTokVoiceCatalog.defaultFor(Locale.getDefault()).id.also {
            preferences.novelTtsTikTokVoice.set(it)
        }
    }

    fun destroy() {
        stop()
        tts?.shutdown()
        tts = null
        tikTokTts?.close()
        tikTokTts = null
        ttsInitialized = false
    }

    companion object {
        private const val LAST_CHUNK_DONE_DELAY_MS = 500L
        private const val TIKTOK_PRELOAD_COUNT = 5
        private const val UTTERANCE_PREFIX = "tts_utterance_"
    }
}
