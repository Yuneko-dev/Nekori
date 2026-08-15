package eu.kanade.tachiyomi.data.translation

import eu.kanade.tachiyomi.data.translation.engine.LlmTranslationEngine
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.translation.model.AIProvider
import tachiyomi.domain.translation.model.AIProviderType
import tachiyomi.domain.translation.model.AiErrorCode
import tachiyomi.domain.translation.model.AiExecutionConfig
import tachiyomi.domain.translation.model.LlmGenerationRequest
import tachiyomi.domain.translation.model.LlmOutputFormat
import tachiyomi.domain.translation.model.LlmResult
import tachiyomi.domain.translation.model.TranslationRequest
import tachiyomi.domain.translation.model.TranslationResult
import tachiyomi.domain.translation.service.TranslationPreferences

class LlmTranslationEngineTest {
    private val preferences = TranslationPreferences(InMemoryPreferenceStore())
    private val generator = mockk<LlmGenerator>()
    private val engine = LlmTranslationEngine(generator, preferences)

    @Test
    fun `a profile provider configures the engine`() {
        engine.isConfigured() shouldBe false
        engine.isConfigured(AiExecutionConfig(provider = provider(AIProviderType.CUSTOM_OPENAI))) shouldBe true
    }

    @Test
    fun `a profile provider needing a key is unconfigured until the key is present`() {
        val keyed = provider(AIProviderType.OPENAI)

        engine.isConfigured(AiExecutionConfig(provider = keyed)) shouldBe false
        engine.isConfigured(AiExecutionConfig(provider = keyed, apiKey = "secret")) shouldBe true
    }

    @Test
    fun `translation without profile config is rejected before generating`() = runTest {
        val result = engine.translate(TranslationRequest(listOf("text"), "en", "vi"))

        result shouldBe TranslationResult.Error(
            "No translation profile configuration",
            AiErrorCode.REQUEST_INVALID,
        )
    }

    @Test
    fun `a generation failure keeps its own code`() = runTest {
        coEvery { generator.generate(any(), any()) } returns
            LlmResult.Failure("HTTP 429: slow down", AiErrorCode.RATE_LIMITED)

        val result = engine.translate(request())

        result shouldBe TranslationResult.Error("HTTP 429: slow down", AiErrorCode.RATE_LIMITED)
    }

    @Test
    fun `structured output asks the provider for the translation schema`() = runTest {
        preferences.structuredOutput().set(true)
        val sent = captureRequest()

        engine.translate(request())

        sent().outputFormat shouldBe TranslationOutputSchema.format
    }

    @Test
    fun `marker mode asks for plain text instead of a schema`() = runTest {
        preferences.structuredOutput().set(false)
        val sent = captureRequest()

        engine.translate(request())

        sent().outputFormat shouldBe LlmOutputFormat.Text
    }

    @Test
    fun `a response the parser cannot read is a structured output error`() = runTest {
        preferences.structuredOutput().set(true)
        coEvery { generator.generate(any(), any()) } returns LlmResult.Success("""{"unexpected":true}""")

        val result = engine.translate(request(listOf("a", "b")))

        (result as TranslationResult.Error).errorCode shouldBe AiErrorCode.STRUCTURED_OUTPUT_INVALID
    }

    /** Captures the generation request the engine builds, so an assertion can read it back. */
    private fun captureRequest(): () -> LlmGenerationRequest {
        var captured: LlmGenerationRequest? = null
        coEvery { generator.generate(any(), any()) } answers {
            captured = secondArg()
            LlmResult.Success("""{"paragraphs":[{"i":0,"t":"ok"}]}""")
        }
        return { requireNotNull(captured) }
    }

    private fun request(texts: List<String> = listOf("text")) = TranslationRequest(
        texts = texts,
        sourceLanguage = "en",
        targetLanguage = "vi",
        config = AiExecutionConfig(provider = provider(AIProviderType.CUSTOM_OPENAI)),
    )

    private fun provider(type: AIProviderType) = AIProvider(
        id = "id",
        alias = "Provider",
        type = type,
        endpoint = "https://example.com/v1",
        model = "model",
    )
}
