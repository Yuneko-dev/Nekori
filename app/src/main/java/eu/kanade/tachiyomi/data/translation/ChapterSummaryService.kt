package eu.kanade.tachiyomi.data.translation

import android.app.Application
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.translation.model.AiErrorCode
import tachiyomi.domain.translation.model.AiExecutionConfig
import tachiyomi.domain.translation.model.LanguageCodes
import tachiyomi.domain.translation.model.LlmGenerationRequest
import tachiyomi.domain.translation.model.LlmResult
import tachiyomi.domain.translation.model.NovelAiTask
import tachiyomi.domain.translation.model.NovelTarget
import tachiyomi.domain.translation.service.TranslationPreferences
import tachiyomi.i18n.novel.TDMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Summarizes the chapter the reader is showing.
 *
 * The system prompt and the output contract belong to this task and are not user-editable; what the
 * user configures is which provider runs it and which guidelines it carries.
 */
class ChapterSummaryService(
    private val generator: LlmGenerator = Injekt.get(),
    private val aiSettings: AiSettingsStore = Injekt.get(),
    private val preferences: TranslationPreferences = Injekt.get(),
    private val invalidConfigMessage: () -> String = {
        Injekt.get<Application>().stringResource(TDMR.strings.ai_guidelines_invalid_config)
    },
    private val noContentMessage: () -> String = {
        Injekt.get<Application>().stringResource(TDMR.strings.chapter_summary_no_content)
    },
) {
    /** Whether a summary can be requested at all; false means the user has AI left unconfigured. */
    fun isConfigured(target: NovelTarget? = null): Boolean = resolveConfig(target).isComplete

    /**
     * Summarizes [chapterHtml] in one request.
     *
     * Deliberately sends the whole chapter: chunking a summary means summarizing summaries, which
     * loses exactly the through-line the reader wants. A provider whose context is too small says so,
     * and that message is shown as-is.
     */
    suspend fun summarize(
        chapterHtml: String,
        target: NovelTarget? = null,
        snapshot: AiExecutionConfig? = null,
    ): LlmResult {
        val text = TranslationHtmlUtils.extractTextFromHtml(chapterHtml).trim()
        if (text.isEmpty()) {
            return LlmResult.Failure(noContentMessage(), AiErrorCode.REQUEST_INVALID)
        }
        val config = try {
            snapshot ?: resolveConfig(target)
        } catch (_: IllegalArgumentException) {
            return LlmResult.Failure(invalidConfigMessage(), AiErrorCode.REQUEST_INVALID)
        }
        val request = LlmGenerationRequest(
            systemPrompt = ChapterSummaryPromptBuilder.build(
                targetLanguage = LanguageCodes.getDisplayName(preferences.targetLanguage().get()),
                guidelines = config.guidelines.orEmpty(),
            ),
            input = text,
        )
        return AiRetryPolicy.execute(
            retries = preferences.requestRetryCount().get(),
            failureCode = { (it as? LlmResult.Failure)?.code },
        ) { generator.generate(config, request) }
    }

    fun resolveConfig(target: NovelTarget? = null): AiExecutionConfig = aiSettings.resolveConfig(
        preferences.chapterSummaryProviderId().get(),
        preferences.chapterSummaryGuidelinesId().get(),
        target,
        NovelAiTask.CHAPTER_SUMMARY,
    )
}

object ChapterSummaryPromptBuilder {
    fun build(targetLanguage: String, guidelines: String): String {
        val custom = guidelines.trim().ifEmpty { "No specific guidelines." }
        return """
            You summarize a single chapter of a novel for a reader who has just read it.

            Rules:
            - Write in $targetLanguage.
            - Write 3 to 5 paragraphs of plain prose, separated by a blank line.
            - Cover the main events, decisions and revelations, in the order they happen.
            - Use only what the chapter says. Never invent, speculate, or draw on other chapters.
            - Output the summary only: no title, no preamble, no markdown, no bullet points.

            ---
            [Reader guidelines]:
            $custom
        """.trimIndent()
    }
}
