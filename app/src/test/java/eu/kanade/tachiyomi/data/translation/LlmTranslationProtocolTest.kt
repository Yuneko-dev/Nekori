package eu.kanade.tachiyomi.data.translation

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.model.AIApiMode
import tachiyomi.domain.translation.model.AIProvider
import tachiyomi.domain.translation.model.AIProviderType
import tachiyomi.domain.translation.model.ReasoningEffort
import tachiyomi.domain.translation.model.TranslationContext
import tachiyomi.domain.translation.model.TranslationResult

class LlmTranslationProtocolTest {

    @Test
    fun `structured prompt uses json payload and custom guidelines`() {
        val prompt = LlmPromptBuilder.build(
            texts = listOf("One", "Two"),
            sourceLanguage = "en",
            targetLanguage = "vi",
            guidelines = "Keep honorifics.",
            structuredOutput = true,
        )

        prompt.system shouldContain "Expert Transcreator"
        prompt.system shouldContain "Keep honorifics."
        prompt.user shouldBe "[\"One\",\"Two\"]"
    }

    @Test
    fun `marker mode preserves paragraph order`() {
        val prompt = LlmPromptBuilder.build(
            texts = listOf("One", "Two"),
            sourceLanguage = "auto",
            targetLanguage = "vi",
            guidelines = "",
            structuredOutput = false,
        )

        prompt.system shouldContain "No specific guidelines."
        prompt.user shouldBe "One\n<br>\nTwo"
        LlmResponseParser.parseMarker(prompt.user, 2) shouldContainExactly listOf("One", "Two")
    }

    @Test
    fun `context keeps previous source and translation outside the user guidelines`() {
        val prompt = LlmPromptBuilder.build(
            texts = listOf("Current"),
            sourceLanguage = "en",
            targetLanguage = "vi",
            guidelines = "Keep names.",
            structuredOutput = true,
            context = TranslationContext(listOf("Before"), listOf("Trước đó")),
        )

        prompt.system shouldContain "Previous Context"
        prompt.system shouldContain "Before"
        prompt.system shouldContain "Trước đó"
        prompt.user shouldBe "[\"Current\"]"
    }

    @Test
    fun `structured response rejects wrong paragraph count`() {
        shouldThrow<InvalidStructuredOutputException> {
            LlmResponseParser.parseStructured("{\"paragraphs\":[\"only\"]}", expectedCount = 2)
        }
    }

    @Test
    fun `responses request omits temperature`() {
        val provider = AIProvider(
            id = "id",
            alias = "OpenAI",
            type = AIProviderType.OPENAI,
            endpoint = "https://api.openai.com/v1",
            model = "gpt-5",
            apiMode = AIApiMode.RESPONSES,
            temperature = 1.2f,
        )

        LlmRequestFactory.create(provider, "system", "user", structuredOutput = true)
            .body.toString().contains("temperature") shouldBe false
    }

    @Test
    fun `chat structured request uses response format and temperature`() {
        val body = LlmRequestFactory.create(
            AIProvider(
                id = "id",
                alias = "OpenAI",
                type = AIProviderType.OPENAI,
                endpoint = "https://api.openai.com/v1",
                model = "gpt-4o",
                apiMode = AIApiMode.CHAT_COMPLETIONS,
                temperature = 0.6f,
            ),
            "system",
            "user",
            structuredOutput = true,
        ).body.toString()

        body shouldContain "response_format"
        body shouldContain "temperature"
    }

    @Test
    fun `reasoning chat request omits temperature`() {
        val body = LlmRequestFactory.create(
            AIProvider(
                id = "id",
                alias = "OpenAI",
                type = AIProviderType.OPENAI,
                endpoint = "https://api.openai.com/v1",
                model = "reasoning-model",
                apiMode = AIApiMode.CHAT_COMPLETIONS,
                temperature = 1.4f,
                reasoning = true,
            ),
            "system",
            "user",
            structuredOutput = false,
        ).body.toString()

        body.contains("temperature") shouldBe false
        body shouldContain "reasoning_effort"
    }

    @Test
    fun `gemini structured request uses json mime and schema`() {
        val body = LlmRequestFactory.create(
            AIProvider(
                id = "id",
                alias = "Gemini",
                type = AIProviderType.GEMINI,
                endpoint = "https://generativelanguage.googleapis.com",
                model = "gemini-2.5-flash",
            ),
            "system",
            "user",
            structuredOutput = true,
        ).body.toString()

        body shouldContain "application/json"
        body shouldContain "responseSchema"
        body shouldContain "temperature"
        body.contains("additionalProperties") shouldBe false
    }

    @Test
    fun `gemini reasoning maps xhigh to high thinking level`() {
        val body = LlmRequestFactory.create(
            AIProvider(
                id = "id",
                alias = "Gemini",
                type = AIProviderType.GEMINI,
                endpoint = "https://generativelanguage.googleapis.com",
                model = "gemini-2.5-flash",
                reasoning = true,
                reasoningEffort = ReasoningEffort.XHIGH,
            ),
            "system",
            "user",
            structuredOutput = false,
        ).body.toString()

        body shouldContain "thinkingConfig"
        body shouldContain "HIGH"
    }

    @Test
    fun `retry policy does not retry invalid structured output`() = runTest {
        var calls = 0
        TranslationRetryPolicy.execute(retries = 5, sleeper = {}) {
            calls++
            TranslationResult.Error("invalid", TranslationResult.ErrorCode.STRUCTURED_OUTPUT_INVALID)
        }
        calls shouldBe 1
    }

    @Test
    fun `retry policy retries transient errors and uses fibonacci delays`() = runTest {
        val delays = mutableListOf<Long>()
        var calls = 0
        val result = TranslationRetryPolicy.execute(
            retries = 2,
            sleeper = { delays += it },
        ) {
            calls++
            if (calls < 3) {
                TranslationResult.Error("busy", TranslationResult.ErrorCode.RATE_LIMITED)
            } else {
                TranslationResult.Success(listOf("ok"))
            }
        }

        result shouldBe TranslationResult.Success(listOf("ok"))
        calls shouldBe 3
        delays shouldContainExactly listOf(1_000L, 2_000L)
    }

    @Test
    fun `retry policy never retries cancellation`() = runTest {
        var calls = 0
        shouldThrow<CancellationException> {
            TranslationRetryPolicy.execute(retries = 5, sleeper = {}) {
                calls++
                throw CancellationException()
            }
        }
        calls shouldBe 1
    }

    @Test
    fun `retry count is clamped to five`() = runTest {
        var calls = 0
        val delays = mutableListOf<Long>()

        TranslationRetryPolicy.execute(retries = 99, sleeper = { delays += it }) {
            calls++
            TranslationResult.Error("offline", TranslationResult.ErrorCode.NETWORK_ERROR)
        }

        calls shouldBe 6
        delays shouldContainExactly listOf(1_000L, 2_000L, 3_000L, 5_000L, 8_000L)
    }
}
