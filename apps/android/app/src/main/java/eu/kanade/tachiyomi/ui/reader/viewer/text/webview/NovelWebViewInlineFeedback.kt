package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import android.content.Context
import android.util.Base64
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.ErrorFormatter
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.localized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

internal class NovelWebViewInlineFeedback(
    private val context: Context,
    private val scope: CoroutineScope,
    private val currentChapterId: () -> Long? = { null },
    private val executeCommand: (ReaderCommand) -> Unit,
) {
    private var pendingRetry: (() -> Unit)? = null
    private var renderJob: Job? = null

    fun showInlineError(message: String) {
        pendingRetry?.let {
            showInlineError(IllegalStateException(message), onRetry = it)
            return
        }
        showMessage(message, "error", currentChapterId())
    }

    fun showInfo(message: String, chapterId: Long? = currentChapterId()) {
        showMessage(message, "info", chapterId)
    }

    private fun showMessage(message: String, severity: String, chapterId: Long?) {
        beginRender()
        renderJob = scope.launch(Dispatchers.Main) {
            showFeedback(
                buildJsonObject {
                    put("kind", "generic")
                    put("severity", severity)
                    put("presentation", "boundary")
                    chapterId?.let { put("chapterId", it.toString()) }
                    put("message", message)
                    put("dismissLabel", context.stringResource(MR.strings.action_close))
                },
            )
        }
    }

    fun showInlineError(error: Throwable, chapterId: Long? = currentChapterId(), onRetry: () -> Unit) {
        beginRender()
        pendingRetry = onRetry
        val formatted = ErrorFormatter.format(error)
        renderJob = scope.launch(Dispatchers.Main) {
            showFeedback(
                buildJsonObject {
                    put("kind", "rich")
                    put("severity", "error")
                    put("presentation", "boundary")
                    chapterId?.let { put("chapterId", it.toString()) }
                    put("category", formatted.category.localized(context))
                    put("summary", formatted.summary)
                    put("retryLabel", context.stringResource(MR.strings.action_retry))
                    put("copyLabel", context.stringResource(MR.strings.action_copy_to_clipboard))
                    put("dismissLabel", context.stringResource(MR.strings.action_close))
                    put("detailsLabel", context.stringResource(MR.strings.action_show_errors))
                    put(
                        "traceBase64",
                        Base64.encodeToString(formatted.stackTrace.toByteArray(Charsets.UTF_8), Base64.NO_WRAP),
                    )
                },
            )
        }
    }

    fun retryPendingError() {
        val retry = pendingRetry ?: return
        pendingRetry = null
        retry()
    }

    fun clear() {
        renderJob?.cancel()
        renderJob = null
        pendingRetry = null
        executeCommand(ReaderCommand("feedback.clear"))
    }

    private fun showFeedback(payload: kotlinx.serialization.json.JsonObject) {
        executeCommand(ReaderCommand("feedback.show", payload))
    }

    private fun beginRender() {
        renderJob?.cancel()
        pendingRetry = null
    }
}
