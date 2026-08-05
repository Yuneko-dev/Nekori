package tachiyomi.tts.tiktok

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import kotlin.math.max

internal fun interface PcmPlayerFactory {
    fun create(data: ByteArray, rate: Float, pitch: Float, onDone: () -> Unit): PcmPlayer
}

internal interface PcmPlayer {
    val isPlaying: Boolean
    fun play()
    fun pause()
    fun resume()
    fun release()
}

internal class AudioTrackPcmPlayer(
    data: ByteArray,
    rate: Float,
    pitch: Float,
    private val onDone: () -> Unit,
) : PcmPlayer {
    private var released = false
    private val track = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(TikTokProtocol.SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
        )
        .setBufferSizeInBytes(max(data.size, minimumBufferSize()))
        .setTransferMode(AudioTrack.MODE_STATIC)
        .build()

    init {
        try {
            check(track.write(data, 0, data.size) == data.size) { "Unable to write TikTok TTS audio" }
            runCatching {
                track.playbackParams = PlaybackParams()
                    .setSpeed(rate.coerceIn(MIN_PLAYBACK_VALUE, MAX_PLAYBACK_VALUE))
                    .setPitch(pitch.coerceIn(MIN_PLAYBACK_VALUE, MAX_PLAYBACK_VALUE))
            }
            track.setNotificationMarkerPosition(data.size / PCM_BYTES_PER_FRAME)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(track: AudioTrack) {
                    release()
                    onDone()
                }

                override fun onPeriodicNotification(track: AudioTrack) = Unit
            })
        } catch (error: Throwable) {
            track.release()
            throw error
        }
    }

    override val isPlaying: Boolean
        get() = !released && track.playState == AudioTrack.PLAYSTATE_PLAYING

    override fun play() = track.play()

    override fun pause() {
        if (!released) track.pause()
    }

    override fun resume() {
        if (!released) track.play()
    }

    override fun release() {
        if (released) return
        released = true
        runCatching { track.stop() }
        track.setPlaybackPositionUpdateListener(null)
        track.release()
    }

    companion object {
        private const val PCM_BYTES_PER_FRAME = 2
        private const val MIN_PLAYBACK_VALUE = 0.5f
        private const val MAX_PLAYBACK_VALUE = 6f

        private fun minimumBufferSize() = AudioTrack.getMinBufferSize(
            TikTokProtocol.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
    }
}
