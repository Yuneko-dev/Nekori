package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import eu.kanade.tachiyomi.data.translation.ChapterSummaryService
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

class NovelWebViewSummaryControllerTest {

    @Test
    fun missingSummaryApiDoesNotStartARequest() {
        var chapterLoads = 0
        var unavailableCalls = 0
        val commands = mutableListOf<ReaderCommand>()
        val controller = controller(
            executeCommand = { command, callback ->
                commands += command
                callback?.invoke("null")
            },
            chapterHtml = {
                chapterLoads++
                "<p>Chapter</p>"
            },
            onUnavailable = { unavailableCalls++ },
        )

        controller.request(7)

        chapterLoads shouldBe 0
        unavailableCalls shouldBe 1
        commands.single().method shouldBe "summary.focus"
        commands.single().payload["chapterId"] shouldBe JsonPrimitive("7")
    }

    @Test
    fun cancelSendsStructuredCancelledSummaryState() {
        val commands = mutableListOf<ReaderCommand>()
        val controller = controller(executeCommand = { command, _ -> commands += command })

        controller.onAction(7, "cancel")

        commands.single().method shouldBe "summary.render"
        commands.single().payload["chapterId"] shouldBe JsonPrimitive("7")
        commands.single().payload["state"] shouldBe JsonPrimitive("cancelled")
        commands.single().payload["text"] shouldBe JsonPrimitive("Summary cancelled")
    }

    private fun controller(
        executeCommand: (ReaderCommand, ((String?) -> Unit)?) -> Unit,
        chapterHtml: suspend (Long) -> String? = { null },
        onUnavailable: () -> Unit = {},
    ): NovelWebViewSummaryController {
        val service = mockk<ChapterSummaryService>()
        every { service.isConfigured() } returns true
        return NovelWebViewSummaryController(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            service = service,
            labels = NovelWebViewSummaryController.Labels(
                title = "Summary",
                loading = "Loading",
                regenerate = "Regenerate",
                close = "Close",
                cancel = "Cancel",
                cancelled = "Summary cancelled",
                contentUnavailable = "No content",
            ),
            executeCommand = executeCommand,
            chapterHtml = chapterHtml,
            onUnconfigured = {},
            onUnavailable = onUnavailable,
        )
    }
}
