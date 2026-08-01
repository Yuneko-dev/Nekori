package eu.kanade.tachiyomi.data.translation

import eu.kanade.tachiyomi.data.translation.engine.DeepLTranslateEngine
import eu.kanade.tachiyomi.data.translation.engine.GoogleTranslateEngine
import eu.kanade.tachiyomi.data.translation.engine.GoogleTranslateScraperEngine
import eu.kanade.tachiyomi.data.translation.engine.LibreTranslateEngine
import eu.kanade.tachiyomi.data.translation.engine.LlmTranslationEngine
import tachiyomi.domain.translation.model.TranslationEngine
import tachiyomi.domain.translation.model.TranslationEngineId
import tachiyomi.domain.translation.model.TranslationRequest
import tachiyomi.domain.translation.model.TranslationResult
import tachiyomi.domain.translation.service.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Manager for translation engines.
 * Provides access to available engines and manages the selected engine.
 */
class TranslationEngineManager(
    private val preferences: TranslationPreferences = Injekt.get(),
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

    /**
     * Get the currently selected translation engine.
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
     * Set the selected translation engine.
     */
    fun setSelectedEngine(engine: TranslationEngine) {
        preferences.selectedEngineId().set(engine.id.key)
    }

    /**
     * Get the currently configured engine, or null if not configured.
     */
    fun getEngine(): TranslationEngine? {
        val engine = getSelectedEngine()
        return if (engine.isConfigured()) engine else null
    }

    /**
     * Get supported languages for the selected engine.
     */
    fun getSupportedLanguages(): List<Pair<String, String>> {
        return getSelectedEngine().supportedLanguages
    }

    suspend fun translate(request: TranslationRequest): TranslationResult = TranslationRetryPolicy.execute(
        retries = preferences.requestRetryCount().get(),
    ) { getSelectedEngine().translate(request) }
}
