package eu.kanade.tachiyomi.data.translation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.translation.model.AIApiFamily
import tachiyomi.domain.translation.model.AIApiMode
import tachiyomi.domain.translation.model.AIProvider
import tachiyomi.domain.translation.model.AiErrorCode
import tachiyomi.domain.translation.model.LlmGenerationRequest
import tachiyomi.domain.translation.model.LlmOutputFormat
import tachiyomi.domain.translation.model.ReasoningEffort
import tachiyomi.domain.translation.model.TranslationContext
import tachiyomi.domain.translation.model.TranslationResult

/** Labels a paragraph in non-structured mode; the index survives a merge or drop, a plain separator does not. */
private fun paragraphMarker(index: Int) = "⟦$index⟧"

data class LlmPrompt(val system: String, val user: String)

object LlmPromptBuilder {
    fun build(
        texts: List<String>,
        sourceLanguage: String,
        targetLanguage: String,
        guidelines: String,
        structuredOutput: Boolean,
        context: TranslationContext? = null,
    ): LlmPrompt {
        val custom = guidelines.trim().ifEmpty { "No specific guidelines." }
        val formattingConstraint = if (structuredOutput) {
            "You MUST output ONLY a valid JSON object."
        } else {
            "You MUST repeat every index marker (${paragraphMarker(0)}, ${paragraphMarker(1)}, …) exactly as " +
                "it appears, alone on the line above the paragraph it labels."
        }
        val taskSubject = if (structuredOutput) "text array" else "text"
        return LlmPrompt(
            system = """
                You are an Expert Transcreator. Your task is to translate the source text accurately while dynamically adapting the style, tone, and localization based on any provided custom guidelines.

                Core Directives:
                1. Two-Step Process: Accurately capture the original meaning, then reshape the linguistic presentation according to the specific style requirements requested.
                2. Neutral Fallback: If no custom style guidelines are provided, produce a highly natural and fluent standard translation in the target language.

                Strict Technical Constraints (CRITICAL):
                - Formatting: $formattingConstraint
                - Ensure that the number of translated paragraphs is exactly equal to the number of input paragraphs.
                - Clean Output: Output ONLY the final processed text. Do NOT include any explanations, formatting tags (unless present in the source), intro/outro conversational filler, or internal thinking.

                ---
                [Custom Style Guidelines]:
                $custom

                ${context.asPromptSection()}

                ---
                Task: Translate the following $taskSubject from $sourceLanguage to $targetLanguage.
            """.trimIndent(),
            user = if (structuredOutput) plainJsonArray(texts) else indexedMarkers(texts),
        )
    }

    /** LNReader's payload: `JSON.stringify(texts)`. A paragraph costs one pair of quotes, nothing else. */
    private fun plainJsonArray(texts: List<String>) = buildJsonArray {
        texts.forEach { add(JsonPrimitive(it)) }
    }.toString()

    private fun indexedMarkers(texts: List<String>) = texts
        .mapIndexed { index, text -> "${paragraphMarker(index)}\n$text" }
        .joinToString("\n\n")

    private fun TranslationContext?.asPromptSection(): String {
        if (this == null || previousTranslatedParagraphs.isEmpty()) return ""
        return """
            [Previous Context — reference only; do not include it in the output]:
            Source:
            ${previousSourceParagraphs.joinToString("\n\n")}

            Translation:
            ${previousTranslatedParagraphs.joinToString("\n\n")}
        """.trimIndent()
    }
}

class InvalidStructuredOutputException(message: String) : IllegalArgumentException(message)

object LlmResponseParser {
    private val json = Json { ignoreUnknownKeys = true }
    private val markerRegex = Regex("⟦(\\d+)⟧")

    /**
     * Read a model response back onto [texts], LNReader's `adjustCount` contract: paragraphs are
     * matched by position, and a response that is short or long is padded/truncated rather than
     * rejected. A padded slot keeps its source text instead of LNReader's empty string, which drops
     * the paragraph from the chapter.
     *
     * Position is only as good as the model's paragraph count. The letters-only prefilter in
     * [TranslationHtmlUtils.prepareTranslation] is what keeps that count stable — it stops sending
     * the scene breaks and bare numbers models like to merge.
     */
    fun parse(content: String, texts: List<String>, structuredOutput: Boolean): List<String> {
        val body = stripCodeFence(content)
        // An envelope we could not read leaves no paragraphs, and must never be pasted into the
        // chapter as if it were a translation.
        val paragraphs = if (structuredOutput) parseStructured(body) else parseMarker(body, texts.size)
        if (paragraphs.none(String::isNotBlank)) {
            throw InvalidStructuredOutputException("No translated paragraphs in response")
        }
        if (paragraphs.size != texts.size) {
            logcat(LogPriority.WARN) {
                "The number of output paragraphs does not match the input " +
                    "(input = ${texts.size} | output = ${paragraphs.size})"
            }
        }
        return texts.mapIndexed { index, source ->
            paragraphs.getOrNull(index)?.takeIf(String::isNotBlank) ?: source
        }
    }

