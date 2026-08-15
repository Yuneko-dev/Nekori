package tachiyomi.domain.translation.model

import kotlinx.serialization.Serializable

/**
 * A translation entry point. Each one resolves to its own [TranslationProfile], so browsing a source
 * can use a fast free engine while chapter text uses a tuned LLM.
 *
 * Adding a purpose is one constant here plus one assignment row in the translation settings.
 */
@Serializable
enum class TranslationPurpose(val key: String) {
    /** Chapter text: chunked, cached, resumable. */
    CHAPTER("chapter"),

    /** Entry title, description and tags, both on library update and from the entry screen. */
    METADATA("metadata"),

    /** Entry titles rendered while browsing a source. */
    BROWSE_TITLE("browse_title"),
    ;

    companion object {
        fun fromKey(key: String): TranslationPurpose? = entries.firstOrNull { it.key == key }
    }
}

/**
 * A named engine configuration. [aiProviderId] and [systemPromptId] are meaningful only when
 * [engineId] is [TranslationEngineId.LLM]; blank means "use the globally active one", which is also
 * what the synthesized default profile relies on.
 */
@Serializable
data class TranslationProfile(
    val id: String,
    val name: String,
    val engineId: TranslationEngineId,
    val aiProviderId: String? = null,
    val systemPromptId: String? = null,
) {
    val deletable: Boolean get() = id != DEFAULT_ID

    companion object {
        const val DEFAULT_ID = "default"
    }
}
