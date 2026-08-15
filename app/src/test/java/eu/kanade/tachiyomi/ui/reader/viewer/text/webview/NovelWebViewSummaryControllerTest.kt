package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import eu.kanade.tachiyomi.data.translation.ChapterSummaryService
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.Test

class NovelWebViewSummaryControllerTest {

    @Test
    fun `a document without the page API does not start a summary`() {
        var chapterLoads = 0
        var unavailableCalls = 0
        val scripts = mutableListOf<String>()
        val controller = controller(
            evaluateJs = { script, callback ->
                scripts += script
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
        scripts.single() shouldContain ".focus('7')"
    }

    @Test
    fun `cancel replaces loading controls with the cancelled state`() {
        val scripts = mutableListOf<String>()
        val controller = controller(
            evaluateJs = { script, _ -> scripts += script },
        )

        controller.onAction(7, "cancel")

        scripts.single() shouldContain "cancelled"
        scripts.single() shouldContain "Summary cancelled"
    }

    private fun controller(
        evaluateJs: (String, ((String) -> Unit)?) -> Unit,
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
            evaluateJs = evaluateJs,
            chapterHtml = chapterHtml,
            onUnconfigured = {},
            onUnavailable = onUnavailable,
        )
    }
}
