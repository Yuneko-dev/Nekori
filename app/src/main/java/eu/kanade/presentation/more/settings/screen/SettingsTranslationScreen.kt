package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.data.translation.TranslationChunkMode
import eu.kanade.tachiyomi.data.translation.TranslationEngineManager
import eu.kanade.tachiyomi.data.translation.TranslationService
import eu.kanade.tachiyomi.ui.download.DownloadQueueScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tachiyomi.domain.translation.model.TranslationEngine
import tachiyomi.domain.translation.model.TranslationEngineId
import tachiyomi.domain.translation.model.TranslationPurpose
import tachiyomi.domain.translation.model.TranslationRequest
import tachiyomi.domain.translation.model.TranslationResult
import tachiyomi.domain.translation.model.contextualAnchoringParagraphs
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
                    title = stringResource(MR.strings.pref_category_general),
                    preferenceItems = listOf(masterPreference(prefs)),
                ),
            )
        }

        val chapterEngine by prefs.engineId(TranslationPurpose.CHAPTER).collectAsState()
        val chapterEngineId = TranslationEngineId.fromKey(chapterEngine)
        val sourceLanguage by prefs.sourceLanguage().collectAsState()
        val targetLanguage by prefs.targetLanguage().collectAsState()
        val libreApiKey by prefs.libreTranslateApiKey().collectAsState()
        val deepLApiKey by prefs.deepLApiKey().collectAsState()
        val googleApiKey by prefs.googleApiKey().collectAsState()
        val progress by translationService.progressState.collectAsState()
        val isPaused by translationService.isPaused.collectAsState()
        val scope = rememberCoroutineScope()
        val testResults = remember { mutableStateMapOf<TranslationEngineId, String>() }
        var testingEngineId by remember { mutableStateOf<TranslationEngineId?>(null) }
        val notSet = stringResource(TDMR.strings.not_set)
        val testEngine = stringResource(TDMR.strings.pref_translation_test_engine)
        val testing = stringResource(TDMR.strings.pref_translation_testing)
        val testSend = stringResource(TDMR.strings.pref_translation_test_send)
        val testText = stringResource(TDMR.strings.pref_translation_test_text)
        fun apiKeySubtitle(value: String) = if (value.isBlank()) notSet else "••••••••"
        fun testButton(engine: TranslationEngine): Preference.PreferenceItem.TextPreference {
            val configured = engine.isConfigured()
            return Preference.PreferenceItem.TextPreference(
                title = testEngine.format(engine.name),
                subtitle = when {
                    testingEngineId == engine.id -> testing
                    testResults[engine.id] != null -> testResults.getValue(engine.id)
                    !configured -> notSet
                    else -> testSend
                },
                onClick = {
                    if (testingEngineId == null && configured) {
                        testingEngineId = engine.id
                        testResults.remove(engine.id)
                        scope.launch {
                            try {
                                testResults[engine.id] = when (
                                    val result = engine.translate(
                                        TranslationRequest(listOf(testText), sourceLanguage, targetLanguage),
                                    )
                                ) {
                                    is TranslationResult.Success -> "✓ ${result.translatedTexts.joinToString(" | ")}"
                                    is TranslationResult.Error -> "✗ ${result.message}"
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                testResults[engine.id] = "✗ ${e.message ?: e.javaClass.simpleName}"
                            } finally {
                                testingEngineId = null
                            }
                        }
                    }
                },
            )
        }
        val queueStatus = when {
            progress.isCancelling -> stringResource(TDMR.strings.pref_translation_status_cancelling)
            progress.isRunning && isPaused -> stringResource(
                TDMR.strings.pref_translation_status_paused,
                progress.completedChapters,
                progress.totalChapters,
            )
            progress.isRunning -> stringResource(
                TDMR.strings.pref_translation_status_translating,
                progress.currentChapterName ?: "...",
                "",
                progress.completedChapters,
                progress.totalChapters,
            )
            else -> stringResource(TDMR.strings.pref_translation_status_idle)
        }
        // The language list follows the chapter engine: it is the one the reader uses.
        val languageEntries = engines.getSupportedLanguages(TranslationPurpose.CHAPTER).toMap()

        return listOf(
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_category_general),
                preferenceItems = listOf(masterPreference(prefs)),
            ),
            engineGroup(
                prefs = prefs,
                engines = engines,
                openAiSettings = { navigator.push(SettingsAiScreen) },
            ),
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
                        title = stringResource(TDMR.strings.pref_translation_api_key),
                        subtitle = apiKeySubtitle(libreApiKey),
                        isPassword = true,
                    ),
                    testButton(engines.engines.first { it.id == TranslationEngineId.LIBRE }),
                ),
            ),
            Preference.PreferenceGroup(
                title = "DeepL",
                preferenceItems = listOf(
                    Preference.PreferenceItem.EditTextPreference(
                        preference = prefs.deepLApiKey(),
                        title = stringResource(TDMR.strings.pref_translation_api_key),
                        subtitle = apiKeySubtitle(deepLApiKey),
                        isPassword = true,
                    ),
                    testButton(engines.engines.first { it.id == TranslationEngineId.DEEPL }),
                ),
            ),
            Preference.PreferenceGroup(
                title = "Google Cloud Translation",
                preferenceItems = listOf(
                    Preference.PreferenceItem.EditTextPreference(
                        preference = prefs.googleApiKey(),
                        title = stringResource(TDMR.strings.pref_translation_api_key),
                        subtitle = apiKeySubtitle(googleApiKey),
                        isPassword = true,
                    ),
                    testButton(engines.engines.first { it.id == TranslationEngineId.GOOGLE_CLOUD }),
                ),
            ),
            behaviorGroup(prefs, isLlmChapterEngine = chapterEngineId == TranslationEngineId.LLM),
            Preference.PreferenceGroup(
                title = stringResource(TDMR.strings.pref_translation_queue),
                preferenceItems = listOf(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(TDMR.strings.pref_translation_queue),
                        subtitle = queueStatus,
                        onClick = { navigator.push(DownloadQueueScreen(initialTab = 1)) },
                    ),
                ),
            ),
            rateLimitGroup(prefs, chapterEngineId),
        )
    }

    /** One engine picker per [TranslationPurpose]. Adding a purpose adds a row here and nothing else. */
    @Composable
    private fun engineGroup(
        prefs: TranslationPreferences,
        engines: TranslationEngineManager,
        openAiSettings: () -> Unit,
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
                    },
                )
            } + Preference.PreferenceItem.CustomPreference(
                title = stringResource(TDMR.strings.pref_category_ai),
                content = {
                    OutlinedButton(
                        onClick = openAiSettings,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Icon(Icons.Outlined.Settings, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(TDMR.strings.pref_category_ai))
                    }
                },
            ),
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
                        TranslationChunkMode.WORDS.key to stringResource(TDMR.strings.pref_translation_chunk_words),
                        TranslationChunkMode.PARAGRAPHS.key to
                            stringResource(TDMR.strings.pref_translation_chunk_paragraphs),
                    ),
                    title = stringResource(TDMR.strings.pref_translation_chunk_mode),
                ),
                when (chunkMode) {
                    TranslationChunkMode.WORDS -> Preference.PreferenceItem.SliderPreference(
                        value = chunkWordLimit,
                        title = stringResource(TDMR.strings.pref_translation_chunk_size),
                        subtitle = stringResource(TDMR.strings.pref_translation_chunk_words_rec),
                        valueString = "$chunkWordLimit ${stringResource(TDMR.strings.pref_translation_chunk_words)}",
                        valueRange = wordLimitRange,
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
                    title = stringResource(TDMR.strings.pref_translation_contextual_anchoring),
                    subtitle = stringResource(TDMR.strings.pref_translation_contextual_anchoring_desc),
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = anchoringParagraphs,
                    title = stringResource(TDMR.strings.pref_translation_contextual_anchoring_paragraphs),
                    subtitle = stringResource(TDMR.strings.pref_translation_contextual_anchoring_paragraphs_desc),
                    valueString = "$anchoringParagraphs",
                    valueRange = 1..10,
                    onValueChanged = prefs.contextualAnchoringParagraphs()::set,
                    preference = prefs.contextualAnchoringParagraphs(),
                    enabled = anchoringEnabled,
                ),
            )
        }
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_behavior),
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
                    preference = prefs.autoTranslateNextChapter(),
                    title = stringResource(TDMR.strings.pref_translation_auto_next_chapter),
                    subtitle = stringResource(TDMR.strings.pref_translation_auto_next_chapter_summary),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.replaceTitle(),
                    title = stringResource(TDMR.strings.pref_translation_replace_title),
                    subtitle = stringResource(TDMR.strings.pref_translation_replace_title_desc),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.saveTranslatedTitleAsAlternative(),
                    title = stringResource(TDMR.strings.pref_translation_save_alt_titles),
                    subtitle = stringResource(TDMR.strings.pref_translation_save_alt_titles_desc),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.translateTags(),
                    title = stringResource(TDMR.strings.pref_translation_translate_tags),
                    subtitle = stringResource(TDMR.strings.pref_translation_translate_tags_desc),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.smartAutoTranslate(),
                    title = stringResource(TDMR.strings.pref_translation_smart_auto),
                    subtitle = stringResource(TDMR.strings.pref_translation_smart_auto_desc),
                ),
            ) + chunkPreferences + anchoringPreferences,
        )
    }

    @Composable
    private fun splitSwitch(prefs: TranslationPreferences) = Preference.PreferenceItem.SwitchPreference(
        preference = prefs.splitLargeChapters(),
        title = stringResource(TDMR.strings.pref_translation_split_large_chapters),
        subtitle = stringResource(TDMR.strings.pref_translation_split_large_chapters_summary),
    )

    @Composable
    private fun paragraphChunkPreference(
        prefs: TranslationPreferences,
        chunkSize: Int,
    ) = Preference.PreferenceItem.SliderPreference(
        value = chunkSize,
        title = stringResource(TDMR.strings.pref_translation_chunk_size),
        subtitle = stringResource(TDMR.strings.pref_translation_chunk_paragraphs_rec),
        valueString = "$chunkSize ${stringResource(TDMR.strings.pref_translation_chunk_paragraphs)}",
        valueRange = 1..500,
        onValueChanged = prefs.translationChunkSize()::set,
        preference = prefs.translationChunkSize(),
    )

    @Composable
    private fun rateLimitGroup(
        prefs: TranslationPreferences,
        chapterEngineId: TranslationEngineId,
    ): Preference.PreferenceGroup {
        val delay by prefs.rateLimitDelayMs().collectAsState()
        val timeout by prefs.translationTimeoutMs().collectAsState()
        val maxParallel by prefs.maxParallelTranslations().collectAsState()
        val anchoringEnabled by prefs.contextualAnchoringEnabled().collectAsState()
        val anchoringParagraphs by prefs.contextualAnchoringParagraphs().collectAsState()
        // Anchoring pins the chapter to one chunk at a time, so the slider below would be a control
        // wired to nothing. Read through the shared rule rather than re-deriving it here.
        val pinnedByAnchoring =
            contextualAnchoringParagraphs(chapterEngineId, anchoringEnabled, anchoringParagraphs) > 0
        val delayRange = 0..10_000 step 100
        val timeoutRange = 30..300 step 5
        val delayString = if (delay > 0) "${delay}ms" else stringResource(TDMR.strings.pref_translation_no_delay)
        return Preference.PreferenceGroup(
            title = stringResource(TDMR.strings.pref_translation_rate_limit),
            preferenceItems = listOf(
                Preference.PreferenceItem.SliderPreference(
                    value = delay,
                    title = stringResource(TDMR.strings.pref_translation_rate_limit_delay),
                    subtitle = stringResource(TDMR.strings.pref_translation_rate_limit_delay_summary),
                    valueString = delayString,
                    valueRange = delayRange,
                    onValueChanged = prefs.rateLimitDelayMs()::set,
                    preference = prefs.rateLimitDelayMs(),
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = (timeout / 1_000).toInt(),
                    title = stringResource(TDMR.strings.pref_translation_timeout),
                    subtitle = stringResource(TDMR.strings.pref_translation_timeout_summary),
                    valueString = "${timeout / 1_000}s",
                    valueRange = timeoutRange,
                    onValueChanged = { prefs.translationTimeoutMs().set(it * 1_000L) },
                    preference = prefs.translationTimeoutMs(),
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = maxParallel,
                    title = stringResource(TDMR.strings.pref_translation_max_parallel),
                    subtitle = stringResource(TDMR.strings.pref_translation_max_parallel_summary),
                    valueString = "$maxParallel",
                    valueRange = 1..TranslationService.MAX_PARALLEL_TRANSLATIONS,
                    onValueChanged = prefs.maxParallelTranslations()::set,
                    preference = prefs.maxParallelTranslations(),
                    // `enabled` hides the row here (StatusWrapper wraps it in AnimatedVisibility),
                    // which is what an anchored chapter needs: the slider would set a value the
                    // dispatcher overrides to 1.
                    enabled = !pinnedByAnchoring,
                ),
            ),
        )
    }
}
