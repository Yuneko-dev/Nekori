package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.data.translation.AiSettingsStore
import tachiyomi.domain.translation.model.AIProvider
import tachiyomi.domain.translation.model.UserGuidelines
import tachiyomi.domain.translation.model.resolve
import tachiyomi.domain.translation.service.TranslationPreferences
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.core.common.preference.Preference as CorePreference

/**
 * The hub for everything AI: the providers and guidelines every task draws on, plus one section per
 * task saying which of them it uses.
 */
object SettingsAiScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = TDMR.strings.pref_category_ai

    @Composable
    override fun getPreferences(): List<Preference> {
        val navigator = LocalNavigator.currentOrThrow
        val store = remember { Injekt.get<AiSettingsStore>() }
        val prefs = remember { Injekt.get<TranslationPreferences>() }
        val providersJson by prefs.aiProvidersJson().collectAsState()
        val guidelinesJson by prefs.userGuidelinesJson().collectAsState()
        val providers = remember(providersJson) { store.providers() }
        val guidelines = remember(guidelinesJson) { store.guidelines() }
        val retries by prefs.requestRetryCount().collectAsState()
        val rpm by prefs.aiRpmLimit().collectAsState()
        val noProviders = stringResource(TDMR.strings.pref_ai_no_providers)

        return listOf(
            Preference.PreferenceGroup(
                title = stringResource(TDMR.strings.pref_ai_resources),
                preferenceItems = listOf(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(TDMR.strings.pref_ai_providers),
                        subtitle = providers.joinToString { it.alias }.ifBlank { noProviders },
                        onClick = { navigator.push(AiProviderManagerScreen) },
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(TDMR.strings.pref_ai_user_guidelines),
                        subtitle = stringResource(TDMR.strings.pref_ai_guidelines_summary),
                        onClick = { navigator.push(UserGuidelinesManagerScreen) },
                    ),
                    // Retries and the request ceiling apply to every AI task, so they belong with the
                    // shared resources rather than inside one task's section.
                    Preference.PreferenceItem.SliderPreference(
                        value = retries,
                        title = stringResource(TDMR.strings.pref_translation_retry_count),
                        valueString = "$retries",
                        valueRange = 0..5,
                        onValueChanged = prefs.requestRetryCount()::set,
                        preference = prefs.requestRetryCount(),
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        value = rpm,
                        title = stringResource(TDMR.strings.pref_ai_rpm_limit),
                        subtitle = stringResource(TDMR.strings.pref_ai_rpm_limit_summary),
                        valueString = if (rpm > 0) "$rpm" else stringResource(TDMR.strings.pref_ai_rpm_unlimited),
                        valueRange = 0..120,
                        onValueChanged = prefs.aiRpmLimit()::set,
                        preference = prefs.aiRpmLimit(),
                    ),
                ),
            ),
            taskGroup(
                title = stringResource(TDMR.strings.pref_ai_purpose_chapter_summary),
                providerPreference = prefs.chapterSummaryProviderId(),
                guidelinesPreference = prefs.chapterSummaryGuidelinesId(),
                providers = providers,
                guidelines = guidelines,
            ),
            taskGroup(
                title = stringResource(TDMR.strings.pref_ai_purpose_translation),
                providerPreference = prefs.translationProviderId(),
                guidelinesPreference = prefs.translationGuidelinesId(),
                providers = providers,
                guidelines = guidelines,
                // Describes how the app asks this provider for a translation, so it sits with the
                // task that asks rather than in the translation screen's engine settings.
                extraItems = listOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.structuredOutput(),
                        title = stringResource(TDMR.strings.pref_translation_structured_output),
                        subtitle = stringResource(TDMR.strings.pref_translation_structured_output_summary),
                    ),
                ),
            ),
        )
    }

    /**
     * One AI task: which provider runs it and which guidelines it carries. Adding a task is one more
     * call to this, and nothing else.
     */
    @Composable
    private fun taskGroup(
        title: String,
        providerPreference: CorePreference<String>,
        guidelinesPreference: CorePreference<String>,
        providers: List<AIProvider>,
        guidelines: List<UserGuidelines>,
        extraItems: List<Preference.PreferenceItem<out Any, out Any>> = emptyList(),
    ): Preference.PreferenceGroup {
        val providerId by providerPreference.collectAsState()
        val guidelinesId by guidelinesPreference.collectAsState()
        return Preference.PreferenceGroup(
            title = title,
            preferenceItems = listOf(
                Preference.PreferenceItem.BasicListPreference(
                    value = providerId,
                    entries = providers.associate { it.id to it.alias },
                    title = stringResource(TDMR.strings.pref_ai_provider),
                    // resolve(), not a plain lookup: a blank id runs on the first provider, so a
                    // lookup names no provider while the task is quietly using one.
                    subtitle = providers.resolve(providerId)?.alias
                        ?: stringResource(TDMR.strings.pref_ai_no_active_provider),
                    onValueChanged = {
                        providerPreference.set(it)
                        true
                    },
                ),
                Preference.PreferenceItem.BasicListPreference(
                    value = guidelinesId,
                    entries = guidelines.associate { it.id to it.name },
                    title = stringResource(TDMR.strings.pref_ai_user_guidelines),
                    subtitle = guidelines.resolve(guidelinesId).name,
                    onValueChanged = {
                        guidelinesPreference.set(it)
                        true
                    },
                ),
            ) + extraItems,
        )
    }
}
