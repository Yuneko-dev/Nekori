package eu.kanade.tachiyomi.data.translation

import eu.kanade.tachiyomi.data.translation.engine.DeepLTranslateEngine
import eu.kanade.tachiyomi.data.translation.engine.GoogleTranslateEngine
import eu.kanade.tachiyomi.data.translation.engine.GoogleTranslateScraperEngine
import eu.kanade.tachiyomi.data.translation.engine.LibreTranslateEngine
import eu.kanade.tachiyomi.data.translation.engine.LlmTranslationEngine
import tachiyomi.domain.translation.model.AIProvider
import tachiyomi.domain.translation.model.SystemPrompt
import tachiyomi.domain.translation.model.TranslationEngine
import tachiyomi.domain.translation.model.TranslationEngineId
import tachiyomi.domain.translation.model.TranslationProfile
import tachiyomi.domain.translation.model.TranslationProfileConfig
import tachiyomi.domain.translation.model.TranslationPurpose
import tachiyomi.domain.translation.model.TranslationRequest
import tachiyomi.domain.translation.model.TranslationResult
import tachiyomi.domain.translation.service.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Resolves each [TranslationPurpose] to its configured engine and executes translations.
 *
 * Resolution lives here rather than in a separate interactor because this class already owns engine
 * selection; a resolver would need the same dependencies and add an indirection for one caller.
 */
class TranslationEngineManager(
    private val preferences: TranslationPreferences = Injekt.get(),
    private val profileStore: TranslationProfileStore = Injekt.get(),
    private val aiSettings: AiSettingsStore = Injekt.get(),
) {
    /**
     * List of all available translation engines.
     */
    val engines: List<TranslationEngine> by lazy {
        listOf(
            GoogleTranslateScraperEngine(),
            LlmTranslationEngine(),
            LibreTranslateEngine(),
            DeepLTranslateEngine(),
            GoogleTranslateEngine(),
        )
    }

    /** Engine plus the execution overrides for one task. */
    data class Resolved(val engine: TranslationEngine, val config: TranslationProfileConfig?)

    /**
     * The engine and overrides [task] should use. Falls back to the globally selected engine when a
     * profile names an engine that no longer exists, so a corrupt preference degrades instead of
     * failing.
     */
    fun resolve(task: TranslationPurpose): Resolved {
        val profile = profileStore.profileFor(task)
        val engine = getEngineById(profile.engineId) ?: getSelectedEngine()
        // Only the LLM engine reads the config, and building it decodes the provider and prompt
        // stores; skip that work entirely for the others.
        return Resolved(engine, if (engine.id == TranslationEngineId.LLM) configFor(profile) else null)
    }

    /**
     * The provider a profile names, or the globally active one. A null id means "use the active
     * setting", which is how the synthesized default profile reproduces pre-profile behaviour.
     */
    private fun providerOf(profile: TranslationProfile): AIProvider? = profile.aiProviderId
        ?.let { id -> aiSettings.providers().firstOrNull { it.id == id } }
        ?: aiSettings.activeProvider()

    /** The prompt a profile names, or the globally active one. */
    private fun promptOf(profile: TranslationProfile): SystemPrompt = profile.systemPromptId
        ?.let { id -> aiSettings.prompts().firstOrNull { it.id == id } }
        ?: aiSettings.activePrompt()

    /** LLM overrides for [profile]. */
    private fun configFor(profile: TranslationProfile): TranslationProfileConfig {
        val provider = providerOf(profile)
        return TranslationProfileConfig(
            provider = provider,
            apiKey = provider?.let { aiSettings.apiKey(it.id) }.orEmpty(),
            guidelines = promptOf(profile).guidelines,
        )
    }

    /** Human-readable summary of a profile's configuration, for the settings list. */
    fun describe(profile: TranslationProfile): String {
        val engineName = getEngineById(profile.engineId)?.name ?: profile.engineId.key
        if (profile.engineId != TranslationEngineId.LLM) return engineName
        val prompt = promptOf(profile)
        return listOfNotNull(
            engineName,
            providerOf(profile)?.alias,
            prompt.name.takeIf { it.isNotBlank() && prompt.id != SystemPrompt.DEFAULT_ID },
        ).joinToString(" · ")
    }

    /**
     * Get the globally selected translation engine. Retained as the fallback for a missing or
     * corrupt profile, and for the engine picker inside the profile editor.
     */
    fun getSelectedEngine(): TranslationEngine {
        val selectedId = preferences.selectedEngineId().get()
        return engines.find { it.id.key == selectedId } ?: engines.first()
    }

    /**
     * Get an engine by its ID.
     */
    fun getEngineById(id: TranslationEngineId): TranslationEngine? {
        return engines.find { it.id == id }
    }

    /**
     * The engine [task] should use, or null when its profile is not fully configured.
     */
    fun getEngine(task: TranslationPurpose): TranslationEngine? {
        val (engine, config) = resolve(task)
        return engine.takeIf { it.isConfigured(config) }
    }

    /**
     * Get supported languages for the engine [task] uses.
     */
    fun getSupportedLanguages(task: TranslationPurpose): List<Pair<String, String>> {
        return resolve(task).engine.supportedLanguages
    }

    suspend fun translate(task: TranslationPurpose, request: TranslationRequest): TranslationResult {
        val (engine, config) = resolve(task)
        return TranslationRetryPolicy.execute(
            retries = preferences.requestRetryCount().get(),
        ) { engine.translate(request.copy(config = config)) }
    }
}
