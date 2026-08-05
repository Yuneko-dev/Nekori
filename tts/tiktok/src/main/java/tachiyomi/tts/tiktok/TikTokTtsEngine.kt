package tachiyomi.tts.tiktok

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.security.MessageDigest
import java.util.concurrent.Executor

class TikTokTtsEngine internal constructor(
    private val newWebSocket: (Request, WebSocketListener) -> WebSocket,
    private val endpoint: String,
    private val callbackExecutor: Executor,
    private val playerFactory: PcmPlayerFactory,
    private val retryDelayMs: Long,
) : Closeable {

    interface Listener {
        fun onStart(utteranceId: String)
        fun onDone(utteranceId: String)
        fun onError(utteranceId: String, error: Throwable)
    }

    constructor(client: OkHttpClient) : this(
        newWebSocket = client::newWebSocket,
        endpoint = TikTokProtocol.ENDPOINT,
        callbackExecutor = Executor { action -> Handler(Looper.getMainLooper()).post(action) },
        playerFactory = PcmPlayerFactory(::AudioTrackPcmPlayer),
        retryDelayMs = DEFAULT_RETRY_DELAY_MS,
    )

    private data class PendingPlayback(
        val serial: Long,
        val key: String,
        val utteranceId: String,
        val listener: Listener,
        val rate: Float,
        val pitch: Float,
    )

    private data class SynthesisJob(
        val key: String,
        val text: String,
        val voice: String,
        val generation: Long,
        var preload: Boolean,
        var retries: Int = 0,
        var socket: WebSocket? = null,
        val callbacks: MutableList<(Result<ByteArray>) -> Unit> = mutableListOf(),
    )

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = LinkedHashMap<String, ByteArray>(MAX_CACHE_ENTRIES, 0.75f, true)
    private val jobs = mutableMapOf<String, SynthesisJob>()
    private var desiredKeys = emptySet<String>()
    private var generation = 0L
    private var playbackSerial = 0L
    private var pendingPlayback: PendingPlayback? = null
    private var player: PcmPlayer? = null
    private var paused = false
    private var closed = false

    val isSpeaking: Boolean
        get() = synchronized(lock) { !paused && (pendingPlayback != null || player?.isPlaying == true) }

    fun speak(
        text: String,
        voice: String,
        rate: Float,
        pitch: Float,
        utteranceId: String,
        listener: Listener,
    ) {
        val normalized = text.trim()
        require(normalized.isNotEmpty()) { "TikTok TTS text must not be blank" }
        require(TikTokVoiceCatalog.find(voice) != null) { "Unknown TikTok TTS voice: $voice" }

        val key = cacheKey(normalized, voice)
        val staleSocket = synchronized(lock) {
            check(!closed) { "TikTok TTS engine is closed" }
            player?.release()
            player = null
            paused = false
            val previous = pendingPlayback
            val stale = previous?.key
                ?.takeIf { it != key }
                ?.let(jobs::remove)
                ?.takeUnless(SynthesisJob::preload)
                ?.socket
            val serial = ++playbackSerial
            pendingPlayback = PendingPlayback(serial, key, utteranceId, listener, rate, pitch)
            desiredKeys = desiredKeys + key
            stale
        }
        staleSocket?.cancel()
        requestAudio(normalized, voice, preload = false) { result ->
            callbackExecutor.execute { handlePlaybackAudio(key, result) }
        }
    }

    fun preload(texts: List<String>, voice: String) {
        if (TikTokVoiceCatalog.find(voice) == null) return
        val candidates = texts.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .take(MAX_PRELOAD_REQUESTS)
            .map { text -> cacheKey(text, voice) to text }
            .toList()

        val staleSockets = synchronized(lock) {
            if (closed) return
            val currentKey = pendingPlayback?.key
            desiredKeys = candidates.mapTo(mutableSetOf(), Pair<String, String>::first).apply {
                if (currentKey != null) add(currentKey)
            }
            val stale = jobs.values
                .filter { it.preload && it.key !in desiredKeys }
                .onEach { jobs.remove(it.key) }
                .mapNotNull(SynthesisJob::socket)
            trimCacheLocked()
            stale
        }
        staleSockets.forEach(WebSocket::cancel)
        candidates.forEach { (key, text) ->
            val shouldStart = synchronized(lock) {
                key !in cache && key !in jobs && jobs.values.count(SynthesisJob::preload) < MAX_PRELOAD_REQUESTS
            }
            if (shouldStart) requestAudio(text, voice, preload = true)
        }
    }

    fun pause() {
        synchronized(lock) {
            if (closed || pendingPlayback == null) return
            paused = true
            player?.pause()
        }
    }

    fun resume() {
        val pending = synchronized(lock) {
            if (closed || !paused) return
            paused = false
            player?.let {
                it.resume()
                return
            }
            pendingPlayback
        } ?: return
        requestCachedOrSynthesize(pending)
    }

    fun stop() {
        val sockets = synchronized(lock) {
            generation++
            playbackSerial++
            pendingPlayback = null
            paused = false
            player?.release()
            player = null
            val active = jobs.values.mapNotNull(SynthesisJob::socket)
            jobs.clear()
            cache.clear()
            desiredKeys = emptySet()
            active
        }
        sockets.forEach(WebSocket::cancel)
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        stop()
        scope.cancel()
    }

    private fun requestCachedOrSynthesize(pending: PendingPlayback) {
        val cached = synchronized(lock) { cache[pending.key] }
        if (cached != null) {
            callbackExecutor.execute { handlePlaybackAudio(pending.key, Result.success(cached)) }
        }
        // Otherwise the original in-flight request still owns the callback and will resume playback.
    }

    private fun requestAudio(
        text: String,
        voice: String,
        preload: Boolean,
        callback: ((Result<ByteArray>) -> Unit)? = null,
    ) {
        var cached: ByteArray? = null
        var jobToStart: SynthesisJob? = null
        synchronized(lock) {
            if (closed) return
            cached = cache[cacheKey(text, voice)]
            if (cached != null) return@synchronized

            val key = cacheKey(text, voice)
            val existing = jobs[key]
            if (existing != null) {
                if (!preload) existing.preload = false
                if (callback != null) existing.callbacks += callback
                return
            }
            if (preload && jobs.values.count(SynthesisJob::preload) >= MAX_PRELOAD_REQUESTS) return
            jobToStart = SynthesisJob(key, text, voice, generation, preload).also { job ->
                if (callback != null) job.callbacks += callback
                jobs[key] = job
            }
        }
        cached?.let { callback?.invoke(Result.success(it)) }
        jobToStart?.let(::startSocket)
    }

    private fun startSocket(job: SynthesisJob) {
        val request = Request.Builder().url(endpoint).build()
        val buffer = ByteArrayOutputStream()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val active = synchronized(lock) {
                    jobs[job.key] === job && job.generation == generation && !closed
                }
                if (active) {
                    webSocket.send(TikTokProtocol.startMessage(job.text, job.voice))
                } else {
                    webSocket.cancel()
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                val event = runCatching { TikTokProtocol.event(data.toString(Charsets.UTF_8)) }.getOrNull()
                if (event == null) buffer.write(data) else handleEvent(webSocket, event)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                TikTokProtocol.event(text)?.let { handleEvent(webSocket, it) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                failJob(job, webSocket, t, retryable = true)
            }

            private fun handleEvent(webSocket: WebSocket, event: TikTokProtocol.Event) {
                when (event.name) {
                    "TaskEnd", "TaskFinished" -> completeJob(job, webSocket, buffer.toByteArray())
                    "TaskFailed" -> failJob(
                        job,
                        webSocket,
                        IllegalStateException(event.error),
                        retryable = true,
                    )
                }
            }
        }
        val socket = newWebSocket(request, listener)
        val keep = synchronized(lock) {
            val active = jobs[job.key] === job && job.generation == generation && !closed
            if (active) job.socket = socket
            active
        }
        if (!keep) socket.cancel()
    }

    private fun completeJob(job: SynthesisJob, socket: WebSocket, audio: ByteArray) {
        if (audio.isEmpty()) {
            failJob(
                job,
                socket,
                IllegalStateException("TikTok TTS returned empty audio"),
                retryable = false,
            )
            return
        }
        val callbacks = synchronized(lock) {
            if (jobs[job.key] !== job || job.socket !== socket || job.generation != generation || closed) return
            jobs.remove(job.key)
            socket.close(1000, null)
            cache[job.key] = audio
            trimCacheLocked()
            job.callbacks.toList()
        }
        callbacks.forEach { it(Result.success(audio)) }
    }

    private fun failJob(
        job: SynthesisJob,
        socket: WebSocket,
        error: Throwable,
        retryable: Boolean,
    ) {
        var retry = false
        var callbacks = emptyList<(Result<ByteArray>) -> Unit>()
        synchronized(lock) {
            if (jobs[job.key] !== job || job.socket !== socket || job.generation != generation || closed) return
            socket.cancel()
            job.socket = null
            if (retryable && job.retries < MAX_RETRIES) {
                job.retries++
                retry = true
            } else {
                jobs.remove(job.key)
                callbacks = job.callbacks.toList()
            }
        }
        if (retry) {
            scope.launch {
                delay(retryDelayMs)
                val active = synchronized(lock) {
                    jobs[job.key] === job && job.generation == generation && !closed
                }
                if (active) startSocket(job)
            }
        } else {
            callbacks.forEach { it(Result.failure(error)) }
        }
    }

    private fun handlePlaybackAudio(key: String, result: Result<ByteArray>) {
        val pending = synchronized(lock) {
            pendingPlayback?.takeIf { it.key == key && !closed }
        } ?: return
        result.fold(
            onSuccess = { audio ->
                val shouldPlay = synchronized(lock) { !paused && pendingPlayback?.serial == pending.serial }
                if (shouldPlay) playAudio(pending, audio)
            },
            onFailure = { error ->
                val active = synchronized(lock) {
                    (pendingPlayback?.serial == pending.serial).also { current ->
                        if (current) pendingPlayback = null
                    }
                }
                if (active) notifyPlaybackFailure(pending, error)
            },
        )
    }

    private fun playAudio(pending: PendingPlayback, audio: ByteArray) {
        val created = runCatching {
            playerFactory.create(audio, pending.rate, pending.pitch) {
                callbackExecutor.execute { finishPlayback(pending) }
            }
        }.getOrElse { error ->
            val active = synchronized(lock) {
                (pendingPlayback?.serial == pending.serial).also { current ->
                    if (current) pendingPlayback = null
                }
            }
            if (active) notifyPlaybackFailure(pending, error)
            return
        }
        val active = synchronized(lock) {
            val current = pendingPlayback?.serial == pending.serial && !paused && !closed
            if (current) player = created
            current
        }
        if (!active) {
            created.release()
            return
        }
        runCatching { created.play() }
            .onSuccess { pending.listener.onStart(pending.utteranceId) }
            .onFailure { error ->
                created.release()
                val active = synchronized(lock) {
                    if (player === created) player = null
                    (pendingPlayback?.serial == pending.serial).also { current ->
                        if (current) pendingPlayback = null
                    }
                }
                if (active) notifyPlaybackFailure(pending, error)
            }
    }

    private fun notifyPlaybackFailure(pending: PendingPlayback, error: Throwable) {
        pending.listener.onError(pending.utteranceId, error)
        pending.listener.onDone(pending.utteranceId)
    }

    private fun finishPlayback(pending: PendingPlayback) {
        val completedPlayer = synchronized(lock) {
            if (pendingPlayback?.serial != pending.serial || closed) return
            val currentPlayer = player
            player = null
            pendingPlayback = null
            currentPlayer
        }
        completedPlayer?.release()
        pending.listener.onDone(pending.utteranceId)
    }

    private fun trimCacheLocked() {
        if (desiredKeys.isNotEmpty()) {
            cache.keys.removeAll { it !in desiredKeys }
        }
        while (cache.size > MAX_CACHE_ENTRIES) {
            cache.remove(cache.entries.first().key)
        }
    }

    private fun cacheKey(text: String, voice: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$voice:$text".toByteArray())
        return "$voice:${digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }}"
    }

    internal fun cachedEntryCount(): Int = synchronized(lock) { cache.size }
    internal fun preloadRequestCount(): Int = synchronized(lock) { jobs.values.count(SynthesisJob::preload) }

    companion object {
        internal const val MAX_PRELOAD_REQUESTS = 5
        internal const val MAX_CACHE_ENTRIES = 6
        internal const val MAX_RETRIES = 3
        private const val DEFAULT_RETRY_DELAY_MS = 100L
    }
}
