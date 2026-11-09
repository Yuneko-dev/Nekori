package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import logcat.LogPriority
import logcat.logcat

object NovelPageLoader {

    private val intMapJsonCache: MutableMap<String, Map<String, Int>> =
        java.util.Collections.synchronizedMap(
            object : java.util.LinkedHashMap<String, Map<String, Int>>(64, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<String, Map<String, Int>>,
                ) = size > 64
            },
        )

    suspend fun awaitPageText(
        tag: String,
        page: ReaderPage,
        loader: PageLoader,
        timeoutMs: Long,
        scope: CoroutineScope,
    ): Boolean {
        if (!page.text.isNullOrBlank()) {
            logcat(LogPriority.DEBUG) { "$tag: page text already available, text.length=${page.text?.length ?: 0}" }
            return true
        }
        var loadJob: kotlinx.coroutines.Job? = null
        fun launchLoad(): kotlinx.coroutines.Job {
            // A closed reader scope cannot own queued work, even if this waiter is still active.
            scope.coroutineContext.ensureActive()
            return scope.launch(Dispatchers.IO) {
                try {
                    loader.loadPage(page)
                } catch (_: kotlinx.coroutines.CancellationException) {
                    // The load owner releases its claim when cancelled.
                }
            }
        }
        val initialStatus = page.status
        when (initialStatus) {
            is Page.State.Error -> {
                loader.retryPage(page)
                // A loader that resets the error owns its retry; the base implementation does not.
                if (page.tryClaim(initialStatus, Page.State.Queue)) loadJob = launchLoad()
            }
            Page.State.Ready -> if (page.tryClaim(initialStatus, Page.State.Queue)) loadJob = launchLoad()
            Page.State.Queue -> loadJob = launchLoad()
            else -> Unit
        }

        // Keep the first snapshot: an existing owner can release LoadPage before we subscribe.
        val restartOnQueue = initialStatus !is Page.State.Error || !loader.handlesPageRetries || loadJob != null
        fun settled(state: Page.State) = state is Page.State.Ready || state is Page.State.Error ||
            (restartOnQueue && state is Page.State.Queue && (loadJob == null || loadJob?.isCancelled == true))

        return try {
            val finalState = withTimeout(timeoutMs) {
                var state = page.statusFlow.first(::settled)
                while (state is Page.State.Queue) {
                    loadJob = launchLoad()
                    state = page.statusFlow.first(::settled)
                }
                state
            }

            when (finalState) {
                is Page.State.Ready -> {
                    logcat(LogPriority.DEBUG) { "$tag: page ready, text.length=${page.text?.length ?: 0}" }
                    !page.text.isNullOrBlank()
                }
                is Page.State.Error -> {
                    logcat(LogPriority.ERROR) { "$tag: page error: ${finalState.error.message}" }
                    false
                }
                else -> false
            }
        } finally {
            loadJob?.cancel()
        }
    }
}
