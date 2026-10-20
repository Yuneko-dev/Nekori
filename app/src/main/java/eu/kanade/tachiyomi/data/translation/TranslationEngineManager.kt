package eu.kanade.tachiyomi.data.translation

import eu.kanade.tachiyomi.data.translation.engine.DeepLTranslateEngine
import eu.kanade.tachiyomi.data.translation.engine.GoogleTranslateEngine
import eu.kanade.tachiyomi.data.translation.engine.GoogleTranslateScraperEngine
import eu.kanade.tachiyomi.data.translation.engine.LibreTranslateEngine
import eu.kanade.tachiyomi.data.translation.engine.LlmTranslationEngine
import tachiyomi.domain.translation.model.AiExecutionConfig
import tachiyomi.domain.translation.model.NovelAiTask
import tachiyomi.domain.translation.model.NovelTarget
import tachiyomi.domain.translation.model.TranslationEngine
import tachiyomi.domain.translation.model.TranslationEngineId
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
    private val aiSettings: AiSettingsStore = Injekt.get(),
    private val providedEngines: List<TranslationEngine>? = null,
) {
    /**
     * List of all available translation engines.
     */
    val engines: List<TranslationEngine> by lazy {
        providedEngines ?: listOf(
            GoogleTranslateScraperEngine(),
            LlmTranslationEngine(),
            LibreTranslateEngine(),
            DeepLTranslateEngine(),
            GoogleTranslateEngine(),
        )
    }

    /** Engine plus the execution overrides for one purpose. */
    data class Resolved(val engine: TranslationEngine, val config: AiExecutionConfig?)

    /** The engine and overrides [purpose] should use. */
    fun resolve(purpose: TranslationPurpose, target: NovelTarget? = null): Resolved {
        val engine = getEngineById(TranslationEngineId.fromKey(preferences.engineId(purpose).get()))
        // Only the LLM engine reads the config, and building it decodes the provider and guidelines
        // stores; skip that work entirely for the others.
        val config = if (engine.id == TranslationEngineId.LLM) {
            aiSettings.resolveConfig(
                preferences.translationProviderId().get(),
                preferences.translationGuidelinesId().get(),
                target.takeIf { purpose == TranslationPurpose.CHAPTER },
                NovelAiTask.TRANSLATION,
            )
        } else {
            null
        }
        return Resolved(engine, config)
    }

    /**
     * Get an engine by its ID.
     */
    private fun getEngineById(id: TranslationEngineId): TranslationEngine = engines.first { it.id == id }

    /**
     * The engine [purpose] should use, or null when it is not fully configured.
     */
    fun getEngine(purpose: TranslationPurpose): TranslationEngine? {
        val (engine, config) = resolve(purpose)
        return engine.takeIf { it.isConfigured(config) }
    }

    /**
     * Get supported languages for the engine [purpose] uses.
     */
    fun getSupportedLanguages(purpose: TranslationPurpose): List<Pair<String, String>> =
        selectedEngine(purpose).supportedLanguages

    /** Language detection needs the engine, not the optional Guidelines store. */
    fun selectedEngine(purpose: TranslationPurpose): TranslationEngine =
        getEngineById(TranslationEngineId.fromKey(preferences.engineId(purpose).get()))

    suspend fun translate(purpose: TranslationPurpose, request: TranslationRequest): TranslationResult {
        return translate(resolve(purpose), request)
    }

    /** A chapter keeps this same engine/config through title, chunks and retries. */
    suspend fun translate(resolved: Resolved, request: TranslationRequest): TranslationResult {
        val (engine, config) = resolved
        return AiRetryPolicy.execute(
            retries = preferences.requestRetryCount().get(),
            failureCode = { (it as? TranslationResult.Error)?.errorCode },
        ) { engine.translate(request.copy(config = config)) }
    }
}
