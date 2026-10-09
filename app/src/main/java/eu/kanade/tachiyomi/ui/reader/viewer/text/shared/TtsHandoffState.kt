package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/** Main-thread owned preparation shared by native TTS progress and the WebView load threshold. */
class TtsHandoffState<P>(private val scope: CoroutineScope) {

    private var anchorChapterId: Long? = null
    private var task: Deferred<P?>? = null

    fun prefetch(anchorChapterId: Long, progress: Float, threshold: Float, prepare: suspend () -> P?) {
        if (progress < threshold) return
        if (this.anchorChapterId == anchorChapterId && task != null) return
        cancel()
        this.anchorChapterId = anchorChapterId
        task = scope.async { prepare() }
    }

    suspend fun take(anchorChapterId: Long): P? {
        if (this.anchorChapterId != anchorChapterId) {
            cancel()
            return null
        }
        val pending = task ?: return null
        return try {
            pending.await()
        } finally {
            pending.cancel()
            if (task === pending) {
                task = null
                this.anchorChapterId = null
            }
        }
    }

    fun cancel() {
        task?.cancel()
        task = null
        anchorChapterId = null
    }
}
