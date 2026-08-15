package eu.kanade.tachiyomi.data.translation

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.translation.model.AiTaskProfile
import tachiyomi.domain.translation.model.AiTaskPurpose
import tachiyomi.domain.translation.model.DEFAULT_PROFILE_ID
import tachiyomi.domain.translation.service.TranslationPreferences

class AiTaskProfileStoreTest {
    private val preferences = TranslationPreferences(InMemoryPreferenceStore())
    private val store = AiTaskProfileStore(preferences, Json { ignoreUnknownKeys = true })

    @Test
    fun `the default profile mirrors the globally active settings`() {
        preferences.activeAiProviderId().set("provider-a")
        preferences.activeGuidelinesId().set("guidelines-a")

        val default = store.profiles().single()

        default.id shouldBe DEFAULT_PROFILE_ID
        default.providerId shouldBe "provider-a"
        default.guidelinesId shouldBe "guidelines-a"
    }

    @Test
    fun `every purpose resolves to the default until assigned`() {
        AiTaskPurpose.entries.forEach { purpose ->
            store.profileFor(purpose).id shouldBe DEFAULT_PROFILE_ID
        }
    }

    @Test
    fun `an assigned purpose resolves to its own profile`() {
        store.save(cheap)
        store.assign(AiTaskPurpose.CHAPTER_SUMMARY, cheap.id)

        store.profileFor(AiTaskPurpose.CHAPTER_SUMMARY) shouldBe cheap
    }

    @Test
    fun `deleting an assigned profile falls back to the default instead of dangling`() {
        store.save(cheap)
        store.assign(AiTaskPurpose.CHAPTER_SUMMARY, cheap.id)

        store.delete(cheap.id)

        store.profileFor(AiTaskPurpose.CHAPTER_SUMMARY).id shouldBe DEFAULT_PROFILE_ID
    }

    @Test
    fun `the default profile cannot be deleted`() {
        shouldThrow<IllegalArgumentException> { store.delete(DEFAULT_PROFILE_ID) }
    }

    @Test
    fun `corrupt stored json degrades to the synthesized default`() {
        preferences.aiTaskProfilesJson().set("{ not json")
        preferences.aiTaskAssignmentsJson().set("also not json")

        store.profiles().single().id shouldBe DEFAULT_PROFILE_ID
    }

    @Test
    fun `task profiles and translation profiles do not share storage`() {
        val translations = TranslationProfileStore(preferences, Json { ignoreUnknownKeys = true })
        store.save(cheap)

        translations.profiles().map { it.id } shouldBe listOf(DEFAULT_PROFILE_ID)
    }

    private val cheap = AiTaskProfile(id = "cheap", name = "Cheap", providerId = "p2")
}