    /**
     * Accepts `{"paragraphs":[…]}` or a bare array. Entries are plain strings, and objects are
     * tolerated so a response in the older `{"i","t"}` shape still reads.
     */
    private fun parseStructured(content: String): List<String> {
        val root = runCatching { json.parseToJsonElement(content) }.getOrNull() ?: return emptyList()
        val array = root as? JsonArray
            ?: (root as? JsonObject)?.get("paragraphs") as? JsonArray
            ?: return emptyList()
        return array.map { element ->
            when (element) {
                is JsonObject -> element["t"]?.jsonPrimitive?.contentOrNull.orEmpty()
                else -> (element as? JsonPrimitive)?.contentOrNull.orEmpty()
            }
        }
    }

    /**
     * Index markers are kept for this path instead of LNReader's `<br>` separator: a paragraph is
     * inner HTML here, and novel paragraphs contain `<br>` often enough that splitting on it would
     * silently shred them.
     */
    private fun parseMarker(content: String, expectedCount: Int): List<String> {
        val byIndex = markerIndex(content)
        // A single-paragraph request (chapter titles) is unambiguous without any marker.
        if (byIndex.isEmpty()) return if (expectedCount == 1) listOf(content.trim()) else emptyList()
        return (0 until expectedCount).map { byIndex[it].orEmpty() }
    }

    private fun markerIndex(content: String): Map<Int, String> {
        val markers = markerRegex.findAll(content).toList()
        return markers.mapIndexedNotNull { position, match ->
            val index = match.groupValues[1].toIntOrNull() ?: return@mapIndexedNotNull null
            val end = markers.getOrNull(position + 1)?.range?.first ?: content.length
            index to content.substring(match.range.last + 1, end).trim()
        }.toMap()
    }

    /** Models wrap the payload in a ```json fence often enough to be worth undoing. */
    private fun stripCodeFence(content: String): String {
        val trimmed = content.trim()
        if (!trimmed.startsWith("```")) return trimmed
        return trimmed.removePrefix("```").substringAfter('\n', "").substringBeforeLast("```").trim()
    }
}

data class LlmWireRequest(val path: String, val body: JsonObject)

/**
 * The structured shape translation asks for: `{"paragraphs":["…"]}` — LNReader's schema.
 *
 * A per-paragraph index was tried here and reverted: it costs roughly ten extra output tokens on
 * every paragraph, and output tokens are what the request waits on.
 */
object TranslationOutputSchema {
    val format = LlmOutputFormat.JsonSchema(
        name = "translation",
        schema = buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            put(
                "properties",
                buildJsonObject {
                    put(
                        "paragraphs",
                        buildJsonObject {
                            put("type", "array")
                            put(
                                "description",
                                "Array of translated paragraphs, must match the order and count of the input array",
                            )
                            put("items", buildJsonObject { put("type", "string") })
                        },
                    )
                },
            )
            put("required", buildJsonArray { add(JsonPrimitive("paragraphs")) })
        },
    )
}

object LlmRequestFactory {
    fun create(provider: AIProvider, request: LlmGenerationRequest): LlmWireRequest {
        val system = request.systemPrompt
        val user = request.input
        val schema = request.outputFormat as? LlmOutputFormat.JsonSchema
        return when {
            provider.type.apiFamily == AIApiFamily.GEMINI -> gemini(provider, system, user, schema)
            provider.apiMode == AIApiMode.RESPONSES -> responses(provider, system, user, schema)
            else -> chat(provider, system, user, schema)
        }
    }

    private fun chat(
        provider: AIProvider,
        system: String,
        user: String,
        schema: LlmOutputFormat.JsonSchema?,
    ) = LlmWireRequest(
        path = "/chat/completions",
        body = buildJsonObject {
            put("model", provider.model)
            put("messages", messages(system, user))
            put("temperature", provider.temperature)
            put("store", false)
            if (schema != null) {
                put(
                    "response_format",
                    buildJsonObject {
                        put("type", "json_schema")
                        put("json_schema", schema.strictEnvelope())
                    },
                )
            }
        },
    )

