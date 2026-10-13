package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import eu.kanade.tachiyomi.data.translation.ChapterSummaryService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tachiyomi.domain.translation.model.LlmResult

/** Owns summary requests while the page runtime owns summary-card DOM. */
internal class NovelWebViewSummaryController(
    private val scope: CoroutineScope,
    private val service: ChapterSummaryService,
    private val labels: Labels,
    private val executeCommand: (ReaderCommand, ((String?) -> Unit)?) -> Unit,
    /** Chapter HTML as loaded from source, never the translated or edited DOM. */
    private val chapterHtml: suspend (Long) -> String?,
    private val onUnconfigured: () -> Unit,
    private val onUnavailable: () -> Unit,
) {
    data class Labels(
        val title: String,
        val loading: String,
        val regenerate: String,
        val close: String,
        val cancel: String,
        val cancelled: String,
        val contentUnavailable: String,
    )

    private val jobs = mutableMapOf<Long, Job>()

    fun request(chapterId: Long) {
        if (!service.isConfigured()) {
            onUnconfigured()
            return
        }
        executeCommand(
            command("summary.focus") { put("chapterId", chapterId.toString()) },
        ) { result ->
            when (parseBooleanResult(result)) {
                true -> Unit
                false -> start(chapterId)
                null -> onUnavailable()
            }
        }
    }

    fun onAction(chapterId: Long, action: String) {
        when (action) {
            ACTION_CANCEL -> {
                cancel(chapterId)
                render(chapterId, STATE_CANCELLED, labels.cancelled)
            }
            ACTION_CLOSE -> {
                cancel(chapterId)
                executeCommand(command("summary.remove") { put("chapterId", chapterId.toString()) }, null)
            }
            ACTION_REGENERATE -> start(chapterId)
        }
    }

    fun cancel(chapterId: Long) {
        jobs.remove(chapterId)?.cancel()
    }

    private fun start(chapterId: Long) {
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
        executeCommand(
            command("summary.render") {
                put("chapterId", chapterId.toString())
                put("state", state)
                put("text", text)
                put("labels", labels.toJson())
            },
            null,
        )
    }

    private fun Labels.toJson() = buildJsonObject {
        put("title", title)
        put("loading", loading)
        put("regenerate", regenerate)
        put("close", close)
        put("cancel", cancel)
        put("cancelled", cancelled)
        put("contentUnavailable", contentUnavailable)
    }

    private fun command(
        method: String,
        payload: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ) = ReaderCommand(method, buildJsonObject(payload))

    companion object {
        private const val ACTION_CANCEL = "cancel"
        private const val ACTION_CLOSE = "close"
        private const val ACTION_REGENERATE = "regenerate"

        private const val STATE_LOADING = "loading"
        private const val STATE_READY = "ready"
        private const val STATE_FAILED = "failed"
        private const val STATE_CANCELLED = "cancelled"

        private fun parseBooleanResult(result: String?): Boolean? =
            runCatching {
                when (val value = result?.let(Json::parseToJsonElement)) {
                    JsonNull -> null
                    is JsonPrimitive -> value.booleanOrNull
                    else -> null
                }
            }.getOrNull()
    }
}
