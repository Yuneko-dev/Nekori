package eu.kanade.tachiyomi.data.translation.engine

import eu.kanade.tachiyomi.data.translation.InvalidStructuredOutputException
import eu.kanade.tachiyomi.data.translation.LlmGenerator
import eu.kanade.tachiyomi.data.translation.LlmPromptBuilder
import eu.kanade.tachiyomi.data.translation.LlmResponseParser
import eu.kanade.tachiyomi.data.translation.TranslationOutputSchema
import kotlinx.coroutines.CancellationException
import tachiyomi.domain.translation.model.AiErrorCode
import tachiyomi.domain.translation.model.AiExecutionConfig
import tachiyomi.domain.translation.model.LanguageCodes
import tachiyomi.domain.translation.model.LlmGenerationRequest
import tachiyomi.domain.translation.model.LlmOutputFormat
import tachiyomi.domain.translation.model.LlmResult
import tachiyomi.domain.translation.model.TranslationEngine
import tachiyomi.domain.translation.model.TranslationEngineId
import tachiyomi.domain.translation.model.TranslationRequest
import tachiyomi.domain.translation.model.TranslationResult
import tachiyomi.domain.translation.service.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Turns a translation request into a prompt and reads the answer back onto the source paragraphs.
 * Everything provider-shaped belongs to [LlmGenerator].
 */
class LlmTranslationEngine(
    private val generator: LlmGenerator = Injekt.get(),
    private val preferences: TranslationPreferences = Injekt.get(),
) : TranslationEngine {

    override val id = TranslationEngineId.LLM
    override val name = "API LLM"
    override val requiresApiKey = true
    override val isRateLimited = true
    override val supportedLanguages = LanguageCodes.GOOGLE_TRANSLATE_LANGUAGES

    override fun isConfigured(config: AiExecutionConfig?): Boolean = config?.isComplete == true

    override suspend fun translate(request: TranslationRequest): TranslationResult {
        val config = request.config
            ?: return TranslationResult.Error("No translation profile configuration", AiErrorCode.REQUEST_INVALID)
        val structured = preferences.structuredOutput().get()
        val prompt = LlmPromptBuilder.build(
            texts = request.texts,
            sourceLanguage = LanguageCodes.getDisplayName(request.sourceLanguage),
            targetLanguage = LanguageCodes.getDisplayName(request.targetLanguage),
            guidelines = config.guidelines.orEmpty(),
            structuredOutput = structured,
            context = request.context,
        )
        val generation = LlmGenerationRequest(
            systemPrompt = prompt.system,
            input = prompt.user,
            outputFormat = if (structured) TranslationOutputSchema.format else LlmOutputFormat.Text,
        )
        return when (val result = generator.generate(config, generation)) {
            is LlmResult.Failure -> TranslationResult.Error(result.message, result.code)
            is LlmResult.Success -> try {
                TranslationResult.Success(LlmResponseParser.parse(result.text, request.texts, structured))
            } catch (e: CancellationException) {
                throw e
            } catch (e: InvalidStructuredOutputException) {
                TranslationResult.Error(e.message.orEmpty(), AiErrorCode.STRUCTURED_OUTPUT_INVALID)
            }
        }
    }
}
