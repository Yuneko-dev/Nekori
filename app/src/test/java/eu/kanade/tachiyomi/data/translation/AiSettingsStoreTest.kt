package eu.kanade.tachiyomi.data.translation

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.translation.model.AIProvider
import tachiyomi.domain.translation.model.AIProviderType
import tachiyomi.domain.translation.model.SystemPrompt
import tachiyomi.domain.translation.service.TranslationPreferences

class AiSettingsStoreTest {
    private val preferences = TranslationPreferences(InMemoryPreferenceStore())
    private val store = AiSettingsStore(preferences, Json { ignoreUnknownKeys = true })

    @Test
    fun `first provider becomes active and deleting it leaves active empty`() {
        val provider = provider("one")
        store.saveProvider(provider, "secret")

        store.activeProvider() shouldBe provider
        store.apiKey(provider.id) shouldBe "secret"

        store.deleteProvider(provider.id)
        store.activeProvider() shouldBe null
        preferences.activeAiProviderId().get() shouldBe ""
    }

    @Test
    fun `deleting active custom prompt returns to default`() {
        val prompt = SystemPrompt("custom", "Names", "Keep names")
        store.savePrompt(prompt)
        store.setActivePrompt(prompt.id)

        store.deletePrompt(prompt.id)

        store.activePrompt() shouldBe SystemPrompt.DEFAULT
    }

    @Test
    fun `saving existing provider updates instead of duplicating`() {
        store.saveProvider(provider("one"))
        store.saveProvider(provider("one").copy(alias = "Updated"))

        store.providers().single().alias shouldBe "Updated"
    }

    @Test
    fun `saving another provider does not replace an intentionally empty active provider`() {
        store.saveProvider(provider("one"))
        store.saveProvider(provider("two"))
        store.deleteProvider("one")

        store.saveProvider(provider("two").copy(alias = "Updated"))

        store.activeProvider() shouldBe null
    }

    @Test
    fun `provider endpoint must be a valid url`() {
        shouldThrow<IllegalArgumentException> {
            store.saveProvider(provider("bad").copy(endpoint = "not a url"))
        }
    }

    private fun provider(id: String) = AIProvider(
        id = id,
        alias = "Provider",
        type = AIProviderType.OPENAI,
        endpoint = "https://api.openai.com/v1",
        model = "model",
    )
}
