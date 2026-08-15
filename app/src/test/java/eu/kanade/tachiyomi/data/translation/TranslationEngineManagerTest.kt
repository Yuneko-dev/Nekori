package eu.kanade.tachiyomi.data.translation

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.translation.model.AIProvider
import tachiyomi.domain.translation.model.AIProviderType
import tachiyomi.domain.translation.model.TranslationEngine
import tachiyomi.domain.translation.model.TranslationEngineId
import tachiyomi.domain.translation.model.TranslationProfile
import tachiyomi.domain.translation.model.TranslationProfileConfig
import tachiyomi.domain.translation.model.TranslationPurpose
import tachiyomi.domain.translation.model.TranslationRequest
import tachiyomi.domain.translation.model.TranslationResult
import tachiyomi.domain.translation.service.TranslationPreferences

class TranslationEngineManagerTest {
    private val preferences = TranslationPreferences(InMemoryPreferenceStore())
    private val profileStore = TranslationProfileStore(preferences, Json)
    private val aiSettings = AiSettingsStore(preferences, Json)
    private val llm = FakeEngine(
        id = TranslationEngineId.LLM,
        configured = { it?.provider != null },
    )
    private val manager = TranslationEngineManager(preferences, profileStore, aiSettings, listOf(llm))

    @Test
    fun `purposes resolve different providers`() {
        val chapterProvider = provider("chapter")
        val browseProvider = provider("browse")
        aiSettings.saveProvider(chapterProvider)
        aiSettings.saveProvider(browseProvider)
        assign(TranslationPurpose.CHAPTER, "chapter-profile", chapterProvider.id)
        assign(TranslationPurpose.BROWSE_TITLE, "browse-profile", browseProvider.id)

        manager.resolve(TranslationPurpose.CHAPTER).config?.provider shouldBe chapterProvider
        manager.resolve(TranslationPurpose.BROWSE_TITLE).config?.provider shouldBe browseProvider
    }

    @Test
    fun `profile without provider uses the active provider`() {
        val active = provider("active")
        aiSettings.saveProvider(active)
        assign(TranslationPurpose.CHAPTER, "chapter-profile", null)

        manager.resolve(TranslationPurpose.CHAPTER).config?.provider shouldBe active
    }

    @Test
    fun `profile pointing at a deleted provider is unconfigured`() {
        aiSettings.saveProvider(provider("active"))
        assign(TranslationPurpose.CHAPTER, "chapter-profile", "deleted")

        manager.getEngine(TranslationPurpose.CHAPTER) shouldBe null
    }

    @Test
    fun `non LLM engines receive no profile config`() {
        val free = FakeEngine(TranslationEngineId.GOOGLE_FREE)
        val freeManager = TranslationEngineManager(preferences, profileStore, aiSettings, listOf(free))

        freeManager.getEngine(TranslationPurpose.CHAPTER) shouldBe free
        free.configurationChecks shouldBe 1
        free.configChecked shouldBe null
        freeManager.resolve(TranslationPurpose.CHAPTER).config shouldBe null
    }

    @Test
    fun `translate injects the purpose profile config`() = runTest {
        val active = provider("active")
        aiSettings.saveProvider(active)
        assign(TranslationPurpose.CHAPTER, "chapter-profile", active.id)

        manager.translate(
            TranslationPurpose.CHAPTER,
            TranslationRequest(listOf("text"), "en", "vi"),
        )

        llm.lastRequest?.config?.provider shouldBe active
    }

    private fun assign(purpose: TranslationPurpose, profileId: String, providerId: String?) {
        profileStore.save(
            TranslationProfile(
                id = profileId,
                name = profileId,
                engineId = TranslationEngineId.LLM,
                aiProviderId = providerId,
            ),
        )
        profileStore.assign(purpose, profileId)
    }

    private fun provider(id: String) = AIProvider(
        id = id,
        alias = id,
        type = AIProviderType.CUSTOM_OPENAI,
        endpoint = "https://example.com/v1",
        model = "model",
    )

    private class FakeEngine(
        override val id: TranslationEngineId,
        private val configured: (TranslationProfileConfig?) -> Boolean = { true },
    ) : TranslationEngine {
        override val name = id.key
        override val requiresApiKey = false
        override val isRateLimited = false
        override val isOffline = false
        override val supportedLanguages = emptyList<Pair<String, String>>()
        var configChecked: TranslationProfileConfig? = null
        var lastRequest: TranslationRequest? = null
        var configurationChecks = 0

        override fun isConfigured(config: TranslationProfileConfig?): Boolean {
            configurationChecks++
            configChecked = config
            return configured(config)
        }

        override suspend fun translate(request: TranslationRequest): TranslationResult {
            lastRequest = request
            return TranslationResult.Success(request.texts)
        }
    }
}
