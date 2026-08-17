package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.data.translation.TranslationChunkMode
import eu.kanade.tachiyomi.data.translation.TranslationEngineManager
import eu.kanade.tachiyomi.data.translation.TranslationService
import eu.kanade.tachiyomi.ui.download.DownloadQueueScreen
import tachiyomi.domain.translation.model.TranslationEngineId
import tachiyomi.domain.translation.model.TranslationPurpose
import tachiyomi.domain.translation.service.TranslationPreferences
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object SettingsTranslationScreen : SearchableSettings {
    override val supportsReset = true

    @Composable
    override fun getAdditionalResetPreferences(): List<tachiyomi.core.common.preference.Preference<*>> {
        val prefs = remember { Injekt.get<TranslationPreferences>() }
        return TranslationPurpose.entries.map(prefs::engineId) + listOf(
            prefs.rateLimitDelayMs(),
            prefs.translationTimeoutMs(),
            prefs.maxParallelTranslations(),
        )
    }

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = TDMR.strings.pref_category_translation

    @Composable
    override fun getPreferences(): List<Preference> {
        val prefs = remember { Injekt.get<TranslationPreferences>() }
        val engines = remember { Injekt.get<TranslationEngineManager>() }
        val translationService = remember { Injekt.get<TranslationService>() }
        val navigator = LocalNavigator.currentOrThrow
        val enabled by prefs.translationEnabled().collectAsState()
        if (!enabled) {
            return listOf(
                Preference.PreferenceGroup(
                    title = stringResource(TDMR.strings.pref_translation_general),
                    preferenceItems = listOf(masterPreference(prefs)),
                ),
            )
        }

        val chapterEngine by prefs.engineId(TranslationPurpose.CHAPTER).collectAsState()
        val sourceLanguage by prefs.sourceLanguage().collectAsState()
        val targetLanguage by prefs.targetLanguage().collectAsState()
        val progress by translationService.progressState.collectAsState()
        val isPaused by translationService.isPaused.collectAsState()
        val queueStatus = when {
            progress.isCancelling -> stringResource(MR.strings.pref_translation_status_cancelling)
            progress.isRunning && isPaused -> stringResource(
                MR.strings.pref_translation_status_paused,
                progress.completedChapters,
                progress.totalChapters,
            )
            progress.isRunning -> stringResource(
                MR.strings.pref_translation_status_translating,
                progress.currentChapterName ?: "...",
                "",
                progress.completedChapters,
                progress.totalChapters,
            )
            else -> stringResource(MR.strings.pref_translation_status_idle)
        }
        // The language list follows the chapter engine: it is the one the reader uses.
        val languageEntries = engines.getSupportedLanguages(TranslationPurpose.CHAPTER).toMap()

        return listOf(
            Preference.PreferenceGroup(
                title = stringResource(TDMR.strings.pref_translation_general),
                preferenceItems = listOf(masterPreference(prefs)),
            ),
            engineGroup(prefs, engines),
            Preference.PreferenceGroup(
                title = stringResource(TDMR.strings.pref_translation_languages),
                preferenceItems = listOf(
                    Preference.PreferenceItem.BasicListPreference(
                        value = sourceLanguage,
                        entries = languageEntries,
                        title = stringResource(TDMR.strings.pref_translation_source_language),
                        onValueChanged = prefs.sourceLanguage()::set,
                    ),
                    Preference.PreferenceItem.BasicListPreference(
                        value = targetLanguage,
                        entries = languageEntries.filterKeys { it != "auto" },
                        title = stringResource(TDMR.strings.pref_translation_target_language),
                        onValueChanged = prefs.targetLanguage()::set,
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = "LibreTranslate",
                preferenceItems = listOf(
                    Preference.PreferenceItem.EditTextPreference(
                        preference = prefs.libreTranslateUrl(),
                        title = stringResource(TDMR.strings.pref_translation_libretranslate_url),
                    ),
                    Preference.PreferenceItem.EditTextPreference(
                        preference = prefs.libreTranslateApiKey(),
                        title = stringResource(MR.strings.pref_translation_api_key),
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = "DeepL",
                preferenceItems = listOf(
                    Preference.PreferenceItem.EditTextPreference(
                        preference = prefs.deepLApiKey(),
                        title = stringResource(MR.strings.pref_translation_api_key),
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = "Google Cloud Translation",
                preferenceItems = listOf(
                    Preference.PreferenceItem.EditTextPreference(
                        preference = prefs.googleApiKey(),
                        title = stringResource(MR.strings.pref_translation_api_key),
                    ),
                ),
            ),
            behaviorGroup(prefs, isLlmChapterEngine = chapterEngine == TranslationEngineId.LLM.key),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_translation_queue),
                preferenceItems = listOf(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.pref_translation_queue),
                        subtitle = queueStatus,
                        onClick = { navigator.push(DownloadQueueScreen(initialTab = 1)) },
                    ),
                ),
            ),
            rateLimitGroup(prefs),
        )
    }

    /** One engine picker per [TranslationPurpose]. Adding a purpose adds a row here and nothing else. */
    @Composable
    private fun engineGroup(
        prefs: TranslationPreferences,
        engines: TranslationEngineManager,
    ): Preference.PreferenceGroup {
        val entries = engines.engines.associate { it.id.key to it.name }
        val labels = mapOf(
            TranslationPurpose.CHAPTER to stringResource(TDMR.strings.pref_translation_purpose_chapter),
            TranslationPurpose.METADATA to stringResource(TDMR.strings.pref_translation_purpose_metadata),
            TranslationPurpose.BROWSE_TITLE to stringResource(TDMR.strings.pref_translation_purpose_browse_title),
        )
        return Preference.PreferenceGroup(
            title = stringResource(TDMR.strings.pref_translation_engine),
            preferenceItems = TranslationPurpose.entries.map { purpose ->
                val preference = prefs.engineId(purpose)
                val current by preference.collectAsState()
                Preference.PreferenceItem.BasicListPreference(
                    value = current,
                    entries = entries,
                    title = labels.getValue(purpose),
                    subtitle = entries[current].orEmpty(),
                    onValueChanged = {
                        preference.set(it)
                        true
                    },
                )
            },
        )
    }

    @Composable
    private fun masterPreference(prefs: TranslationPreferences) = Preference.PreferenceItem.SwitchPreference(
        preference = prefs.translationEnabled(),
        title = stringResource(TDMR.strings.pref_translation_enabled),
        subtitle = stringResource(TDMR.strings.pref_translation_enabled_summary),
    )

    @Composable
    private fun behaviorGroup(
        prefs: TranslationPreferences,
        isLlmChapterEngine: Boolean,
    ): Preference.PreferenceGroup {
        val splitLargeChapters by prefs.splitLargeChapters().collectAsState()
        val chunkModeKey by prefs.translationChunkMode().collectAsState()
        val chunkMode = TranslationChunkMode.fromKey(chunkModeKey)
        val chunkSize by prefs.translationChunkSize().collectAsState()
        val chunkWordLimit by prefs.translationChunkWordLimit().collectAsState()
        val anchoringEnabled by prefs.contextualAnchoringEnabled().collectAsState()
        val anchoringParagraphs by prefs.contextualAnchoringParagraphs().collectAsState()
        val wordLimitRange = 300..10_000 step 100
        val chunkPreferences: List<Preference.PreferenceItem<out Any, out Any>> = when {
            !isLlmChapterEngine -> listOf(paragraphChunkPreference(prefs, chunkSize))
            !splitLargeChapters -> listOf(splitSwitch(prefs))
            else -> listOf(
                splitSwitch(prefs),
                Preference.PreferenceItem.ListPreference(
                    preference = prefs.translationChunkMode(),
                    entries = mapOf(
                        TranslationChunkMode.WORDS.key to stringResource(MR.strings.pref_translation_chunk_words),
                        TranslationChunkMode.PARAGRAPHS.key to
                            stringResource(MR.strings.pref_translation_chunk_paragraphs),
                    ),
                    title = stringResource(MR.strings.pref_translation_chunk_mode),
                ),
                when (chunkMode) {
                    TranslationChunkMode.WORDS -> Preference.PreferenceItem.SliderPreference(
                        value = chunkWordLimit,
                        title = stringResource(MR.strings.pref_translation_chunk_size),
                        subtitle = stringResource(MR.strings.pref_translation_chunk_words_rec),
                        valueString = "$chunkWordLimit ${stringResource(MR.strings.pref_translation_chunk_words)}",
                        valueRange = wordLimitRange,
                        steps = wordLimitRange.count() - 2,
                        onValueChanged = prefs.translationChunkWordLimit()::set,
                        preference = prefs.translationChunkWordLimit(),
                    )
                    TranslationChunkMode.PARAGRAPHS -> paragraphChunkPreference(prefs, chunkSize)
                },
            )
        }
        // Anchoring feeds the previous chunk back to an LLM; the other engines never read it.
        val anchoringPreferences: List<Preference.PreferenceItem<out Any, out Any>> = if (!isLlmChapterEngine) {
            emptyList()
        } else {
            listOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.contextualAnchoringEnabled(),
                    title = stringResource(MR.strings.pref_translation_contextual_anchoring),
                    subtitle = stringResource(MR.strings.pref_translation_contextual_anchoring_desc),
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = anchoringParagraphs,
                    title = stringResource(MR.strings.pref_translation_contextual_anchoring_paragraphs),
                    subtitle = stringResource(MR.strings.pref_translation_contextual_anchoring_paragraphs_desc),
                    valueString = "$anchoringParagraphs",
                    valueRange = 1..10,
                    onValueChanged = prefs.contextualAnchoringParagraphs()::set,
                    preference = prefs.contextualAnchoringParagraphs(),
                    enabled = anchoringEnabled,
                ),
            )
        }
        return Preference.PreferenceGroup(
            title = stringResource(TDMR.strings.pref_translation_behavior),
            preferenceItems = listOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.autoDownloadBeforeTranslate(),
                    title = stringResource(TDMR.strings.pref_translation_auto_download),
                    subtitle = stringResource(TDMR.strings.pref_translation_auto_download_summary),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.autoTranslateDownloads(),
                    title = stringResource(TDMR.strings.pref_translation_auto_translate),
                    subtitle = stringResource(TDMR.strings.pref_translation_auto_translate_summary),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.replaceTitle(),
                    title = stringResource(MR.strings.pref_translation_replace_title),
                    subtitle = stringResource(MR.strings.pref_translation_replace_title_desc),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.saveTranslatedTitleAsAlternative(),
                    title = stringResource(MR.strings.pref_translation_save_alt_titles),
                    subtitle = stringResource(MR.strings.pref_translation_save_alt_titles_desc),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.translateTags(),
                    title = stringResource(MR.strings.pref_translation_translate_tags),
                    subtitle = stringResource(MR.strings.pref_translation_translate_tags_desc),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.smartAutoTranslate(),
                    title = stringResource(MR.strings.pref_translation_smart_auto),
                    subtitle = stringResource(MR.strings.pref_translation_smart_auto_desc),
                ),
            ) + chunkPreferences + anchoringPreferences,
        )
    }

    @Composable
    private fun splitSwitch(prefs: TranslationPreferences) = Preference.PreferenceItem.SwitchPreference(
        preference = prefs.splitLargeChapters(),
        title = stringResource(MR.strings.pref_translation_split_large_chapters),
        subtitle = stringResource(MR.strings.pref_translation_split_large_chapters_summary),
    )

    @Composable
    private fun paragraphChunkPreference(
        prefs: TranslationPreferences,
        chunkSize: Int,
    ) = Preference.PreferenceItem.SliderPreference(
        value = chunkSize,
        title = stringResource(MR.strings.pref_translation_chunk_size),
        subtitle = stringResource(MR.strings.pref_translation_chunk_paragraphs_rec),
        valueString = "$chunkSize ${stringResource(MR.strings.pref_translation_chunk_paragraphs)}",
        valueRange = 1..500,
        onValueChanged = prefs.translationChunkSize()::set,
        preference = prefs.translationChunkSize(),
    )

    @Composable
    private fun rateLimitGroup(prefs: TranslationPreferences): Preference.PreferenceGroup {
        val delay by prefs.rateLimitDelayMs().collectAsState()
        val timeout by prefs.translationTimeoutMs().collectAsState()
        val maxParallel by prefs.maxParallelTranslations().collectAsState()
        return Preference.PreferenceGroup(
            title = stringResource(TDMR.strings.pref_translation_rate_limit),
            preferenceItems = listOf(
                Preference.PreferenceItem.SliderPreference(
                    value = delay,
                    title = stringResource(TDMR.strings.pref_translation_rate_limit_delay),
                    subtitle = stringResource(TDMR.strings.pref_translation_rate_limit_delay_summary),
                    valueString = "${delay}ms",
                    valueRange = 500..10_000,
                    onValueChanged = prefs.rateLimitDelayMs()::set,
                    preference = prefs.rateLimitDelayMs(),
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = (timeout / 1_000).toInt(),
                    title = stringResource(TDMR.strings.pref_translation_timeout),
                    subtitle = stringResource(TDMR.strings.pref_translation_timeout_summary),
                    valueString = "${timeout / 1_000}s",
                    valueRange = 30..300,
                    onValueChanged = { prefs.translationTimeoutMs().set(it * 1_000L) },
                    preference = prefs.translationTimeoutMs(),
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = maxParallel,
                    title = stringResource(TDMR.strings.pref_translation_max_parallel),
                    subtitle = stringResource(TDMR.strings.pref_translation_max_parallel_summary),
                    valueString = "$maxParallel",
                    valueRange = 1..10,
                    onValueChanged = prefs.maxParallelTranslations()::set,
                    preference = prefs.maxParallelTranslations(),
                ),
            ),
        )
    }
}
