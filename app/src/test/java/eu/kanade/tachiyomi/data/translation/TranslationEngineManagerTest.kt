package eu.kanade.tachiyomi.data.translation

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.translation.model.AIProvider
import tachiyomi.domain.translation.model.AIProviderType
import tachiyomi.domain.translation.model.AiExecutionConfig
import tachiyomi.domain.translation.model.TranslationEngine
import tachiyomi.domain.translation.model.TranslationEngineId
import tachiyomi.domain.translation.model.TranslationPurpose
import tachiyomi.domain.translation.model.TranslationRequest
import tachiyomi.domain.translation.model.TranslationResult
import tachiyomi.domain.translation.service.TranslationPreferences

class TranslationEngineManagerTest {
    private val preferences = TranslationPreferences(InMemoryPreferenceStore())
    private val aiSettings = AiSettingsStore(preferences, Json)
    private val llm = FakeEngine(
        id = TranslationEngineId.LLM,
        configured = { it?.provider != null },
    )
    private val free = FakeEngine(TranslationEngineId.GOOGLE_FREE)
    private val manager = TranslationEngineManager(preferences, aiSettings, listOf(llm, free))

    @Test
    fun `each purpose resolves its own engine`() {
        useEngine(TranslationPurpose.CHAPTER, TranslationEngineId.LLM)
        useEngine(TranslationPurpose.BROWSE_TITLE, TranslationEngineId.GOOGLE_FREE)

        manager.resolve(TranslationPurpose.CHAPTER).engine shouldBe llm
        manager.resolve(TranslationPurpose.BROWSE_TITLE).engine shouldBe free
    }

    @Test
    fun `an unset purpose falls back to the free engine`() {
        manager.resolve(TranslationPurpose.METADATA).engine shouldBe free
    }

    @Test
    fun `the llm engine receives the translation provider`() {
        val provider = provider("chosen")
        aiSettings.saveProvider(provider)
        useEngine(TranslationPurpose.CHAPTER, TranslationEngineId.LLM)
        preferences.translationProviderId().set(provider.id)

        manager.resolve(TranslationPurpose.CHAPTER).config?.provider shouldBe provider
    }

    @Test
    fun `an unnamed provider resolves to the only one configured`() {
        val only = provider("only")
        aiSettings.saveProvider(only)
        useEngine(TranslationPurpose.CHAPTER, TranslationEngineId.LLM)

        manager.resolve(TranslationPurpose.CHAPTER).config?.provider shouldBe only
    }

    @Test
    fun `a deleted provider leaves the engine unconfigured`() {
        aiSettings.saveProvider(provider("kept"))
        useEngine(TranslationPurpose.CHAPTER, TranslationEngineId.LLM)
        preferences.translationProviderId().set("deleted")

        manager.getEngine(TranslationPurpose.CHAPTER) shouldBe null
    }

    @Test
    fun `non LLM engines receive no config`() {
        manager.getEngine(TranslationPurpose.CHAPTER) shouldBe free
        free.configurationChecks shouldBe 1
        free.configChecked shouldBe null
        manager.resolve(TranslationPurpose.CHAPTER).config shouldBe null
    }

    @Test
    fun `translate injects the resolved config`() = runTest {
        val provider = provider("active")
        aiSettings.saveProvider(provider)
        useEngine(TranslationPurpose.CHAPTER, TranslationEngineId.LLM)

        manager.translate(
            TranslationPurpose.CHAPTER,
            TranslationRequest(listOf("text"), "en", "vi"),
        )

        llm.lastRequest?.config?.provider shouldBe provider
    }

    private fun useEngine(purpose: TranslationPurpose, engineId: TranslationEngineId) {
        preferences.engineId(purpose).set(engineId.key)
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
        private val configured: (AiExecutionConfig?) -> Boolean = { true },
    ) : TranslationEngine {
        override val name = id.key
        override val requiresApiKey = false
        override val isRateLimited = false
        override val isOffline = false
        override val supportedLanguages = emptyList<Pair<String, String>>()
        var configChecked: AiExecutionConfig? = null
        var lastRequest: TranslationRequest? = null
        var configurationChecks = 0

        override fun isConfigured(config: AiExecutionConfig?): Boolean {
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
