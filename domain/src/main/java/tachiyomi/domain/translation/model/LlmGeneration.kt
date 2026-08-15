package tachiyomi.domain.translation.model

import kotlinx.serialization.json.JsonObject

/** What an AI task wants back: free prose, or JSON the provider must shape itself. */
sealed interface LlmOutputFormat {
    data object Text : LlmOutputFormat

    /** [schema] is a JSON Schema object; [name] labels it for providers that require one. */
    data class JsonSchema(val name: String, val schema: JsonObject) : LlmOutputFormat
}

/**
 * One generation call, with no idea what it is for.
 *
 * The task owns [systemPrompt] - its role, its rules, its output contract - and appends the user's
 * [UserGuidelines] to it before getting here.
 */
data class LlmGenerationRequest(
    val systemPrompt: String,
    val input: String,
    val outputFormat: LlmOutputFormat = LlmOutputFormat.Text,
)

sealed interface LlmResult {
    data class Success(val text: String) : LlmResult

    data class Failure(val message: String, val code: AiErrorCode = AiErrorCode.UNKNOWN) : LlmResult
}
