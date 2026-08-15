package eu.kanade.tachiyomi.data.translation

import kotlinx.serialization.json.Json
import tachiyomi.domain.translation.model.DEFAULT_PROFILE_ID
import tachiyomi.domain.translation.model.TranslationEngineId
import tachiyomi.domain.translation.model.TranslationProfile
import tachiyomi.domain.translation.model.TranslationPurpose
import tachiyomi.domain.translation.service.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class TranslationProfileStore(
    private val preferences: TranslationPreferences = Injekt.get(),
    json: Json = Injekt.get(),
) : ProfileStore<TranslationProfile, TranslationPurpose>(json, TranslationPurpose.entries) {

    override val serializer = TranslationProfile.serializer()
    override val profilesPreference = preferences.translationProfilesJson()
    override val assignmentsPreference = preferences.translationTaskProfilesJson()

    override fun synthesizeDefault() = TranslationProfile(
        id = DEFAULT_PROFILE_ID,
        name = "",
        engineId = TranslationEngineId.fromKey(preferences.selectedEngineId().get()),
        aiProviderId = preferences.activeAiProviderId().get().ifBlank { null },
        guidelinesId = preferences.activeGuidelinesId().get().ifBlank { null },
    )
}
