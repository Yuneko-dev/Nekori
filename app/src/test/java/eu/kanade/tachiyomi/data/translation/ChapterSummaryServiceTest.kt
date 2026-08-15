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
import tachiyomi.domain.translation.model.AiTaskProfile
import tachiyomi.domain.translation.model.AiTaskPurpose
import tachiyomi.domain.translation.model.LlmGenerationRequest
import tachiyomi.domain.translation.model.LlmOutputFormat
import tachiyomi.domain.translation.model.LlmResult
import tachiyomi.domain.translation.model.UserGuidelines
import tachiyomi.domain.translation.service.TranslationPreferences

class ChapterSummaryServiceTest {
    private val preferences = TranslationPreferences(InMemoryPreferenceStore())
    private val json = Json { ignoreUnknownKeys = true }
    private val aiSettings = AiSettingsStore(preferences, json)
    private val profiles = AiTaskProfileStore(preferences, json)
    private val generator = mockk<LlmGenerator>()
    private val service = ChapterSummaryService(generator, aiSettings, profiles, preferences)

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
            "This chapter has no text to summarize",
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
    fun `the target language and the profile guidelines reach the system prompt`() = runTest {
        aiSettings.saveProvider(provider, "secret")
        aiSettings.saveGuidelines(UserGuidelines("terse", "Terse", "Keep it short"))
        profiles.save(AiTaskProfile("summary", "Summary", guidelinesId = "terse"))
        profiles.assign(AiTaskPurpose.CHAPTER_SUMMARY, "summary")
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

    private val provider = AIProvider(
        id = "p1",
        alias = "Provider",
        type = AIProviderType.OPENAI,
        endpoint = "https://example.com/v1",
        model = "model",
    )
}
