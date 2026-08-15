package tachiyomi.domain.translation.model

/**
 * Base interface for translation engines.
 */
interface TranslationEngine {
    /**
     * Unique identifier for this engine.
     */
    val id: TranslationEngineId

    /**
     * Display name of the engine.
     */
    val name: String

    /**
     * Whether this engine requires an API key.
     */
    val requiresApiKey: Boolean

    /**
     * Whether this engine is rate-limited (web-based).
     * Offline engines like ML Kit don't need rate limiting.
     */
    val isRateLimited: Boolean

    /**
     * Whether this engine works offline.
     */
    val isOffline: Boolean

    /**
     * List of supported languages as (code, displayName) pairs.
     */
    val supportedLanguages: List<Pair<String, String>>

    /**
     * Translate a list of text segments.
     *
     * @param request Text segments, language pair, and optional context.
     * @return Result containing translated texts or error
     */
    suspend fun translate(request: TranslationRequest): TranslationResult

    /**
     * Translate a single text.
     */
    suspend fun translateSingle(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
    ): TranslationResult {
        return translate(TranslationRequest(listOf(text), sourceLanguage, targetLanguage))
    }

    /**
     * Check if the engine is properly configured (API key set, etc.) for [config].
     *
     * Only the LLM engine varies per profile; engines keyed by a service-wide API key ignore the
     * argument. The LLM engine is unconfigured when [config] is null.
     */
    fun isConfigured(config: AiExecutionConfig? = null): Boolean = true
}

/**
 * Result of a translation operation.
 */
sealed class TranslationResult {
    /**
     * Successful translation.
     */
    data class Success(
        val translatedTexts: List<String>,
        val detectedSourceLanguage: String? = null,
    ) : TranslationResult()

    /**
     * Translation failed.
     */
    data class Error(
        val message: String,
        val errorCode: AiErrorCode = AiErrorCode.UNKNOWN,
    ) : TranslationResult()
}

/**
 * Common language codes used across translation engines.
 */
object LanguageCodes {
    val COMMON_LANGUAGES = listOf(
        "auto" to "Auto-detect",
        "en" to "English",
        "zh" to "Chinese (Simplified)",
        "zh-TW" to "Chinese (Traditional)",
        "ja" to "Japanese",
        "ko" to "Korean",
        "es" to "Spanish",
        "fr" to "French",
        "de" to "German",
        "it" to "Italian",
        "pt" to "Portuguese",
        "ru" to "Russian",
        "ar" to "Arabic",
        "hi" to "Hindi",
        "th" to "Thai",
        "vi" to "Vietnamese",
        "id" to "Indonesian",
        "ms" to "Malay",
        "tl" to "Filipino",
        "tr" to "Turkish",
        "pl" to "Polish",
        "nl" to "Dutch",
        "sv" to "Swedish",
        "da" to "Danish",
        "fi" to "Finnish",
        "no" to "Norwegian",
        "uk" to "Ukrainian",
        "cs" to "Czech",
        "ro" to "Romanian",
        "hu" to "Hungarian",
        "el" to "Greek",
        "he" to "Hebrew",
        "fa" to "Persian",
        "bn" to "Bengali",
    )

    val GOOGLE_TRANSLATE_LANGUAGES = (
        COMMON_LANGUAGES + listOf(
            "af" to "Afrikaans",
            "sq" to "Albanian",
            "am" to "Amharic",
            "hy" to "Armenian",
            "az" to "Azerbaijani",
            "eu" to "Basque",
            "be" to "Belarusian",
            "bs" to "Bosnian",
            "bg" to "Bulgarian",
            "ca" to "Catalan",
            "ceb" to "Cebuano",
            "ny" to "Chichewa",
            "zh-CN" to "Chinese (Simplified)",
            "co" to "Corsican",
            "hr" to "Croatian",
            "eo" to "Esperanto",
            "et" to "Estonian",
            "fy" to "Frisian",
            "gl" to "Galician",
            "ka" to "Georgian",
            "gu" to "Gujarati",
            "ht" to "Haitian Creole",
            "ha" to "Hausa",
            "haw" to "Hawaiian",
            "iw" to "Hebrew",
            "hmn" to "Hmong",
            "is" to "Icelandic",
            "ig" to "Igbo",
            "ga" to "Irish",
            "jw" to "Javanese",
            "kn" to "Kannada",
            "kk" to "Kazakh",
            "km" to "Khmer",
            "ku" to "Kurdish (Kurmanji)",
            "ky" to "Kyrgyz",
            "lo" to "Lao",
            "la" to "Latin",
            "lv" to "Latvian",
            "lt" to "Lithuanian",
            "lb" to "Luxembourgish",
            "mk" to "Macedonian",
            "mg" to "Malagasy",
            "ml" to "Malayalam",
            "mt" to "Maltese",
            "mi" to "Maori",
            "mr" to "Marathi",
            "mn" to "Mongolian",
            "my" to "Myanmar (Burmese)",
            "ne" to "Nepali",
            "ps" to "Pashto",
            "pa" to "Punjabi",
            "sm" to "Samoan",
            "gd" to "Scots Gaelic",
            "sr" to "Serbian",
            "st" to "Sesotho",
            "sn" to "Shona",
            "sd" to "Sindhi",
            "si" to "Sinhala",
            "sk" to "Slovak",
            "sl" to "Slovenian",
            "so" to "Somali",
            "su" to "Sundanese",
            "sw" to "Swahili",
            "tg" to "Tajik",
            "ta" to "Tamil",
            "te" to "Telugu",
            "ur" to "Urdu",
            "uz" to "Uzbek",
            "cy" to "Welsh",
            "xh" to "Xhosa",
            "yi" to "Yiddish",
            "yo" to "Yoruba",
            "zu" to "Zulu",
        )
        )
        .distinctBy { it.first }
        .let { languages -> languages.take(1) + languages.drop(1).sortedBy { it.second } }

    fun getDisplayName(code: String): String {
        return GOOGLE_TRANSLATE_LANGUAGES.find { it.first == code }?.second ?: code
    }
}
