package eu.kanade.tachiyomi.data.translation

import kotlinx.serialization.json.Json
import tachiyomi.domain.translation.model.AiTaskProfile
import tachiyomi.domain.translation.model.AiTaskPurpose
import tachiyomi.domain.translation.model.DEFAULT_PROFILE_ID
import tachiyomi.domain.translation.service.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AiTaskProfileStore(
    private val preferences: TranslationPreferences = Injekt.get(),
    json: Json = Injekt.get(),
) : ProfileStore<AiTaskProfile, AiTaskPurpose>(json, AiTaskPurpose.entries) {

    override val serializer = AiTaskProfile.serializer()
    override val profilesPreference = preferences.aiTaskProfilesJson()
    override val assignmentsPreference = preferences.aiTaskAssignmentsJson()

    override fun synthesizeDefault() = AiTaskProfile(
        id = DEFAULT_PROFILE_ID,
        name = "",
        providerId = preferences.activeAiProviderId().get().ifBlank { null },
        guidelinesId = preferences.activeGuidelinesId().get().ifBlank { null },
    )
}
