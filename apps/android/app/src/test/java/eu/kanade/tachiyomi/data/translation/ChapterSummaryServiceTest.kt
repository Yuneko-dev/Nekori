package eu.kanade.tachiyomi.data.translation

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.translation.model.AIProvider
import tachiyomi.domain.translation.model.AIProviderType
import tachiyomi.domain.translation.model.AiErrorCode
import tachiyomi.domain.translation.model.LlmGenerationRequest
import tachiyomi.domain.translation.model.LlmOutputFormat
import tachiyomi.domain.translation.model.LlmResult
import tachiyomi.domain.translation.model.NovelAiTask
import tachiyomi.domain.translation.model.NovelGuidelinesChoice
import tachiyomi.domain.translation.model.NovelTarget
import tachiyomi.domain.translation.model.UserGuidelines
import tachiyomi.domain.translation.service.TranslationPreferences

class ChapterSummaryServiceTest {
    private val preferences = TranslationPreferences(InMemoryPreferenceStore())
    private val json = Json { ignoreUnknownKeys = true }
    private val aiSettings = AiSettingsStore(preferences, json)
    private val generator = mockk<LlmGenerator>()
    private val noContentMessage = "Localized empty chapter"
    private val service =
        ChapterSummaryService(generator, aiSettings, preferences, { "Invalid Guidelines" }) { noContentMessage }

    @Test
    fun `an unconfigured provider means no summary can be requested`() {
        service.isConfigured() shouldBe false

        aiSettings.saveProvider(provider, "secret")

        service.isConfigured() shouldBe true
    }

    @Test
    fun `an empty chapter fails without calling the provider`() = runTest {
        aiSettings.saveProvider(provider, "secret")

        val result = service.summarize("<p>   </p>")

        result shouldBe LlmResult.Failure(
            noContentMessage,
            AiErrorCode.REQUEST_INVALID,
        )
        coVerify(exactly = 0) { generator.generate(any(), any()) }
    }

    @Test
    fun `the chapter is sent as plain text in one request asking for prose`() = runTest {
        aiSettings.saveProvider(provider, "secret")
        val sent = captureRequest()

        service.summarize("<p>First line.</p><p>Second line.</p>")

        val request = sent()
        request.outputFormat shouldBe LlmOutputFormat.Text
        request.input shouldContain "First line."
        request.input shouldContain "Second line."
        request.input.contains("<p>") shouldBe false
        coVerify(exactly = 1) { generator.generate(any(), any()) }
    }

    @Test
    fun `the target language and the task guidelines reach the system prompt`() = runTest {
        aiSettings.saveProvider(provider, "secret")
        aiSettings.saveGuidelines(UserGuidelines("terse", "Terse", "Keep it short"))
        preferences.chapterSummaryGuidelinesId().set("terse")
        preferences.targetLanguage().set("vi")
        val sent = captureRequest()

        service.summarize("<p>Something happens.</p>")

        val prompt = sent().systemPrompt
        prompt shouldContain "Vietnamese"
        prompt shouldContain "Keep it short"
    }

    @Test
    fun `a transient failure is retried and a permanent one is not`() = runTest {
        aiSettings.saveProvider(provider, "secret")
        preferences.requestRetryCount().set(2)
        var calls = 0
        coEvery { generator.generate(any(), any()) } answers {
            calls++
            LlmResult.Failure("busy", AiErrorCode.RATE_LIMITED)
        }

        service.summarize("<p>Text</p>")
        calls shouldBe 3

        calls = 0
        coEvery { generator.generate(any(), any()) } answers {
            calls++
            LlmResult.Failure("bad key", AiErrorCode.API_KEY_INVALID)
        }

        service.summarize("<p>Text</p>")
        calls shouldBe 1
    }

    private fun captureRequest(): () -> LlmGenerationRequest {
        var captured: LlmGenerationRequest? = null
        coEvery { generator.generate(any(), any()) } answers {
            captured = secondArg()
            LlmResult.Success("A summary.")
        }
        return { requireNotNull(captured) }
    }

    @Test
    fun `summary uses its own novel choice without translation choice or corrupt library`() = runTest {
        aiSettings.saveProvider(provider, "secret")
        val target = NovelTarget(5, "/a")
        aiSettings.saveChoice(
            target,
            NovelAiTask.TRANSLATION,
            NovelGuidelinesChoice("custom", text = "Translation only"),
            "[]",
        )
        aiSettings.saveChoice(
            target,
            NovelAiTask.CHAPTER_SUMMARY,
            NovelGuidelinesChoice("custom", text = "Novel summary"),
            preferences.novelGuidelinesJson().get(),
        )
        preferences.userGuidelinesJson().set("broken")
        val sent = captureRequest()
        service.summarize("<p>Events</p>", target)
        sent().systemPrompt shouldContain "Novel summary"
        sent().systemPrompt.contains("Translation only") shouldBe false
    }

    @Test
    fun `corrupt novel choices fail before provider call`() = runTest {
        aiSettings.saveProvider(provider, "secret")
        preferences.novelGuidelinesJson().set("broken")
        val result = service.summarize("<p>Events</p>", NovelTarget(5, "/a"))
        (result as LlmResult.Failure).code shouldBe AiErrorCode.REQUEST_INVALID
        coVerify(exactly = 0) { generator.generate(any(), any()) }
    }

    private val provider = AIProvider(
        id = "p1",
        alias = "Provider",
        type = AIProviderType.OPENAI,
        endpoint = "https://example.com/v1",
        model = "model",
    )
}
