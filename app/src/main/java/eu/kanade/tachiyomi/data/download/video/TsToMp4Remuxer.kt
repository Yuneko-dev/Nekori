package eu.kanade.tachiyomi.data.download.video

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.hippo.unifile.UniFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File

/** Rewraps supported MPEG-TS streams as MP4 without re-encoding. */
@OptIn(UnstableApi::class)
internal object TsToMp4Remuxer {

    private const val LOG_TAG = "TsToMp4Remuxer"

    /** Returns null when the source should remain `.ts`. */
    suspend fun remux(context: Context, source: UniFile, directory: UniFile, targetName: String): UniFile? {
        val scratch = File.createTempFile("tsundoku-remux-", ".mp4", context.cacheDir)
        try {
            try {
                export(context, source, scratch)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                logcat(LogPriority.WARN, error) { "$LOG_TAG: export failed" }
                return null
            }

            directory.findFile(targetName)?.delete()
            val target = directory.createFile(targetName) ?: return null
            return runCatching {
                scratch.inputStream().use { input ->
                    target.openOutputStream().use { output -> input.copyTo(output, COPY_BUFFER_BYTES) }
                }
                target
            }.onFailure {
                target.delete()
                logcat(LogPriority.WARN, it) { "$LOG_TAG: unable to save $targetName" }
            }.getOrNull()
        } finally {
            scratch.delete()
        }
    }

    private suspend fun export(context: Context, source: UniFile, target: File) {
        withContext(Dispatchers.Main.immediate) {
            val finished = CompletableDeferred<Unit>()
            val transformer = Transformer.Builder(context)
                .setUsePlatformDiagnostics(false)
                .addListener(
                    object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            finished.complete(Unit)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException,
                        ) {
                            finished.completeExceptionally(exportException)
                        }
                    },
                )
                .build()
            try {
                transformer.start(MediaItem.fromUri(source.uri), target.absolutePath)
                finished.await()
            } finally {
                if (!finished.isCompleted) transformer.cancel()
            }
        }
    }

    private const val COPY_BUFFER_BYTES = 256 * 1024
}
