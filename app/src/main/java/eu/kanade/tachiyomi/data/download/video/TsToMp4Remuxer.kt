package eu.kanade.tachiyomi.data.download.video

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.hippo.unifile.UniFile
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.nio.ByteBuffer

/** Rewraps MPEG-TS as MP4 without re-encoding; failure leaves the playable `.ts` untouched. */
internal object TsToMp4Remuxer {

    private const val LOG_TAG = "TsToMp4Remuxer"

    /** Returns null when the source should remain `.ts`. */
    fun remux(context: Context, source: UniFile, directory: UniFile, targetName: String): UniFile? {
        directory.findFile(targetName)?.delete()
        val target = directory.createFile(targetName)
        if (target == null) {
            logcat(LogPriority.WARN) { "$LOG_TAG: unable to create $targetName" }
            return null
        }

        // The document fd is tried first because it writes straight into the download directory.
        // MediaMuxer needs a seekable read-write fd though, and not every storage provider grants
        // one, so a plain cache file is kept as the fallback at the cost of one extra copy.
        if (attempt("saf-fd") { muxToDocument(context, source, target) }) return target
        if (attempt("cache-file") { muxViaCache(context, source, target) }) return target

        target.delete()
        return null
    }

    private inline fun attempt(stage: String, block: () -> Boolean): Boolean {
        return try {
            block().also { if (!it) logcat(LogPriority.WARN) { "$LOG_TAG: $stage produced no output" } }
        } catch (error: Throwable) {
            logcat(LogPriority.WARN, error) { "$LOG_TAG: $stage failed" }
            false
        }
    }

    private fun muxToDocument(context: Context, source: UniFile, target: UniFile): Boolean {
        val output = context.contentResolver.openFileDescriptor(target.uri, "rw") ?: return false
        return output.use {
            val muxer = MediaMuxer(output.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            copyTracks(context, source, muxer)
        }
    }

    private fun muxViaCache(context: Context, source: UniFile, target: UniFile): Boolean {
        val scratch = File.createTempFile("tsundoku-remux-", ".mp4", context.cacheDir)
        try {
            val muxer = MediaMuxer(scratch.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (!copyTracks(context, source, muxer)) return false
            scratch.inputStream().use { input ->
                target.openOutputStream().use { output -> input.copyTo(output, COPY_BUFFER_BYTES) }
            }
            return true
        } finally {
            scratch.delete()
        }
    }

    private fun copyTracks(context: Context, source: UniFile, muxer: MediaMuxer): Boolean {
        val extractor = MediaExtractor()
        try {
            // The Uri overload is used rather than a raw descriptor: it is the documented path for
            // content:// sources and lets the platform pick how to open them.
            extractor.setDataSource(context, source.uri, null)

            val trackIndices = mutableMapOf<Int, Int>()
            var bufferSize = MIN_BUFFER_BYTES
            for (track in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(track)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    bufferSize = maxOf(bufferSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                }
                trackIndices[track] = muxer.addTrack(format)
                extractor.selectTrack(track)
            }
            if (trackIndices.isEmpty()) {
                logcat(LogPriority.WARN) { "$LOG_TAG: no audio or video tracks in ${extractor.trackCount}" }
                return false
            }

            muxer.start()
            val buffer = ByteBuffer.allocate(bufferSize)
            val info = MediaCodec.BufferInfo()
            // Shift the broadcaster clock to zero without changing audio/video offsets.
            var firstSampleTimeUs = -1L
            var wroteSample = false

            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val target = trackIndices[extractor.sampleTrackIndex]
                if (target != null) {
                    val sampleTimeUs = extractor.sampleTime
                    if (firstSampleTimeUs < 0) firstSampleTimeUs = sampleTimeUs
                    info.set(
                        0,
                        size,
                        (sampleTimeUs - firstSampleTimeUs).coerceAtLeast(0L),
                        extractor.sampleFlags,
                    )
                    muxer.writeSampleData(target, buffer, info)
                    wroteSample = true
                }
                extractor.advance()
            }

            if (!wroteSample) {
                logcat(LogPriority.WARN) { "$LOG_TAG: extractor returned no samples" }
                return false
            }
            muxer.stop()
            return true
        } finally {
            runCatching { muxer.release() }
            runCatching { extractor.release() }
        }
    }

    private const val MIN_BUFFER_BYTES = 1 shl 20
    private const val COPY_BUFFER_BYTES = 256 * 1024
}
