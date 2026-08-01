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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import tachiyomi.domain.translation.model.AIApiFamily
import tachiyomi.domain.translation.model.AIApiMode
import tachiyomi.domain.translation.model.AIProvider
import tachiyomi.domain.translation.model.ReasoningEffort
import tachiyomi.domain.translation.model.TranslationContext
import tachiyomi.domain.translation.model.TranslationResult

internal const val PARAGRAPH_MARKER = "<br>"

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
            "You MUST maintain the exact structural integrity of the input. " +
                "Keep all $PARAGRAPH_MARKER markers exactly as they appear between paragraphs."
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
                - Preserve image placeholders and internal markers verbatim.
                - Clean Output: Output ONLY the final processed text. Do NOT include any explanations, formatting tags (unless present in the source), intro/outro conversational filler, or internal thinking.

                ---
                [Custom Style Guidelines]:
                $custom

                ${context.asPromptSection()}

                ---
                Task: Translate the following $taskSubject from $sourceLanguage to $targetLanguage.
            """.trimIndent(),
            user = if (structuredOutput) {
                Json.encodeToString(texts)
            } else {
                texts.joinToString("\n$PARAGRAPH_MARKER\n")
            },
        )
    }

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

    fun parseStructured(content: String, expectedCount: Int): List<String> {
        val paragraphs = runCatching {
            json.parseToJsonElement(content.trim()).jsonObject.getValue("paragraphs").jsonArray
                .map { it.jsonPrimitive.content }
        }.getOrElse { throw InvalidStructuredOutputException("Invalid structured translation response") }
        if (paragraphs.size != expectedCount) {
            throw InvalidStructuredOutputException("Expected $expectedCount paragraphs, received ${paragraphs.size}")
        }
        return paragraphs
    }

    fun parseMarker(content: String, expectedCount: Int): List<String> {
        val paragraphs = content.split(PARAGRAPH_MARKER).map(String::trim)
        if (paragraphs.size != expectedCount) {
            throw InvalidStructuredOutputException(
                "Expected $expectedCount marker paragraphs, received ${paragraphs.size}",
            )
        }
        return paragraphs
    }
}

data class LlmWireRequest(val path: String, val body: JsonObject)

object LlmRequestFactory {
    private fun schema(includeAdditionalProperties: Boolean = true) = buildJsonObject {
        put("type", "object")
        if (includeAdditionalProperties) put("additionalProperties", false)
        put(
            "properties",
            buildJsonObject {
                put(
                    "paragraphs",
                    buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                    },
                )
            },
        )
        put("required", buildJsonArray { add(JsonPrimitive("paragraphs")) })
    }

    fun create(provider: AIProvider, system: String, user: String, structuredOutput: Boolean): LlmWireRequest {
        return when {
            provider.type.apiFamily == AIApiFamily.GEMINI -> gemini(provider, system, user, structuredOutput)
            provider.apiMode == AIApiMode.RESPONSES -> responses(provider, system, user, structuredOutput)
            else -> chat(provider, system, user, structuredOutput)
        }
    }

    private fun chat(provider: AIProvider, system: String, user: String, structured: Boolean) = LlmWireRequest(
        path = "/chat/completions",
        body = buildJsonObject {
            put("model", provider.model)
            put("messages", messages(system, user))
            if (!provider.reasoning) put("temperature", provider.temperature)
            if (provider.reasoning) put("reasoning_effort", provider.reasoningEffort.name.lowercase())
            if (structured) {
                put(
                    "response_format",
                    buildJsonObject {
                        put("type", "json_schema")
                        put("json_schema", jsonSchema())
                    },
                )
            }
        },
    )

    private fun responses(provider: AIProvider, system: String, user: String, structured: Boolean) = LlmWireRequest(
        path = "/responses",
        body = buildJsonObject {
            put("model", provider.model)
            put("instructions", system)
            put("input", user)
            if (provider.reasoning) {
                put(
                    "reasoning",
                    buildJsonObject {
                        put("effort", provider.reasoningEffort.name.lowercase())
                    },
                )
            }
            if (structured) {
                put(
                    "text",
                    buildJsonObject {
                        put(
                            "format",
                            buildJsonObject {
                                put("type", "json_schema")
                                jsonSchema().forEach { (key, value) -> put(key, value) }
                            },
                        )
                    },
                )
            }
        },
    )

    private fun gemini(provider: AIProvider, system: String, user: String, structured: Boolean) = LlmWireRequest(
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
                    put("temperature", provider.temperature)
                    if (provider.reasoning) {
                        put(
                            "thinkingConfig",
                            buildJsonObject {
                                put("thinkingLevel", provider.reasoningEffort.geminiLevel)
                            },
                        )
                    }
                    if (structured) {
                        put("responseMimeType", "application/json")
                        put("responseSchema", schema(includeAdditionalProperties = false))
                    }
                },
            )
        },
    )

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

    private fun jsonSchema() = buildJsonObject {
        put("name", "translation")
        put("strict", true)
        put("schema", schema())
    }

    private val ReasoningEffort.geminiLevel: String
        get() = when (this) {
            ReasoningEffort.NONE -> "THINKING_LEVEL_UNSPECIFIED"
            ReasoningEffort.MINIMAL -> "MINIMAL"
            ReasoningEffort.LOW -> "LOW"
            ReasoningEffort.MEDIUM -> "MEDIUM"
            ReasoningEffort.HIGH, ReasoningEffort.XHIGH -> "HIGH"
        }
}

object TranslationRetryPolicy {
    private val backoff = longArrayOf(1_000, 2_000, 3_000, 5_000, 8_000)

    suspend fun execute(
        retries: Int,
        sleeper: suspend (Long) -> Unit = { delay(it) },
        block: suspend () -> TranslationResult,
    ): TranslationResult {
        val retryCount = retries.coerceIn(0, 5)
        repeat(retryCount + 1) { attempt ->
            val result = try {
                block()
            } catch (e: CancellationException) {
                throw e
            }
            if (result !is TranslationResult.Error || !result.isRetryable || attempt >= retryCount) return result
            sleeper(backoff[attempt])
        }
        error("unreachable")
    }

    private val TranslationResult.Error.isRetryable: Boolean
        get() = errorCode in setOf(
            TranslationResult.ErrorCode.NETWORK_ERROR,
            TranslationResult.ErrorCode.TIMEOUT,
            TranslationResult.ErrorCode.RATE_LIMITED,
            TranslationResult.ErrorCode.SERVICE_UNAVAILABLE,
        )
}