    private fun responses(
        provider: AIProvider,
        system: String,
        user: String,
        schema: LlmOutputFormat.JsonSchema?,
    ) = LlmWireRequest(
        path = "/responses",
        body = buildJsonObject {
            put("model", provider.model)
            put("instructions", system)
            put("input", user)
            put("store", false)
            if (provider.reasoning) {
                put(
                    "reasoning",
                    buildJsonObject {
                        put("effort", provider.reasoningEffort.name.lowercase())
                    },
                )
            }
            if (schema != null) {
                put(
                    "text",
                    buildJsonObject {
                        put(
                            "format",
                            buildJsonObject {
                                put("type", "json_schema")
                                schema.strictEnvelope().forEach { (key, value) -> put(key, value) }
                            },
                        )
                    },
                )
            }
        },
    )

    private fun gemini(
        provider: AIProvider,
        system: String,
        user: String,
        schema: LlmOutputFormat.JsonSchema?,
    ) = LlmWireRequest(
        path = "/v1beta/models/${provider.model}:generateContent",
        body = buildJsonObject {
            put("systemInstruction", buildJsonObject { put("parts", textParts(system)) })
            put(
                "contents",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("parts", textParts(user))
                        },
                    )
                },
            )
            put(
                "generationConfig",
                buildJsonObject {
                    put(
                        "thinkingConfig",
                        buildJsonObject {
                            // Always sent. Leaving thinkingConfig out asks for the model's own
                            // default budget, so reasoning=false still billed thinking tokens -
                            // the toggle read as "off" and behaved as "whatever you like".
                            put(
                                "thinkingLevel",
                                if (provider.reasoning) provider.reasoningEffort.geminiLevel else "MINIMAL",
                            )
                        },
                    )
                    if (schema != null) {
                        put("responseMimeType", "application/json")
                        put("responseJsonSchema", schema.schema.withoutAdditionalProperties())
                    }
                },
            )
            put(
                "safetySettings",
                buildJsonArray {
                    listOf(
                        "HARM_CATEGORY_HARASSMENT",
                        "HARM_CATEGORY_HATE_SPEECH",
                        "HARM_CATEGORY_SEXUALLY_EXPLICIT",
                        "HARM_CATEGORY_DANGEROUS_CONTENT",
                        "HARM_CATEGORY_CIVIC_INTEGRITY",
                    ).forEach { category ->
                        add(
                            buildJsonObject {
                                put("category", category)
                                put("threshold", "OFF")
                            },
                        )
                    }
                },
            )
        },
    )

    /** OpenAI wants the schema wrapped and named; strict mode is what makes the shape binding. */
    private fun LlmOutputFormat.JsonSchema.strictEnvelope() = buildJsonObject {
        put("name", name)
        put("strict", true)
        put("schema", schema)
    }

    /** Gemini rejects `additionalProperties`, which OpenAI's strict mode requires. */
    private fun JsonObject.withoutAdditionalProperties(): JsonObject = buildJsonObject {
        forEach { (key, value) ->
            when {
                key == "additionalProperties" -> Unit
                value is JsonObject -> put(key, value.withoutAdditionalProperties())
                else -> put(key, value)
            }
        }
    }

    private fun messages(system: String, user: String) = buildJsonArray {
        add(
            buildJsonObject {
                put("role", "system")
                put("content", system)
            },
        )
        add(
            buildJsonObject {
                put("role", "user")
                put("content", user)
            },
        )
    }

    private fun textParts(text: String) = buildJsonArray { add(buildJsonObject { put("text", text) }) }

    private val ReasoningEffort.geminiLevel: String
        get() = when (this) {
            // Not THINKING_LEVEL_UNSPECIFIED: that asks the model for its default budget, which on
            // a thinking model is the opposite of what picking "none" means. MINIMAL is the floor.
            ReasoningEffort.NONE -> "MINIMAL"
            ReasoningEffort.MINIMAL -> "MINIMAL"
            ReasoningEffort.LOW -> "LOW"
            ReasoningEffort.MEDIUM -> "MEDIUM"
            ReasoningEffort.HIGH, ReasoningEffort.XHIGH -> "HIGH"
        }
}

/**
 * Retries an AI request while the provider says the failure is transient.
 *
 * Generic over the result so translation and every later AI task share one backoff schedule; each
 * caller supplies [failureCode] because only it knows the shape of its own result.
 */
object AiRetryPolicy {
    private val backoff = longArrayOf(1_000, 2_000, 3_000, 5_000, 8_000)

    suspend fun <T> execute(
        retries: Int,
        failureCode: (T) -> AiErrorCode?,
        sleeper: suspend (Long) -> Unit = { delay(it) },
        block: suspend () -> T,
    ): T {
        val retryCount = retries.coerceIn(0, 5)
        repeat(retryCount + 1) { attempt ->
            val result = try {
                block()
            } catch (e: CancellationException) {
                throw e
            }
            if (failureCode(result)?.retryable != true || attempt >= retryCount) return result
            sleeper(backoff[attempt])
        }
        error("unreachable")
    }
}
