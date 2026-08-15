package eu.kanade.tachiyomi.data.translation

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.model.AIProvider
import tachiyomi.domain.translation.model.AIProviderType
import tachiyomi.domain.translation.model.AiErrorCode
import tachiyomi.domain.translation.model.AiExecutionConfig
import tachiyomi.domain.translation.model.LlmGenerationRequest
import tachiyomi.domain.translation.model.LlmResult

class LlmGeneratorTest {

    @Test
    fun `gemini endpoint encodes api key`() {
        val url = resolveProviderUrl(provider(AIProviderType.GEMINI), "/v1beta/models", "a+b&c")

        url.queryParameter("key") shouldBe "a+b&c"
    }

    @Test
    fun `openai endpoint does not put api key in query`() {
        val url = resolveProviderUrl(provider(AIProviderType.OPENAI), "/models", "secret")

        url.toString() shouldBe "https://example.com/v1/models"
        url.queryParameter("key") shouldBe null
    }

    @Test
    fun `model loading parser supports openai and gemini responses`() {
        parseProviderModels(Json, """{"data":[{"id":"z"},{"id":"a"}]}""") shouldContainExactly listOf("a", "z")
        parseProviderModels(
            Json,
            """{"models":[{"name":"models/gemini-pro"},{"name":"models/gemini-flash"}]}""",
        ) shouldContainExactly listOf("gemini-flash", "gemini-pro")
    }

    @Test
    fun `a request without a provider fails before networking`() = runTest {
        val result = generator().generate(AiExecutionConfig(), request)

        result shouldBe LlmResult.Failure("No AI provider configured", AiErrorCode.REQUEST_INVALID)
    }

    @Test
    fun `a provider needing a key fails before networking when the key is missing`() = runTest {
        val config = AiExecutionConfig(provider = provider(AIProviderType.OPENAI))

        val result = generator().generate(config, request)

        result shouldBe LlmResult.Failure("API key is missing for Provider", AiErrorCode.API_KEY_MISSING)
    }

    // Never touched on these paths: the client is created lazily, so an unstubbed mock proves the
    // guards run before any request is built.
    private fun generator() = LlmGenerator(networkHelper = mockk(), json = Json)

    private val request = LlmGenerationRequest(systemPrompt = "system", input = "input")

    private fun provider(type: AIProviderType) = AIProvider(
        id = "id",
        alias = "Provider",
        type = type,
        endpoint = "https://example.com/v1",
        model = "model",
    )
}
