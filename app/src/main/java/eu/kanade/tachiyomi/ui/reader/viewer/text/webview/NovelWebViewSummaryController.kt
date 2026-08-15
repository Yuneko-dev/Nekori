package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import eu.kanade.tachiyomi.data.translation.ChapterSummaryService
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.quoteForJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import tachiyomi.domain.translation.model.LlmResult

/**
 * Owns the chapter summary cards shown in the reader.
 *
 * Results live only in the page's DOM and in [jobs]: no cache, no backup, no migration. A reload, a
 * chapter leaving the infinite-scroll DOM, or the viewer being destroyed cancels the job and drops
 * the summary - regenerating is one tap, and a stale summary of a chapter the reader has moved past
 * is worth less than the storage it would need.
 */
internal class NovelWebViewSummaryController(
    private val scope: CoroutineScope,
    private val service: ChapterSummaryService,
    private val labels: Labels,
    private val evaluateJs: (String, ((String) -> Unit)?) -> Unit,
    /** Chapter HTML as loaded from the source, never the translated or edited DOM. */
    private val chapterHtml: suspend (Long) -> String?,
    private val onUnconfigured: () -> Unit,
) {
    data class Labels(
        val title: String,
        val loading: String,
        val regenerate: String,
        val close: String,
        val cancel: String,
        val contentUnavailable: String,
    )

    private val jobs = mutableMapOf<Long, Job>()

    /**
     * Shows the summary for [chapterId], starting one if there is none.
     *
     * An existing card is only scrolled to: pressing the menu item twice must not throw away a
     * finished summary or start a second request for the same chapter.
     */
    fun request(chapterId: Long) {
        if (!service.isConfigured()) {
            onUnconfigured()
            return
        }
        evaluateJs(call("focus", "'$chapterId'")) { focused ->
            if (focused != "true") start(chapterId)
        }
    }

    fun onAction(chapterId: Long, action: String) {
        when (action) {
            ACTION_CANCEL -> cancel(chapterId)
            ACTION_CLOSE -> {
                cancel(chapterId)
                evaluateJs(call("remove", "'$chapterId'"), null)
            }
            ACTION_REGENERATE -> start(chapterId)
        }
    }

    /** Drops the summary for a chapter leaving the page, so its job cannot outlive its card. */
    fun cancel(chapterId: Long) {
        jobs.remove(chapterId)?.cancel()
    }

    fun cancelAll() {
        jobs.values.forEach(Job::cancel)
        jobs.clear()
    }

    private fun start(chapterId: Long) {
        // Regenerating replaces an in-flight request, so a late response cannot overwrite a newer one.
        jobs.remove(chapterId)?.cancel()
        render(chapterId, STATE_LOADING)
        jobs[chapterId] = scope.launch {
            val html = chapterHtml(chapterId)
            val result = if (html.isNullOrBlank()) {
                LlmResult.Failure(labels.contentUnavailable)
            } else {
                service.summarize(html)
            }
            jobs.remove(chapterId)
            when (result) {
                is LlmResult.Success -> render(chapterId, STATE_READY, result.text)
                is LlmResult.Failure -> render(chapterId, STATE_FAILED, result.message)
            }
        }
    }

    private fun render(chapterId: Long, state: String, text: String = "") {
        val args = "'$chapterId', ${quoteForJson(state)}, ${quoteForJson(text)}, ${labels.toJson()}"
        evaluateJs(call("render", args), null)
    }

    private fun call(method: String, args: String) =
        "window.__tsundokuSummary && window.__tsundokuSummary.$method($args)"

    private fun Labels.toJson() = buildString {
        append("{\"title\":").append(quoteForJson(title))
        append(",\"loading\":").append(quoteForJson(loading))
        append(",\"regenerate\":").append(quoteForJson(regenerate))
        append(",\"close\":").append(quoteForJson(close))
        append(",\"cancel\":").append(quoteForJson(cancel))
        append('}')
    }

    companion object {
        const val ACTION_CANCEL = "cancel"
        const val ACTION_CLOSE = "close"
        const val ACTION_REGENERATE = "regenerate"

        private const val STATE_LOADING = "loading"
        private const val STATE_READY = "ready"
        private const val STATE_FAILED = "failed"
    }
}
