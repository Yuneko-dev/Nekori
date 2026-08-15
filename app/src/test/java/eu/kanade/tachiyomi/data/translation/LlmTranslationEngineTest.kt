package eu.kanade.tachiyomi.data.translation

import eu.kanade.tachiyomi.data.translation.engine.LlmTranslationEngine
import eu.kanade.tachiyomi.data.translation.engine.parseProviderModels
import eu.kanade.tachiyomi.data.translation.engine.resolveProviderUrl
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.translation.model.AIProvider
import tachiyomi.domain.translation.model.AIProviderType
import tachiyomi.domain.translation.model.TranslationProfileConfig
import tachiyomi.domain.translation.service.TranslationPreferences

class LlmTranslationEngineTest {

    @Test
    fun `gemini endpoint encodes api key`() {
        val url = resolveProviderUrl(provider(AIProviderType.GEMINI), "/v1beta/models", "a+b&c")

        url.queryParameter("key") shouldBe "a+b&c"
    }

    @Test
    fun `openai endpoint does not put api key in query`() {
        val url = resolveProviderUrl(provider(AIProviderType.OPENAI), "/models", "secret")

        url.toString() shouldBe "https://example.com/v1/models"
        url.queryParameter("key") shouldBe null
    }

    @Test
    fun `model loading parser supports openai and gemini responses`() {
        parseProviderModels(Json, """{"data":[{"id":"z"},{"id":"a"}]}""") shouldContainExactly listOf("a", "z")
        parseProviderModels(
            Json,
            """{"models":[{"name":"models/gemini-pro"},{"name":"models/gemini-flash"}]}""",
        ) shouldContainExactly listOf("gemini-flash", "gemini-pro")
    }

    @Test
    fun `a profile provider is used even when no global provider is active`() {
        val engine = engine()
        val profileProvider = provider(AIProviderType.CUSTOM_OPENAI).copy(id = "from-profile")

        // No global active provider configured, so only the profile can satisfy this.
        engine.isConfigured() shouldBe false
        engine.isConfigured(TranslationProfileConfig(provider = profileProvider)) shouldBe true
    }

    @Test
    fun `a profile provider needing a key is unconfigured until the key is present`() {
        val engine = engine()
        val keyed = provider(AIProviderType.OPENAI)

        engine.isConfigured(TranslationProfileConfig(provider = keyed)) shouldBe false
        engine.isConfigured(TranslationProfileConfig(provider = keyed, apiKey = "secret")) shouldBe true
    }

    @Test
    fun `no profile falls back to the globally active provider`() {
        val preferences = TranslationPreferences(InMemoryPreferenceStore())
        val settings = AiSettingsStore(preferences, Json)
        settings.saveProvider(provider(AIProviderType.CUSTOM_OPENAI))

        engine(preferences, settings).isConfigured() shouldBe true
    }

    private fun engine(
        preferences: TranslationPreferences = TranslationPreferences(InMemoryPreferenceStore()),
        settings: AiSettingsStore = AiSettingsStore(preferences, Json),
    ) = LlmTranslationEngine(
        // Never touched by isConfigured: the client is created lazily on the first request.
        networkHelper = mockk(),
        preferences = preferences,
        settings = settings,
        json = Json,
    )

    private fun provider(type: AIProviderType) = AIProvider(
        id = "id",
        alias = "Provider",
        type = type,
        endpoint = "https://example.com/v1",
        model = "model",
    )
}
