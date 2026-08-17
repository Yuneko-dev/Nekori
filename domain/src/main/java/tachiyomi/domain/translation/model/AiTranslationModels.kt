package tachiyomi.domain.translation.model

import kotlinx.serialization.Serializable

@Serializable
enum class TranslationEngineId(val key: String) {
    GOOGLE_FREE("google_free"),
    LLM("llm"),
    LIBRE("libre"),
    DEEPL("deepl"),
    GOOGLE_CLOUD("google_cloud"),
    ;

    companion object {
        fun fromKey(key: String): TranslationEngineId = entries.firstOrNull { it.key == key } ?: GOOGLE_FREE
    }
}

data class TranslationContext(
    val previousSourceParagraphs: List<String> = emptyList(),
    val previousTranslatedParagraphs: List<String> = emptyList(),
)

/**
 * Everything needed to run one LLM call: which provider, its key, and the user's optional
 * guidelines. Shared by translation profiles and AI task profiles - both resolve to the same three
 * values, so they resolve through the same type.
 *
 * Deliberately does not carry a translation engine: [TranslationEngine.translate] already takes a
 * [TranslationRequest], so an engine reference here would make the two types mutually referential.
 */
data class AiExecutionConfig(
    val provider: AIProvider? = null,
    val apiKey: String = "",
    val guidelines: String? = null,
) {
    /** Whether a request can be built at all: a provider to call, a model to call it with, and a key if it needs one. */
    val isComplete: Boolean
        get() = provider != null && provider.endpoint.isNotBlank() && provider.model.isNotBlank() &&
            (!provider.requiresApiKey || apiKey.isNotBlank())
}

data class TranslationRequest(
    val texts: List<String>,
    val sourceLanguage: String,
    val targetLanguage: String,
    val context: TranslationContext? = null,
    /** Filled in by TranslationEngineManager; callers name a purpose instead of building this. */
    val config: AiExecutionConfig? = null,
)

@Serializable
enum class AIProviderType {
    OPENAI,
    DEEPSEEK,
    GEMINI,
    XAI,
    OPENROUTER,
    GROQ,
    CUSTOM_OPENAI,

    ;

    val apiFamily: AIApiFamily
        get() = if (this == GEMINI) AIApiFamily.GEMINI else AIApiFamily.OPENAI_COMPATIBLE

    val defaultEndpoint: String
        get() = when (this) {
            OPENAI -> "https://api.openai.com/v1"
            DEEPSEEK -> "https://api.deepseek.com/v1"
            GEMINI -> "https://generativelanguage.googleapis.com"
            XAI -> "https://api.x.ai/v1"
            OPENROUTER -> "https://openrouter.ai/api/v1"
            GROQ -> "https://api.groq.com/openai/v1"
            CUSTOM_OPENAI -> "http://localhost:1234/v1"
        }

    val endpointEditable: Boolean get() = this == GEMINI || this == CUSTOM_OPENAI
    val supportsApiMode: Boolean get() = apiFamily == AIApiFamily.OPENAI_COMPATIBLE

    /** Gemini requests carry no temperature, so offering the slider would be a knob wired to nothing. */
    val supportsTemperature: Boolean get() = apiFamily == AIApiFamily.OPENAI_COMPATIBLE
}

enum class AIApiFamily {
    OPENAI_COMPATIBLE,
    GEMINI,
}

@Serializable
enum class AIApiMode {
    RESPONSES,
    CHAT_COMPLETIONS,
}

@Serializable
enum class ReasoningEffort {
    NONE,
    MINIMAL,
    LOW,
    MEDIUM,
    HIGH,
    XHIGH,
}

@Serializable
data class AIHeader(
    val name: String,
    val value: String,
)

@Serializable
data class AIProvider(
    val id: String,
    val alias: String,
    val type: AIProviderType,
    val endpoint: String,
    val model: String,
    val apiMode: AIApiMode = AIApiMode.RESPONSES,
    val temperature: Float = 0.6f,
    val reasoning: Boolean = false,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.LOW,
    val customHeaders: List<AIHeader> = emptyList(),
) {
    val requiresApiKey: Boolean get() = type != AIProviderType.CUSTOM_OPENAI
}

/**
 * A named set of optional user instructions appended to whichever AI task is running - translation
 * today, chapter summaries next. It is not the task's system prompt: each task owns that, along with
 * its output contract, and the user cannot replace it.
 *
 * Field names are the ones the pre-rename `SystemPrompt` serialized, so stored JSON still reads.
 */
@Serializable
data class UserGuidelines(
    val id: String,
    val name: String,
    val guidelines: String = "",
) {
    val deletable: Boolean get() = id != DEFAULT_ID

    companion object {
        const val DEFAULT_ID = "default"
        val DEFAULT = UserGuidelines(DEFAULT_ID, "Default")
    }
}

/** A named id, or the globally active one when the caller named none. */
private fun effectiveId(id: String?, activeId: String) = id?.takeIf(String::isNotBlank) ?: activeId

/**
 * The provider a profile named, or the globally active one.
 *
 * A named provider that has since been deleted resolves to null rather than silently borrowing the
 * active one: the profile is unconfigured, and saying so beats translating with a provider the user
 * did not pick. Defined here so the store and the settings UI share one rule instead of two copies.
 */
fun List<AIProvider>.resolve(id: String?, activeId: String): AIProvider? =
    firstOrNull { it.id == effectiveId(id, activeId) }

/** As [resolve], but deleted guidelines fall back to the empty default - no instructions, not a failure. */
fun List<UserGuidelines>.resolve(id: String?, activeId: String): UserGuidelines =
    firstOrNull { it.id == effectiveId(id, activeId) } ?: UserGuidelines.DEFAULT

data class HeaderValidationResult(val errors: List<String>) {
    val isValid: Boolean get() = errors.isEmpty()
}

fun validateCustomHeaders(headers: List<AIHeader>): HeaderValidationResult {
    val errors = mutableListOf<String>()
    val names = mutableSetOf<String>()
    headers.forEachIndexed { index, header ->
        val name = header.name.trim()
        when {
            name.isEmpty() -> errors += "Header ${index + 1}: name is empty"
            name.equals("Host", true) || name.equals("Content-Length", true) ->
                errors += "Header ${index + 1}: $name cannot be overridden"
            '\n' in name || '\r' in name || '\n' in header.value || '\r' in header.value ->
                errors += "Header ${index + 1}: newlines are not allowed"
            !names.add(name.lowercase()) -> errors += "Duplicate header: $name"
        }
    }
    return HeaderValidationResult(errors)
}

fun mergeHeaders(defaults: Map<String, String>, custom: List<AIHeader>): Map<String, String> {
    val validation = validateCustomHeaders(custom)
    require(validation.isValid) { validation.errors.joinToString() }
    val overridden = custom.map { it.name.trim().lowercase() }.toSet()
    return buildMap {
        defaults.filterKeys { it.lowercase() !in overridden }.forEach(::put)
        custom.forEach { put(it.name.trim(), it.value) }
    }
}

fun AIProvider.normalized(): AIProvider = copy(
    alias = alias.trim(),
    endpoint = endpoint.trim().trimEnd('/'),
    model = model.trim(),
    temperature = temperature.coerceIn(0f, 2f),
    customHeaders = customHeaders.map { it.copy(name = it.name.trim()) },
)
