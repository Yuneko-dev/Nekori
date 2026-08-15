package tachiyomi.domain.translation.service

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.translation.model.TranslationEngineId
import tachiyomi.domain.translation.model.UserGuidelines

/**
 * Preferences for translation services.
 */
class TranslationPreferences(
    private val preferenceStore: PreferenceStore,
) {
    /**
     * Whether translation is enabled.
     */
    fun translationEnabled() = preferenceStore.getBoolean(
        "translation_enabled",
        false,
    )

    /**
     * Selected translation engine ID.
     */
    fun selectedEngineId() = preferenceStore.getString(
        "translation_engine_id",
        TranslationEngineId.GOOGLE_FREE.key,
    )

    /** Serialized [tachiyomi.domain.translation.model.TranslationProfile] list. Empty until the user
     *  creates one; the store then synthesizes a default from [selectedEngineId] and the active
     *  provider/prompt, so an upgrade needs no migration. */
    fun translationProfilesJson() = preferenceStore.getString("translation_profiles", "[]")

    /** Serialized purpose key -> profile id map. A missing purpose falls back to the default profile. */
    fun translationTaskProfilesJson() = preferenceStore.getString("translation_task_profiles", "{}")

    /** Serialized [tachiyomi.domain.translation.model.AiTaskProfile] list, for AI tasks other than translation. */
    fun aiTaskProfilesJson() = preferenceStore.getString("ai_task_profiles", "[]")

    fun aiTaskAssignmentsJson() = preferenceStore.getString("ai_task_assignments", "{}")

    fun aiProvidersJson() = preferenceStore.getString("translation_ai_providers", "[]")

    fun activeAiProviderId() = preferenceStore.getString("translation_active_ai_provider", "")

    fun aiProviderApiKey(providerId: String) = preferenceStore.getString(
        Preference.privateKey("translation_ai_provider_key_$providerId"),
        "",
    )

    /** Serialized [tachiyomi.domain.translation.model.UserGuidelines] list. The key predates the
     *  rename from "system prompt" and must stay, or every existing install loses its guidelines. */
    fun userGuidelinesJson() = preferenceStore.getString(
        "translation_system_prompts",
        """[{"id":"${UserGuidelines.DEFAULT_ID}","name":"Default","guidelines":""}]""",
    )

    fun activeGuidelinesId() = preferenceStore.getString(
        "translation_active_system_prompt",
        UserGuidelines.DEFAULT_ID,
    )

    fun structuredOutput() = preferenceStore.getBoolean("translation_structured_output", true)

    fun requestRetryCount() = preferenceStore.getInt("translation_request_retry_count", 2)

    /**
     * Source language code for translation.
     */
    fun sourceLanguage() = preferenceStore.getString(
        "translation_source_language",
        "auto",
    )

    /**
     * Target language code for translation.
     */
    fun targetLanguage() = preferenceStore.getString(
        "translation_target_language",
        "en",
    )

    /**
     * Delay between translation requests in milliseconds (for rate-limited engines).
     */
    fun rateLimitDelayMs() = preferenceStore.getInt(
        "translation_rate_limit_delay",
        3000, // 3 seconds default
    )

    /**
     * Whether to auto-download chapters before translating.
     */
    fun autoDownloadBeforeTranslate() = preferenceStore.getBoolean(
        "translation_auto_download",
        true,
    )

    /**
     * Whether to auto-translate downloaded chapters.
     */
    fun autoTranslateDownloads() = preferenceStore.getBoolean(
        "translation_auto_translate_downloads",
        false,
    )

    /**
     * Smart auto-translate: skip translation if detected language matches target.
     * Consolidated from ReaderPreferences.autoTranslate (pref_auto_translate).
     */
    fun smartAutoTranslate() = preferenceStore.getBoolean(
        "pref_auto_translate",
        false,
    )

    /**
     * Chapter count threshold to show rate limit warning.
     */
    fun rateLimitWarningThreshold() = preferenceStore.getInt(
        "translation_rate_limit_warning_threshold",
        10,
    )

    /**
     * Whether to bypass the rate limit warning.
     */
    fun bypassRateLimitWarning() = preferenceStore.getBoolean(
        "translation_bypass_rate_limit_warning",
        false,
    )

    fun maxParallelTranslations() = preferenceStore.getInt(
        "translation_max_parallel",
        3,
    )

    /**
     * LibreTranslate server URL.
     */
    fun libreTranslateUrl() = preferenceStore.getString(
        "translation_libretranslate_url",
        "https://libretranslate.com/translate",
    )

    /**
     * LibreTranslate API key (optional).
     */
    fun libreTranslateApiKey() = preferenceStore.getString(
        Preference.privateKey("translation_libretranslate_api_key"),
        "",
    )

    /**
     * DeepL API key.
     */
    fun deepLApiKey() = preferenceStore.getString(
        Preference.privateKey("translation_deepl_api_key"),
        "",
    )

    /**
     * Google Cloud Translation API key.
     */
    fun googleApiKey() = preferenceStore.getString(
        Preference.privateKey("translation_google_api_key"),
        "",
    )

    /**
     * Translation request timeout in milliseconds.
     * Default is 2 minutes (120000ms).
     */
    fun translationTimeoutMs() = preferenceStore.getLong(
        "translation_timeout_ms",
        120000L, // 2 minutes default
    )

    /**
     * Whether to replace the manga title with the translated title.
     * When enabled, the original title is saved to alternative_titles.
     */
    fun replaceTitle() = preferenceStore.getBoolean(
        "translation_replace_title",
        false,
    )

    /**
     * Whether to translate tags and merge with original tags.
     */
    fun translateTags() = preferenceStore.getBoolean(
        "translation_translate_tags",
        false,
    )

    /**
     * Whether to replace original tags with translated tags instead of merging.
     * When false, translated tags are added to original tags.
     * When true, translated tags replace original tags.
     */
    fun replaceTagsInsteadOfMerge() = preferenceStore.getBoolean(
        "translation_replace_tags",
        false,
    )

    /**
     * Whether to save translated titles to alternative_titles.
     * Useful for keeping track of both original and translated titles.
     */
    fun saveTranslatedTitleAsAlternative() = preferenceStore.getBoolean(
        "translation_save_title_as_alternative",
        true,
    )

    /**
     * Maximum chunk size per translation batch (in paragraphs).
     */
    fun translationChunkSize() = preferenceStore.getInt(
        "translation_chunk_size",
        50,
    )

    /**
     * Whether to send previous translated paragraphs as context to LLM engines.
     * This improves translation consistency across chunks by giving the LLM
     * context from the end of the previous chunk.
     */
    fun contextualAnchoringEnabled() = preferenceStore.getBoolean(
        "translation_contextual_anchoring_enabled",
        true,
    )

    /**
     * Number of paragraphs from the end of the previous chunk to send as context.
     * Only applies when contextual anchoring is enabled and the engine is an LLM.
     */
    fun contextualAnchoringParagraphs() = preferenceStore.getInt(
        "translation_contextual_anchoring_paragraphs",
        2,
    )
}
