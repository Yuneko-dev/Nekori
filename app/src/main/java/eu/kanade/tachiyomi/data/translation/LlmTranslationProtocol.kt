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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.translation.model.AIApiFamily
import tachiyomi.domain.translation.model.AIApiMode
import tachiyomi.domain.translation.model.AIProvider
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
            """You MUST output ONLY a valid JSON object shaped as {"paragraphs":[{"i":0,"t":"translation"}]}, """ +
                """where "i" is copied verbatim from the input entry being translated."""
        } else {
            "You MUST repeat every index marker (${paragraphMarker(0)}, ${paragraphMarker(1)}, …) exactly as " +
                "it appears, alone on the line above the paragraph it labels."
        }
        return LlmPrompt(
            system = """
                You are an Expert Transcreator. Your task is to translate the source text accurately while dynamically adapting the style, tone, and localization based on any provided custom guidelines.

                Core Directives:
                1. Two-Step Process: Accurately capture the original meaning, then reshape the linguistic presentation according to the specific style requirements requested.
                2. Neutral Fallback: If no custom style guidelines are provided, produce a highly natural and fluent standard translation in the target language.

                Strict Technical Constraints (CRITICAL):
                - Formatting: $formattingConstraint
                - Return exactly one entry per input paragraph, in the input order. NEVER merge, split, reorder or omit paragraphs.
                - A paragraph that needs no translation MUST be repeated unchanged instead of being dropped.
                - Preserve image placeholders and internal markers verbatim.
                - Clean Output: Output ONLY the final processed text. Do NOT include any explanations, formatting tags (unless present in the source), intro/outro conversational filler, or internal thinking.

                ---
                [Custom Style Guidelines]:
                $custom

                ${context.asPromptSection()}

                ---
                Task: Translate the following paragraphs from $sourceLanguage to $targetLanguage.
            """.trimIndent(),
            user = if (structuredOutput) indexedJson(texts) else indexedMarkers(texts),
        )
    }

    private fun indexedJson(texts: List<String>) = buildJsonArray {
        texts.forEachIndexed { index, text ->
            add(
                buildJsonObject {
                    put("i", index)
                    put("t", text)
                },
            )
        }
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
     * Realign a model response onto [texts] by paragraph index.
     *
     * Paragraphs the model dropped or returned blank keep their source text, so a single
     * missing paragraph no longer discards the whole chunk. Only a response without any
     * usable paragraph is an error.
     */
    fun parse(content: String, texts: List<String>, structuredOutput: Boolean): List<String> {
        val body = stripCodeFence(content)
        val byIndex = if (structuredOutput) parseStructured(body, texts.size) else parseMarker(body)
        if (byIndex.isEmpty()) {
            // A single-paragraph request (chapter titles) is unambiguous without any marker, but an
            // envelope we merely failed to read must not be pasted into the chapter as a translation.
            if (texts.size == 1 && !body.startsWith('{') && !body.startsWith('[')) return listOf(body.trim())
            throw InvalidStructuredOutputException("No translated paragraphs in response")
        }

        fun translationAt(index: Int) = byIndex[index]?.takeIf(String::isNotBlank)

        val dropped = texts.indices.count { translationAt(it) == null }
        if (dropped > 0) {
            logcat(LogPriority.WARN) { "Translation dropped $dropped/${texts.size} paragraphs, kept source text" }
        }
        return texts.mapIndexed { index, source -> translationAt(index) ?: source }
    }

    /** Accepts `{"paragraphs":[…]}` or a bare array, of either indexed objects or plain strings. */
    private fun parseStructured(content: String, expectedCount: Int): Map<Int, String> {
        val root = runCatching { json.parseToJsonElement(content) }.getOrNull() ?: return emptyMap()
        val array = root as? JsonArray
            ?: (root as? JsonObject)?.get("paragraphs") as? JsonArray
            ?: return emptyMap()
        val indexed = array.filterIsInstance<JsonObject>()
        // A provider that ignored the schema answers with plain strings, whose position is only
        // trustworthy when it returned every paragraph — a short array would shift the rest.
        if (indexed.isEmpty()) {
            if (array.size != expectedCount) return emptyMap()
            return array.mapIndexed { position, element ->
                position to (element as? JsonPrimitive)?.contentOrNull.orEmpty()
            }.toMap()
        }
        return indexed.mapIndexed { position, element ->
            (element["i"]?.jsonPrimitive?.intOrNull ?: position) to
                element["t"]?.jsonPrimitive?.contentOrNull.orEmpty()
        }.toMap()
    }

    private fun parseMarker(content: String): Map<Int, String> {
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
                        put("items", paragraphSchema(includeAdditionalProperties))
                    },
                )
            },
        )
        put("required", buildJsonArray { add(JsonPrimitive("paragraphs")) })
    }

    /** `i` is the input paragraph index, `t` its translation — see [LlmResponseParser.parse]. */
    private fun paragraphSchema(includeAdditionalProperties: Boolean) = buildJsonObject {
        put("type", "object")
        if (includeAdditionalProperties) put("additionalProperties", false)
        put(
            "properties",
            buildJsonObject {
                put("i", buildJsonObject { put("type", "integer") })
                put("t", buildJsonObject { put("type", "string") })
            },
        )
        put(
            "required",
            buildJsonArray {
                add(JsonPrimitive("i"))
                add(JsonPrimitive("t"))
            },
        )
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
