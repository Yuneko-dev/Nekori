package eu.kanade.tachiyomi.data.translation

import eu.kanade.tachiyomi.data.translation.engine.parseProviderModels
import eu.kanade.tachiyomi.data.translation.engine.resolveProviderUrl
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.model.AIProvider
import tachiyomi.domain.translation.model.AIProviderType

class LlmTranslationEngineTest {

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

    private fun provider(type: AIProviderType) = AIProvider(
        id = "id",
        alias = "Provider",
        type = type,
        endpoint = "https://example.com/v1",
        model = "model",
    )
}
