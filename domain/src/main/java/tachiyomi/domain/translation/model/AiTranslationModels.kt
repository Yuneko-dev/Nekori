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

data class TranslationRequest(
    val texts: List<String>,
    val sourceLanguage: String,
    val targetLanguage: String,
    val context: TranslationContext? = null,
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

@Serializable
data class SystemPrompt(
    val id: String,
    val name: String,
    val guidelines: String = "",
) {
    val deletable: Boolean get() = id != DEFAULT_ID

    companion object {
        const val DEFAULT_ID = "default"
        val DEFAULT = SystemPrompt(DEFAULT_ID, "Default")
    }
}

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
