package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import eu.kanade.tachiyomi.data.translation.ChapterSummaryService
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.model.AIProvider
import tachiyomi.domain.translation.model.AIProviderType
import tachiyomi.domain.translation.model.AiExecutionConfig
import tachiyomi.domain.translation.model.LlmResult

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

    @Test
    fun existingSummarySurvivesInvalidSettings() {
        val service = mockk<ChapterSummaryService>()
        every { service.resolveConfig(any()) } throws IllegalArgumentException("Bad config")
        val commands = mutableListOf<ReaderCommand>()
        val controller = controller(service = service, executeCommand = { command, callback ->
            commands += command
            callback?.invoke("true")
        })
        controller.request(7)
        commands.map { it.method } shouldBe listOf("summary.focus")
        verify(exactly = 0) { service.resolveConfig(any()) }
    }

    @Test
    fun regenerateCreatesNewSummaryWhileNormalRequestOnlyFocusesExistingCard() {
        val config = AiExecutionConfig(
            provider = AIProvider("p", "P", AIProviderType.CUSTOM_OPENAI, "https://example.com/v1", "model"),
            guidelines = "Current guidelines",
        )
        val service = mockk<ChapterSummaryService>()
        every { service.resolveConfig(any()) } returns config
        coEvery { service.summarize(any(), any(), config) } returns LlmResult.Success("New summary")
        val commands = mutableListOf<ReaderCommand>()
        val controller = controller(
            service = service,
            executeCommand = { command, callback ->
                commands += command
                callback?.invoke("true")
            },
            chapterHtml = { "<p>Events</p>" },
        )
        controller.request(7)
        coVerify(exactly = 0) { service.summarize(any(), any(), any()) }
        controller.onAction(7, "regenerate")
        commands.map { it.method } shouldBe listOf("summary.focus", "summary.render", "summary.render")
        commands.last().payload["text"] shouldBe JsonPrimitive("New summary")
        coVerify(exactly = 1) { service.summarize(any(), any(), config) }
    }

    @Test
    fun summarySnapshotsBeforeSuspendedContentLoad() = runTest {
        val service = mockk<ChapterSummaryService>()
        val before = AiExecutionConfig(
            provider = AIProvider("p", "P", AIProviderType.CUSTOM_OPENAI, "https://example.com/v1", "model"),
            guidelines = "Before loading",
        )
        var current = before
        every { service.resolveConfig(any()) } answers { current }
        coEvery { service.summarize(any(), any(), any()) } answers {
            thirdArg<AiExecutionConfig>() shouldBe before
            LlmResult.Success("Summary")
        }
        val content = CompletableDeferred<String>()
        val commands = mutableListOf<ReaderCommand>()
        val controller = controller(
            service = service,
            executeCommand = { command, callback ->
                commands += command
                callback?.invoke("false")
            },
            chapterHtml = { content.await() },
        )
        controller.request(7)
        current = before.copy(guidelines = "Changed while loading")
        content.complete("<p>Events</p>")
        coVerify(exactly = 1) { service.summarize(any(), any(), before) }
        commands.last().payload["state"] shouldBe JsonPrimitive("ready")
    }

    @Test
    fun invalidSettingsFailRequestAndRegenerateWithoutLoadingContent() {
        val service = mockk<ChapterSummaryService>()
        every { service.resolveConfig(any()) } throws IllegalArgumentException("Invalid assignments")
        var loads = 0
        val commands = mutableListOf<ReaderCommand>()
        val controller = controller(
            service = service,
            executeCommand = { command, callback ->
                commands += command
                callback?.invoke("false")
            },
            chapterHtml = {
                loads++
                "<p>Events</p>"
            },
        )
        controller.request(7)
        controller.onAction(7, "regenerate")
        commands.filter { it.method == "summary.render" }.map { it.payload["state"] } shouldBe
            listOf(JsonPrimitive("failed"), JsonPrimitive("failed"))
        loads shouldBe 0
        coVerify(exactly = 0) { service.summarize(any(), any(), any()) }
    }

    private fun controller(
        executeCommand: (ReaderCommand, ((String?) -> Unit)?) -> Unit,
        chapterHtml: suspend (Long) -> String? = { null },
        onUnavailable: () -> Unit = {},
        service: ChapterSummaryService = mockk(),
    ): NovelWebViewSummaryController {
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
