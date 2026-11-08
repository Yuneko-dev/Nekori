package eu.kanade.tachiyomi.data.translation

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
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
    fun `a saved provider keeps its key and deleting it takes the key with it`() {
        val provider = provider("one")
        store.saveProvider(provider, "secret")

        store.providers().single() shouldBe provider
        store.apiKey(provider.id) shouldBe "secret"

        store.deleteProvider(provider.id)
        store.providers().shouldBeEmpty()
        store.apiKey(provider.id) shouldBe ""
    }

    @Test
    fun `deleted guidelines leave the task with no instructions`() {
        store.saveGuidelines(names)

        store.deleteGuidelines(names.id)

        store.resolveConfig(null, names.id).guidelines shouldBe ""
    }

    @Test
    fun `guidelines written under the old system-prompt name still read`() {
        preferences.userGuidelinesJson().set(
            """[{"id":"default","name":"Default","guidelines":""},{"id":"c","name":"C","guidelines":"Keep honorifics"}]""",
        )

        store.resolveConfig(null, "c").guidelines shouldBe "Keep honorifics"
    }

    @Test
    fun `naming nothing resolves to the first provider and no guidelines`() {
        store.saveProvider(provider("one"), "secret")
        store.saveProvider(provider("two"), "other")
        store.saveGuidelines(names)

        val config = store.resolveConfig(null, null)

        config.provider shouldBe provider("one")
        config.apiKey shouldBe "secret"
        config.guidelines shouldBe ""
    }

    @Test
    fun `naming a provider and guidelines picks exactly those`() {
        store.saveProvider(provider("one"), "active-key")
        store.saveProvider(provider("two"), "named-key")
        store.saveGuidelines(names)

        val config = store.resolveConfig("two", names.id)

        config.provider?.id shouldBe "two"
        config.apiKey shouldBe "named-key"
        config.guidelines shouldBe "Keep names"
    }

    @Test
    fun `a deleted provider leaves the caller unconfigured instead of borrowing another`() {
        store.saveProvider(provider("one"), "secret")

        val config = store.resolveConfig("gone", null)

        config.provider shouldBe null
        config.apiKey shouldBe ""
    }

    @Test
    fun `deleted guidelines fall back to no instructions`() {
        store.saveGuidelines(names)

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
