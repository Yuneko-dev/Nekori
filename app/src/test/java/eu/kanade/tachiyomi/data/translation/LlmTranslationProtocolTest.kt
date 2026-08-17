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
import tachiyomi.domain.translation.model.AiErrorCode
import tachiyomi.domain.translation.model.LlmGenerationRequest
import tachiyomi.domain.translation.model.LlmOutputFormat
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
        prompt.system shouldContain "text array"
        prompt.user shouldBe """["One","Two"]"""
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
        prompt.user shouldBe "⟦0⟧\nOne\n\n⟦1⟧\nTwo"
        LlmResponseParser.parse(prompt.user, listOf("One", "Two"), structuredOutput = false) shouldContainExactly
            listOf("One", "Two")
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
        prompt.user shouldBe """["Current"]"""
    }

    @Test
    fun `structured response pads a short answer with the source text`() {
        val translated = LlmResponseParser.parse(
            """{"paragraphs":["Một","Hai"]}""",
            texts = listOf("One", "Two", "Three"),
            structuredOutput = true,
        )

        translated shouldContainExactly listOf("Một", "Hai", "Three")
    }

    @Test
    fun `structured response truncates an answer longer than the input`() {
        val translated = LlmResponseParser.parse(
            """["Một","Hai","Ba"]""",
            texts = listOf("One", "Two"),
            structuredOutput = true,
        )

        translated shouldContainExactly listOf("Một", "Hai")
    }

    @Test
    fun `structured response still reads the older indexed object shape`() {
        val translated = LlmResponseParser.parse(
            """{"paragraphs":[{"i":0,"t":"Một"},{"i":1,"t":"Hai"}]}""",
            texts = listOf("One", "Two"),
            structuredOutput = true,
        )

        translated shouldContainExactly listOf("Một", "Hai")
    }

    @Test
    fun `marker response keeps the source of a dropped paragraph`() {
        val translated = LlmResponseParser.parse(
            "⟦0⟧\nMột\n\n⟦2⟧\nBa",
            texts = listOf("One", "Two", "Three"),
            structuredOutput = false,
        )

        translated shouldContainExactly listOf("Một", "Two", "Ba")
    }

    @Test
    fun `structured response tolerates code fences and complete plain string arrays`() {
        val translated = LlmResponseParser.parse(
            "```json\n{\"paragraphs\":[\"Một\",\"Hai\"]}\n```",
            texts = listOf("One", "Two"),
            structuredOutput = true,
        )

        translated shouldContainExactly listOf("Một", "Hai")
    }

    @Test
    fun `single paragraph request accepts an unmarked response`() {
        LlmResponseParser.parse("Tiêu đề", texts = listOf("Title"), structuredOutput = false) shouldContainExactly
            listOf("Tiêu đề")
    }

    @Test
    fun `unusable response is rejected`() {
        shouldThrow<InvalidStructuredOutputException> {
            LlmResponseParser.parse("I cannot help with that.", listOf("One", "Two"), structuredOutput = true)
        }
    }

    @Test
    fun `single paragraph request rejects an empty json envelope`() {
        shouldThrow<InvalidStructuredOutputException> {
            LlmResponseParser.parse("""{"paragraphs":[]}""", listOf("Title"), structuredOutput = true)
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

        LlmRequestFactory.create(provider, generation(structured = true))
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
            generation(structured = true),
        ).body.toString()

        body shouldContain "response_format"
        body shouldContain "temperature"
    }

    @Test
    fun `chat request matches LNReader temperature behavior`() {
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
            generation(structured = false),
        ).body.toString()

        body shouldContain "temperature"
        body.contains("reasoning_effort") shouldBe false
        body shouldContain "\"store\":false"
    }

    @Test
    fun `gemini structured request uses json schema and default temperature`() {
        val body = LlmRequestFactory.create(
            AIProvider(
                id = "id",
                alias = "Gemini",
                type = AIProviderType.GEMINI,
                endpoint = "https://generativelanguage.googleapis.com",
                model = "gemini-2.5-flash",
            ),
            generation(structured = true),
        ).body.toString()

        body shouldContain "application/json"
        body shouldContain "responseJsonSchema"
        body shouldContain "\"items\":{\"type\":\"string\"}"
        body.contains("\"responseSchema\"") shouldBe false
        body.contains("temperature") shouldBe false
        body.contains("additionalProperties") shouldBe false
        body shouldContain "safetySettings"
        body shouldContain "HARM_CATEGORY_CIVIC_INTEGRITY"
    }

    @Test
    fun `gemini asks for minimal thinking when reasoning is off`() {
        val body = LlmRequestFactory.create(
            AIProvider(
                id = "id",
                alias = "Gemini",
                type = AIProviderType.GEMINI,
                endpoint = "https://generativelanguage.googleapis.com",
                model = "gemini-3-flash-preview",
                reasoning = false,
                reasoningEffort = ReasoningEffort.HIGH,
            ),
            generation(structured = true),
        ).body.toString()

        // Omitting thinkingConfig would hand the model its default budget instead of turning
        // thinking off, which is what billed thinking tokens on a reasoning=false request.
        body shouldContain "\"thinkingLevel\":\"MINIMAL\""
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
            generation(structured = false),
        ).body.toString()

        body shouldContain "thinkingConfig"
        body shouldContain "HIGH"
    }

    @Test
    fun `gemini reasoning sends the selected medium thinking level`() {
        val body = LlmRequestFactory.create(
            AIProvider(
                id = "id",
                alias = "Gemini",
                type = AIProviderType.GEMINI,
                endpoint = "https://generativelanguage.googleapis.com",
                model = "gemini-3-flash-preview",
                reasoning = true,
                reasoningEffort = ReasoningEffort.MEDIUM,
            ),
            generation(structured = true),
        ).body.toString()

        body shouldContain "\"thinkingLevel\":\"MEDIUM\""
    }

    @Test
    fun `retry policy does not retry invalid structured output`() = runTest {
        var calls = 0
        AiRetryPolicy.execute(retries = 5, failureCode = translationFailure, sleeper = {}) {
            calls++
            TranslationResult.Error("invalid", AiErrorCode.STRUCTURED_OUTPUT_INVALID)
        }
        calls shouldBe 1
    }

    @Test
    fun `retry policy retries transient errors and uses fibonacci delays`() = runTest {
        val delays = mutableListOf<Long>()
        var calls = 0
        val result = AiRetryPolicy.execute(
            retries = 2,
            failureCode = translationFailure,
            sleeper = { delays += it },
        ) {
            calls++
            if (calls < 3) {
                TranslationResult.Error("busy", AiErrorCode.RATE_LIMITED)
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
            AiRetryPolicy.execute(retries = 5, failureCode = translationFailure, sleeper = {}) {
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

        AiRetryPolicy.execute(retries = 99, failureCode = translationFailure, sleeper = { delays += it }) {
            calls++
            TranslationResult.Error("offline", AiErrorCode.NETWORK_ERROR)
        }

        calls shouldBe 6
        delays shouldContainExactly listOf(1_000L, 2_000L, 3_000L, 5_000L, 8_000L)
    }

    private fun generation(structured: Boolean) = LlmGenerationRequest(
        systemPrompt = "system",
        input = "user",
        outputFormat = if (structured) TranslationOutputSchema.format else LlmOutputFormat.Text,
    )

    private val translationFailure: (TranslationResult) -> AiErrorCode? = {
        (it as? TranslationResult.Error)?.errorCode
    }
}
