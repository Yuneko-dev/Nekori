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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.data.translation.AiSettingsStore
import eu.kanade.tachiyomi.data.translation.TranslationEngineManager
import eu.kanade.tachiyomi.data.translation.TranslationProfileStore
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
        return listOf(
            prefs.requestRetryCount(),
            prefs.rateLimitDelayMs(),
            prefs.translationTimeoutMs(),
            prefs.maxParallelTranslations(),
            prefs.selectedEngineId(),
        )
    }

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = TDMR.strings.pref_category_translation

    @Composable
    override fun getPreferences(): List<Preference> {
        val prefs = remember { Injekt.get<TranslationPreferences>() }
        val engines = remember { Injekt.get<TranslationEngineManager>() }
        val profileStore = remember { Injekt.get<TranslationProfileStore>() }
        val ai = remember { Injekt.get<AiSettingsStore>() }
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

        val engineId by prefs.selectedEngineId().collectAsState()
        val sourceLanguage by prefs.sourceLanguage().collectAsState()
        val targetLanguage by prefs.targetLanguage().collectAsState()
        val providersJson by prefs.aiProvidersJson().collectAsState()
        val promptsJson by prefs.systemPromptsJson().collectAsState()
        val activeProviderId by prefs.activeAiProviderId().collectAsState()
        val activePromptId by prefs.activeSystemPromptId().collectAsState()
        val providers = remember(providersJson) { ai.providers() }
        val prompts = remember(promptsJson) { ai.prompts() }
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
        // The language list follows the chapter profile's engine: it is the one the reader uses.
        val languageEntries = engines.getSupportedLanguages(TranslationPurpose.CHAPTER).toMap()
        val groups = mutableListOf<Preference>()
        groups += Preference.PreferenceGroup(
            title = stringResource(TDMR.strings.pref_translation_general),
            preferenceItems = listOf(masterPreference(prefs)),
        )
        groups += profilesGroup(prefs, profileStore, navigator)
        groups += Preference.PreferenceGroup(
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
        )
        engineConfiguration(engineId, prefs, providers, prompts, activeProviderId, activePromptId, ai) {
            navigator.push(SettingsAiScreen)
        }?.let(groups::add)
        groups += behaviorGroup(prefs)
        groups += Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_translation_queue),
            preferenceItems = listOf(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_translation_queue),
                    subtitle = queueStatus,
                    onClick = { navigator.push(DownloadQueueScreen(initialTab = 1)) },
                ),
            ),
        )
        groups += rateLimitGroup(prefs)
        return groups
    }

    /**
     * Profile management plus one assignment row per [TranslationPurpose]. Adding a purpose adds a
     * row here and nothing else.
     */
    @Composable
    private fun profilesGroup(
        prefs: TranslationPreferences,
        profileStore: TranslationProfileStore,
        navigator: Navigator,
    ): Preference.PreferenceGroup {
        val profilesJson by prefs.translationProfilesJson().collectAsState()
        val assignmentsJson by prefs.translationTaskProfilesJson().collectAsState()
        val profiles = remember(profilesJson) { profileStore.profiles() }
        val defaultName = stringResource(TDMR.strings.pref_translation_profile_default)
        val purposeLabels = mapOf(
            TranslationPurpose.CHAPTER to stringResource(TDMR.strings.pref_translation_purpose_chapter),
            TranslationPurpose.METADATA to stringResource(TDMR.strings.pref_translation_purpose_metadata),
            TranslationPurpose.BROWSE_TITLE to stringResource(TDMR.strings.pref_translation_purpose_browse_title),
        )

        return Preference.PreferenceGroup(
            title = stringResource(TDMR.strings.pref_translation_profiles),
            preferenceItems = buildList {
                add(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(TDMR.strings.pref_translation_profiles_manage),
                        subtitle = stringResource(
                            TDMR.strings.pref_translation_profiles_count,
                            profiles.size,
                        ),
                        onClick = { navigator.push(SettingsTranslationProfilesScreen) },
                    ),
                )
                TranslationPurpose.entries.forEach { purpose ->
                    val current = remember(profilesJson, assignmentsJson) { profileStore.profileFor(purpose) }
                    add(
                        Preference.PreferenceItem.CustomPreference(
                            title = purposeLabels.getValue(purpose),
                            content = {
                                SettingsDropdownField(
                                    label = purposeLabels.getValue(purpose),
                                    value = current,
                                    values = profiles,
                                    valueLabel = { it.name.ifBlank { defaultName } },
                                    onSelected = { profileStore.assign(purpose, it.id) },
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            },
                        ),
                    )
                }
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
    private fun engineConfiguration(
        engineKey: String,
        prefs: TranslationPreferences,
        providers: List<tachiyomi.domain.translation.model.AIProvider>,
        prompts: List<tachiyomi.domain.translation.model.SystemPrompt>,
        activeProviderId: String,
        activePromptId: String,
        ai: AiSettingsStore,
        openAiSettings: () -> Unit,
    ): Preference.PreferenceGroup? {
        val engine = TranslationEngineId.fromKey(engineKey)
        val items = when (engine) {
            TranslationEngineId.LLM -> listOf(
                if (providers.isEmpty()) {
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(TDMR.strings.pref_ai_active_provider),
                        subtitle = stringResource(TDMR.strings.pref_ai_no_active_provider),
                        onClick = openAiSettings,
                    )
                } else {
                    Preference.PreferenceItem.BasicListPreference(
                        value = activeProviderId,
                        entries = providers.associate { it.id to it.alias },
                        title = stringResource(TDMR.strings.pref_ai_active_provider),
                        onValueChanged = ai::setActiveProvider,
                    )
                },
                Preference.PreferenceItem.BasicListPreference(
                    value = activePromptId,
                    entries = prompts.associate { it.id to it.name },
                    title = stringResource(TDMR.strings.pref_ai_active_prompt),
                    onValueChanged = ai::setActivePrompt,
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.structuredOutput(),
                    title = stringResource(TDMR.strings.pref_translation_structured_output),
                    subtitle = stringResource(TDMR.strings.pref_translation_structured_output_summary),
                ),
                Preference.PreferenceItem.CustomPreference(
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
            TranslationEngineId.LIBRE -> listOf(
                Preference.PreferenceItem.EditTextPreference(
                    preference = prefs.libreTranslateUrl(),
                    title = stringResource(TDMR.strings.pref_translation_libretranslate_url),
                ),
                Preference.PreferenceItem.EditTextPreference(
                    preference = prefs.libreTranslateApiKey(),
                    title = stringResource(MR.strings.pref_translation_api_key),
                ),
            )
            TranslationEngineId.DEEPL -> listOf(
                Preference.PreferenceItem.EditTextPreference(
                    preference = prefs.deepLApiKey(),
                    title = stringResource(MR.strings.pref_translation_api_key),
                ),
            )
            TranslationEngineId.GOOGLE_CLOUD -> listOf(
                Preference.PreferenceItem.EditTextPreference(
                    preference = prefs.googleApiKey(),
                    title = stringResource(MR.strings.pref_translation_api_key),
                ),
            )
            TranslationEngineId.GOOGLE_FREE -> emptyList()
        }
        if (items.isEmpty()) return null
        return Preference.PreferenceGroup(
            title = stringResource(
                if (engine == TranslationEngineId.LLM) {
                    TDMR.strings.pref_translation_llm
                } else {
                    TDMR.strings.pref_translation_api_keys
                },
            ),
            preferenceItems = items,
        )
    }

    @Composable
    private fun behaviorGroup(prefs: TranslationPreferences): Preference.PreferenceGroup {
        val chunkSize by prefs.translationChunkSize().collectAsState()
        val anchoringEnabled by prefs.contextualAnchoringEnabled().collectAsState()
        val anchoringParagraphs by prefs.contextualAnchoringParagraphs().collectAsState()
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
                Preference.PreferenceItem.SliderPreference(
                    value = chunkSize,
                    title = stringResource(MR.strings.pref_translation_chunk_size),
                    subtitle = stringResource(MR.strings.pref_translation_chunk_paragraphs_rec),
                    valueString = "$chunkSize ${stringResource(MR.strings.pref_translation_chunk_paragraphs)}",
                    valueRange = 1..500,
                    onValueChanged = prefs.translationChunkSize()::set,
                    preference = prefs.translationChunkSize(),
                ),
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
            ),
        )
    }

    @Composable
    private fun rateLimitGroup(prefs: TranslationPreferences): Preference.PreferenceGroup {
        val retries by prefs.requestRetryCount().collectAsState()
        val delay by prefs.rateLimitDelayMs().collectAsState()
        val timeout by prefs.translationTimeoutMs().collectAsState()
        val maxParallel by prefs.maxParallelTranslations().collectAsState()
        return Preference.PreferenceGroup(
            title = stringResource(TDMR.strings.pref_translation_rate_limit),
            preferenceItems = listOf(
                Preference.PreferenceItem.SliderPreference(
                    value = retries,
                    title = stringResource(TDMR.strings.pref_translation_retry_count),
                    valueString = "$retries",
                    valueRange = 0..5,
                    onValueChanged = prefs.requestRetryCount()::set,
                    preference = prefs.requestRetryCount(),
                ),
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
