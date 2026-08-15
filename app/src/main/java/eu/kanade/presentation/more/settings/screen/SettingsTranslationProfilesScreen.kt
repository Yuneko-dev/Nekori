package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.LocalBackPress
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.translation.AiSettingsStore
import eu.kanade.tachiyomi.data.translation.TranslationEngineManager
import eu.kanade.tachiyomi.data.translation.TranslationProfileStore
import tachiyomi.domain.translation.model.AIProvider
import tachiyomi.domain.translation.model.SystemPrompt
import tachiyomi.domain.translation.model.TranslationEngine
import tachiyomi.domain.translation.model.TranslationEngineId
import tachiyomi.domain.translation.model.TranslationProfile
import tachiyomi.domain.translation.service.TranslationPreferences
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.UUID

object SettingsTranslationProfilesScreen : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val back = LocalBackPress.currentOrThrow
        val store = remember { Injekt.get<TranslationProfileStore>() }
        val engines = remember { Injekt.get<TranslationEngineManager>() }
        val aiSettings = remember { Injekt.get<AiSettingsStore>() }
        val preferences = remember { Injekt.get<TranslationPreferences>() }
        val profilesJson by preferences.translationProfilesJson().collectAsState()
        val providersJson by preferences.aiProvidersJson().collectAsState()
        val promptsJson by preferences.systemPromptsJson().collectAsState()
        val activeProviderId by preferences.activeAiProviderId().collectAsState()
        val activePromptId by preferences.activeSystemPromptId().collectAsState()
        val profiles = remember(profilesJson) { store.profiles() }
        val providers = remember(providersJson) { aiSettings.providers() }
        val prompts = remember(promptsJson) { aiSettings.prompts() }
        val defaultName = stringResource(TDMR.strings.pref_translation_profile_default)

        Scaffold(topBar = {
            AppBar(stringResource(TDMR.strings.pref_translation_profiles), navigateUp = back::invoke)
        }) { padding ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                items(profiles, key = { it.id }) { profile ->
                    ManagerRow(
                        title = profile.name.ifBlank { defaultName },
                        subtitle = describeProfile(
                            profile,
                            engines.engines,
                            providers,
                            prompts,
                            activeProviderId,
                            activePromptId,
                        ),
                        icon = { Icon(Icons.Outlined.Translate, null) },
                        onClick = { navigator.push(TranslationProfileEditorScreen(profile.id)) },
                        onDelete = if (profile.deletable) {
                            { store.delete(profile.id) }
                        } else {
                            null
                        },
                    )
                }
                item {
                    Button(
                        onClick = { navigator.push(TranslationProfileEditorScreen()) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                    ) {
                        Icon(Icons.Outlined.Add, null)
                        Text(
                            stringResource(TDMR.strings.pref_translation_profile_add),
                            Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

private data class TranslationProfileEditorScreen(private val profileId: String? = null) : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val back = LocalBackPress.currentOrThrow
        val store = remember { Injekt.get<TranslationProfileStore>() }
        val engines = remember { Injekt.get<TranslationEngineManager>() }
        val aiSettings = remember { Injekt.get<AiSettingsStore>() }

        val original = remember(profileId) { profileId?.let(store::profile) }
        val isDefault = original?.id == TranslationProfile.DEFAULT_ID
        val id = original?.id ?: remember { UUID.randomUUID().toString() }
        val availableEngines = engines.engines
        val providers = remember { listOf<AIProvider?>(null) + aiSettings.providers() }
        val prompts = remember { listOf<SystemPrompt?>(null) + aiSettings.prompts() }

        var name by remember { mutableStateOf(original?.name.orEmpty()) }
        var engine by remember {
            mutableStateOf(
                original?.let { profile -> availableEngines.first { it.id == profile.engineId } }
                    ?: engines.getSelectedEngine(),
            )
        }
        var provider by remember {
            mutableStateOf(original?.aiProviderId?.let { id -> providers.firstOrNull { it?.id == id } })
        }
        var prompt by remember {
            mutableStateOf(original?.systemPromptId?.let { id -> prompts.firstOrNull { it?.id == id } })
        }

        // "Use the globally active one" is a real choice, so both pickers carry a null entry.
        val useActive = stringResource(TDMR.strings.pref_translation_profile_use_active)
        val defaultName = stringResource(TDMR.strings.pref_translation_profile_default)

        fun save() {
            store.save(
                TranslationProfile(
                    id = id,
                    // The default profile has no editable name; it is labelled by the UI.
                    name = if (isDefault) "" else name.trim(),
                    engineId = engine.id,
                    aiProviderId = provider?.id,
                    systemPromptId = prompt?.id,
                ),
            )
            navigator.pop()
        }

        Scaffold(topBar = {
            AppBar(
                title = if (isDefault) {
                    defaultName
                } else {
                    stringResource(
                        if (original == null) {
                            TDMR.strings.pref_translation_profile_new
                        } else {
                            TDMR.strings.pref_translation_profiles
                        },
                    )
                },
                navigateUp = back::invoke,
                actions = {
                    TextButton(onClick = ::save, enabled = isDefault || name.isNotBlank()) {
                        Text(stringResource(MR.strings.action_save))
                    }
                },
            )
        }) { padding ->
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    end = 16.dp,
                    bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!isDefault) {
                    item {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text(stringResource(TDMR.strings.pref_translation_profile_name)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                item {
                    SettingsDropdownField(
                        label = stringResource(TDMR.strings.pref_translation_engine),
                        value = engine,
                        values = availableEngines,
                        valueLabel = { it.name },
                        onSelected = { engine = it },
                    )
                }
                // Provider and prompt are meaningless on the other engines; hidden rather than
                // disabled so they do not read as a misconfiguration.
                if (engine.id == TranslationEngineId.LLM) {
                    item {
                        SettingsDropdownField(
                            label = stringResource(TDMR.strings.pref_ai_active_provider),
                            value = provider,
                            values = providers,
                            valueLabel = { it?.alias ?: useActive },
                            onSelected = { provider = it },
                        )
                    }
                    item {
                        SettingsDropdownField(
                            label = stringResource(TDMR.strings.pref_ai_active_prompt),
                            value = prompt,
                            values = prompts,
                            valueLabel = { it?.name ?: useActive },
                            onSelected = { prompt = it },
                        )
                    }
                }
            }
        }
    }
}

private fun describeProfile(
    profile: TranslationProfile,
    engines: List<TranslationEngine>,
    providers: List<AIProvider>,
    prompts: List<SystemPrompt>,
    activeProviderId: String,
    activePromptId: String,
): String {
    val engine = engines.first { it.id == profile.engineId }
    if (engine.id != TranslationEngineId.LLM) return engine.name
    val providerId = profile.aiProviderId?.takeIf { it.isNotBlank() } ?: activeProviderId
    val promptId = profile.systemPromptId?.takeIf { it.isNotBlank() } ?: activePromptId
    val prompt = prompts.firstOrNull { it.id == promptId } ?: SystemPrompt.DEFAULT
    return listOfNotNull(
        engine.name,
        providers.firstOrNull { it.id == providerId }?.alias,
        prompt.name.takeIf { it.isNotBlank() && prompt.id != SystemPrompt.DEFAULT_ID },
    ).joinToString(" · ")
}
