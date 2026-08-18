package eu.kanade.tachiyomi.data.translation

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.model.AIProvider
import tachiyomi.domain.translation.model.AIProviderType
import tachiyomi.domain.translation.model.AiErrorCode
import tachiyomi.domain.translation.model.AiExecutionConfig
import tachiyomi.domain.translation.model.LlmGenerationRequest
import tachiyomi.domain.translation.model.LlmResult

class LlmGeneratorTest {

    @Test
    fun `translation timeout applies to the whole LLM request`() {
        val client = OkHttpClient().withTranslationTimeout(12_345)

        client.connectTimeoutMillis shouldBe 12_345
        client.readTimeoutMillis shouldBe 12_345
        client.writeTimeoutMillis shouldBe 12_345
        client.callTimeoutMillis shouldBe 12_345
    }

    @Test
    fun `translation gets its own concurrency budget but keeps the app's dns and sockets`() {
        val appClient = OkHttpClient()
        val client = appClient.withTranslationTimeout(12_345)

        // OkHttp's default per-host cap is 5; chunks all target one provider host, so leaving the
        // app's dispatcher in place held the parallelism slider at 5 no matter what it read.
        client.dispatcher.maxRequestsPerHost shouldBe TranslationService.MAX_PARALLEL_TRANSLATIONS
        (client.dispatcher === appClient.dispatcher) shouldBe false

        // DoH resolver and the DPI-bypass socket factory are user network settings and must still
        // come from the app client. newBuilder() carries them; a bare OkHttpClient() would not.
        (client.dns === appClient.dns) shouldBe true
        (client.socketFactory === appClient.socketFactory) shouldBe true
    }

    @Test
    fun `no provider family puts the api key in the query`() {
        listOf(AIProviderType.GEMINI, AIProviderType.OPENAI).forEach { type ->
            val url = resolveProviderUrl(provider(type), "/models")

            url.toString() shouldBe "https://example.com/v1/models"
            url.queryParameter("key") shouldBe null
        }
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

    @Test
    fun `an incomplete provider fails before networking`() = runTest {
        val missingEndpoint = AiExecutionConfig(
            provider = provider(AIProviderType.OPENAI).copy(endpoint = ""),
            apiKey = "secret",
        )
        val missingModel = AiExecutionConfig(
            provider = provider(AIProviderType.OPENAI).copy(model = ""),
            apiKey = "secret",
        )

        generator().generate(missingEndpoint, request) shouldBe LlmResult.Failure(
            "Provider endpoint is missing for Provider",
            AiErrorCode.REQUEST_INVALID,
        )
        generator().generate(missingModel, request) shouldBe LlmResult.Failure(
            "Model is missing for Provider",
            AiErrorCode.REQUEST_INVALID,
        )
    }

    // Never touched on these paths: unstubbed mocks prove the guards run before any client is built.
    private fun generator() = LlmGenerator(networkHelper = mockk(), json = Json, preferences = mockk())

    private val request = LlmGenerationRequest(systemPrompt = "system", input = "input")

    private fun provider(type: AIProviderType) = AIProvider(
        id = "id",
        alias = "Provider",
        type = type,
        endpoint = "https://example.com/v1",
        model = "model",
    )
}
