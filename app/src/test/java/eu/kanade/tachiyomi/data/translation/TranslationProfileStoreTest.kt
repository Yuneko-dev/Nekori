package eu.kanade.tachiyomi.data.translation

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.translation.model.TranslationEngineId
import tachiyomi.domain.translation.model.TranslationProfile
import tachiyomi.domain.translation.model.TranslationPurpose
import tachiyomi.domain.translation.service.TranslationPreferences

class TranslationProfileStoreTest {
    private val preferences = TranslationPreferences(InMemoryPreferenceStore())
    private val store = TranslationProfileStore(preferences, Json { ignoreUnknownKeys = true })

    @Test
    fun `default profile mirrors the pre-profile global settings`() {
        preferences.selectedEngineId().set(TranslationEngineId.DEEPL.key)
        preferences.activeAiProviderId().set("provider-a")
        preferences.activeGuidelinesId().set("prompt-a")

        val default = store.profiles().single()

        default.id shouldBe TranslationProfile.DEFAULT_ID
        default.engineId shouldBe TranslationEngineId.DEEPL
        default.aiProviderId shouldBe "provider-a"
        default.guidelinesId shouldBe "prompt-a"
    }

    @Test
    fun `every purpose resolves to the default until assigned`() {
        TranslationPurpose.entries.forEach { purpose ->
            store.profileFor(purpose).id shouldBe TranslationProfile.DEFAULT_ID
        }
    }

    @Test
    fun `purposes can resolve to different profiles`() {
        store.save(fast)
        store.assign(TranslationPurpose.BROWSE_TITLE, fast.id)

        store.profileFor(TranslationPurpose.BROWSE_TITLE) shouldBe fast
        store.profileFor(TranslationPurpose.CHAPTER).id shouldBe TranslationProfile.DEFAULT_ID
    }

    @Test
    fun `deleting an assigned profile falls back to the default instead of dangling`() {
        store.save(fast)
        store.assign(TranslationPurpose.BROWSE_TITLE, fast.id)

        store.delete(fast.id)

        store.profileFor(TranslationPurpose.BROWSE_TITLE).id shouldBe TranslationProfile.DEFAULT_ID
    }

    @Test
    fun `an assignment pointing at a missing profile resolves to the default`() {
        store.assign(TranslationPurpose.CHAPTER, "never-created")

        store.profileFor(TranslationPurpose.CHAPTER).id shouldBe TranslationProfile.DEFAULT_ID
    }

    @Test
    fun `the default profile cannot be deleted`() {
        shouldThrow<IllegalArgumentException> { store.delete(TranslationProfile.DEFAULT_ID) }
    }

    @Test
    fun `saving an existing profile updates instead of duplicating`() {
        store.save(fast)
        store.save(fast.copy(name = "Renamed"))

        val stored = store.profiles().filter { it.id == fast.id }
        stored.size shouldBe 1
        stored.single().name shouldBe "Renamed"
    }

    @Test
    fun `an overriding default profile replaces the synthesized one and stays first`() {
        store.save(fast)
        store.save(TranslationProfile(TranslationProfile.DEFAULT_ID, "", TranslationEngineId.LIBRE))

        val profiles = store.profiles()
        profiles.first().id shouldBe TranslationProfile.DEFAULT_ID
        profiles.first().engineId shouldBe TranslationEngineId.LIBRE
        profiles.size shouldBe 2
    }

    @Test
    fun `profiles written before the guidelines rename still read`() {
        preferences.translationProfilesJson().set(
            """[{"id":"fast","name":"Fast","engineId":"LLM","aiProviderId":"p1","systemPromptId":"g1"}]""",
        )

        val stored = store.profiles().first { it.id == "fast" }

        stored.aiProviderId shouldBe "p1"
        stored.guidelinesId shouldBe "g1"
    }

    @Test
    fun `corrupt stored json degrades to the synthesized default`() {
        preferences.translationProfilesJson().set("{ not json")
        preferences.translationTaskProfilesJson().set("also not json")

        store.profiles().single().id shouldBe TranslationProfile.DEFAULT_ID
    }

    private val fast = TranslationProfile(
        id = "fast",
        name = "Fast",
        engineId = TranslationEngineId.GOOGLE_FREE,
    )
}
