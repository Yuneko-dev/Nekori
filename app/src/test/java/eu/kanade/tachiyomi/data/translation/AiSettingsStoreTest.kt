package eu.kanade.tachiyomi.data.translation

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.translation.model.AIProvider
import tachiyomi.domain.translation.model.AIProviderType
import tachiyomi.domain.translation.model.UserGuidelines
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
    fun `deleting active custom guidelines returns to default`() {
        store.saveGuidelines(names)
        store.setActiveGuidelines(names.id)

        store.deleteGuidelines(names.id)

        preferences.activeGuidelinesId().get() shouldBe UserGuidelines.DEFAULT_ID
        store.resolveConfig(null, null).guidelines shouldBe ""
    }

    @Test
    fun `guidelines written under the old system-prompt name still read`() {
        preferences.userGuidelinesJson().set(
            """[{"id":"default","name":"Default","guidelines":""},{"id":"c","name":"C","guidelines":"Keep honorifics"}]""",
        )

        store.resolveConfig(null, "c").guidelines shouldBe "Keep honorifics"
    }

    @Test
    fun `naming nothing resolves to the active provider and guidelines`() {
        store.saveProvider(provider("one"), "secret")
        store.saveGuidelines(names)
        store.setActiveGuidelines(names.id)

        val config = store.resolveConfig(null, null)

        config.provider shouldBe provider("one")
        config.apiKey shouldBe "secret"
        config.guidelines shouldBe "Keep names"
    }

    @Test
    fun `naming a provider and guidelines overrides the active ones`() {
        store.saveProvider(provider("one"), "active-key")
        store.saveProvider(provider("two"), "named-key")
        store.saveGuidelines(names)

        val config = store.resolveConfig("two", names.id)

        config.provider?.id shouldBe "two"
        config.apiKey shouldBe "named-key"
        config.guidelines shouldBe "Keep names"
    }

    @Test
    fun `a deleted provider leaves the caller unconfigured instead of borrowing the active one`() {
        store.saveProvider(provider("one"), "secret")

        val config = store.resolveConfig("gone", null)

        config.provider shouldBe null
        config.apiKey shouldBe ""
    }

    @Test
    fun `deleted guidelines fall back to no instructions, not to the active ones`() {
        store.saveGuidelines(names)
        store.setActiveGuidelines(names.id)

        store.resolveConfig(null, "gone").guidelines shouldBe ""
    }

    @Test
    fun `a blank id counts as naming nothing`() {
        store.saveProvider(provider("one"))

        store.resolveConfig("", "").provider?.id shouldBe "one"
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

    private val names = UserGuidelines("custom", "Names", "Keep names")

    private fun provider(id: String) = AIProvider(
        id = id,
        alias = "Provider",
        type = AIProviderType.OPENAI,
        endpoint = "https://api.openai.com/v1",
        model = "model",
    )
}
