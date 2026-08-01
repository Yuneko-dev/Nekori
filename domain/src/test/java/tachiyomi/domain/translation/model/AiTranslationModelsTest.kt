package tachiyomi.domain.translation.model

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class AiTranslationModelsTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun `provider round trips through json`() {
        val provider = AIProvider(
            id = "provider-id",
            alias = "Local",
            type = AIProviderType.CUSTOM_OPENAI,
            endpoint = "http://localhost:1234/v1",
            model = "model",
            apiMode = AIApiMode.CHAT_COMPLETIONS,
            temperature = 0.7f,
            customHeaders = listOf(AIHeader("X-Test", "yes")),
        )

        json.decodeFromString<AIProvider>(json.encodeToString(provider)) shouldBe provider
    }

    @Test
    fun `custom headers reject unsafe and duplicate names`() {
        val result = validateCustomHeaders(
            listOf(
                AIHeader("X-Test", "one"),
                AIHeader("x-test", "two"),
                AIHeader("Host", "example.com"),
                AIHeader("Safe", "bad\nvalue"),
            ),
        )

        result.errors.joinToString() shouldContain "Duplicate"
        result.errors.joinToString() shouldContain "Host"
        result.errors.joinToString() shouldContain "newlines"
    }

    @Test
    fun `custom headers override defaults case insensitively`() {
        mergeHeaders(
            defaults = mapOf("Authorization" to "Bearer old", "Content-Type" to "application/json"),
            custom = listOf(AIHeader("authorization", "Bearer new"), AIHeader("X-App", "Tsundoku")),
        ).entries.map { it.key to it.value } shouldContainExactly listOf(
            "Content-Type" to "application/json",
            "authorization" to "Bearer new",
            "X-App" to "Tsundoku",
        )
    }

    @Test
    fun `default prompt cannot be deleted`() {
        SystemPrompt.DEFAULT.deletable shouldBe false
    }

    @Test
    fun `provider presets expose the correct api behavior`() {
        AIProviderType.OPENAI.apiFamily shouldBe AIApiFamily.OPENAI_COMPATIBLE
        AIProviderType.OPENAI.defaultEndpoint shouldBe "https://api.openai.com/v1"
        AIProviderType.OPENAI.endpointEditable shouldBe false
        AIProviderType.OPENAI.supportsApiMode shouldBe true

        AIProviderType.GEMINI.apiFamily shouldBe AIApiFamily.GEMINI
        AIProviderType.GEMINI.defaultEndpoint shouldBe "https://generativelanguage.googleapis.com"
        AIProviderType.GEMINI.endpointEditable shouldBe true
        AIProviderType.GEMINI.supportsApiMode shouldBe false

        AIProviderType.CUSTOM_OPENAI.endpointEditable shouldBe true
        AIProviderType.CUSTOM_OPENAI.supportsApiMode shouldBe true
    }
}
