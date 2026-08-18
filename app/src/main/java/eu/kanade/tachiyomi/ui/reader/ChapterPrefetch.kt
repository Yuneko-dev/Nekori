package eu.kanade.tachiyomi.ui.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Runs at most one background job, for one chapter at a time.
 *
 * Asking again for the chapter already in flight is a no-op. That is the whole point: the
 * translation cache only rejects a repeat once the first run has *finished*, so two overlapping
 * runs would each pay the provider for the same chapter.
 *
 * Re-targeting cancels whatever was running, because the reader has moved and the old chapter is no
 * longer the one about to be opened.
 */
internal class ChapterPrefetch(private val scope: CoroutineScope) {

    private var job: Job? = null
    private var chapterId: Long? = null

    @Synchronized
    fun start(chapterId: Long, block: suspend () -> Unit) {
        if (this.chapterId == chapterId && job?.isActive == true) return
        job?.cancel()
        this.chapterId = chapterId
        job = scope.launch { block() }
    }

    @Synchronized
    fun cancel() {
        job?.cancel()
        job = null
        chapterId = null
    }
}
