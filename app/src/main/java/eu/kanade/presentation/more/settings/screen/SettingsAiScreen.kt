package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.data.translation.AiSettingsStore
import tachiyomi.domain.translation.service.TranslationPreferences
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * The hub for everything AI, shared by every task that calls a provider.
 *
 * Task-specific settings stay out: a translation profile belongs to the translation screen, and each
 * AI task owns its own assignment row. What lives here is what all of them draw on.
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
        val activeProviderId by prefs.activeAiProviderId().collectAsState()
        val activeGuidelinesId by prefs.activeGuidelinesId().collectAsState()
        val providers = remember(providersJson) { store.providers() }
        val guidelines = remember(guidelinesJson) { store.guidelines() }
        val noProviders = stringResource(TDMR.strings.pref_ai_no_providers)

        return listOf(
            Preference.PreferenceGroup(
                title = stringResource(TDMR.strings.pref_ai_defaults),
                preferenceItems = listOf(
                    if (providers.isEmpty()) {
                        Preference.PreferenceItem.TextPreference(
                            title = stringResource(TDMR.strings.pref_ai_active_provider),
                            subtitle = stringResource(TDMR.strings.pref_ai_no_active_provider),
                            onClick = { navigator.push(AiProviderEditorScreen()) },
                        )
                    } else {
                        Preference.PreferenceItem.BasicListPreference(
                            value = activeProviderId,
                            entries = providers.associate { it.id to it.alias },
                            title = stringResource(TDMR.strings.pref_ai_active_provider),
                            onValueChanged = store::setActiveProvider,
                        )
                    },
                    Preference.PreferenceItem.BasicListPreference(
                        value = activeGuidelinesId,
                        entries = guidelines.associate { it.id to it.name },
                        title = stringResource(TDMR.strings.pref_ai_active_guidelines),
                        onValueChanged = store::setActiveGuidelines,
                    ),
                    // Only the LLM translation path reads this, but it describes how the app talks to a
                    // provider, so it belongs with the provider settings rather than with translation.
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.structuredOutput(),
                        title = stringResource(TDMR.strings.pref_translation_structured_output),
                        subtitle = stringResource(TDMR.strings.pref_translation_structured_output_summary),
                    ),
                ),
            ),
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
                ),
            ),
        )
    }
}
