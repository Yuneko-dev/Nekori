package eu.kanade.tachiyomi.data.translation

import kotlinx.serialization.json.Json
import tachiyomi.domain.translation.model.TranslationEngineId
import tachiyomi.domain.translation.model.TranslationProfile
import tachiyomi.domain.translation.model.TranslationPurpose
import tachiyomi.domain.translation.service.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Stores named engine configurations and which purpose uses which, both as JSON in preferences -
 * the same shape [AiSettingsStore] uses for providers and prompts.
 */
class TranslationProfileStore(
    private val preferences: TranslationPreferences = Injekt.get(),
    private val json: Json = Injekt.get(),
) {

    /**
     * All profiles, with the default guaranteed to exist and to come first.
     *
     * The default is synthesized on read rather than written during an upgrade step, mirroring
     * [AiSettingsStore.prompts]. That is what makes this feature migration-free: before the user
     * touches anything, the default mirrors the pre-existing global engine/provider/prompt, so
     * behaviour is unchanged.
     */
    fun profiles(): List<TranslationProfile> {
        val stored = decode(preferences.translationProfilesJson().get(), emptyList<TranslationProfile>())
        return listOf(stored.firstOrNull { it.id == TranslationProfile.DEFAULT_ID } ?: synthesizeDefault()) +
            stored.filterNot { it.id == TranslationProfile.DEFAULT_ID }
    }

    private fun synthesizeDefault() = TranslationProfile(
        id = TranslationProfile.DEFAULT_ID,
        name = "",
        engineId = TranslationEngineId.fromKey(preferences.selectedEngineId().get()),
        aiProviderId = preferences.activeAiProviderId().get().ifBlank { null },
        systemPromptId = preferences.activeSystemPromptId().get().ifBlank { null },
    )

    fun profile(id: String): TranslationProfile? = profiles().firstOrNull { it.id == id }

    /** The profile assigned to [purpose], falling back to the default when unassigned or dangling. */
    fun profileFor(purpose: TranslationPurpose): TranslationProfile {
        val all = profiles()
        val assignedId = assignments()[purpose]
        return all.firstOrNull { it.id == assignedId } ?: all.first()
    }

    private fun assignments(): Map<TranslationPurpose, String> =
        decode(preferences.translationTaskProfilesJson().get(), emptyMap<String, String>())
            .mapNotNull { (key, id) -> TranslationPurpose.fromKey(key)?.let { it to id } }
            .toMap()

    fun assign(purpose: TranslationPurpose, profileId: String) {
        val updated = assignments().mapKeys { it.key.key } + (purpose.key to profileId)
        preferences.translationTaskProfilesJson().set(json.encodeToString(updated))
    }

    fun save(profile: TranslationProfile) {
        require(profile.id.isNotBlank()) { "Profile id must not be blank" }
        require(profile.id == TranslationProfile.DEFAULT_ID || profile.name.isNotBlank()) {
            "Profile name must not be blank"
        }
        val profiles = profiles().toMutableList()
        val index = profiles.indexOfFirst { it.id == profile.id }
        if (index >= 0) profiles[index] = profile else profiles += profile
        preferences.translationProfilesJson().set(json.encodeToString(profiles))
    }

    fun delete(id: String) {
        require(id != TranslationProfile.DEFAULT_ID) { "The default profile cannot be deleted" }
        preferences.translationProfilesJson().set(
            json.encodeToString(profiles().filterNot { it.id == id }),
        )
        // Drop assignments pointing at it so profileFor() falls back to the default instead of
        // silently resolving nothing.
        val remaining = assignments().filterValues { it != id }.mapKeys { it.key.key }
        preferences.translationTaskProfilesJson().set(json.encodeToString(remaining))
    }

    private inline fun <reified T> decode(value: String, fallback: T): T =
        runCatching { json.decodeFromString<T>(value) }.getOrDefault(fallback)
}
