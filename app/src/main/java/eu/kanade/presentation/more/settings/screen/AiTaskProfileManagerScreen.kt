package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Icon
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
import eu.kanade.tachiyomi.data.translation.AiTaskProfileStore
import tachiyomi.domain.translation.model.AIProvider
import tachiyomi.domain.translation.model.AiTaskProfile
import tachiyomi.domain.translation.model.DEFAULT_PROFILE_ID
import tachiyomi.domain.translation.model.UserGuidelines
import tachiyomi.domain.translation.model.resolve
import tachiyomi.domain.translation.service.TranslationPreferences
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.UUID

object AiTaskProfileManagerScreen : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val back = LocalBackPress.currentOrThrow
        val store = remember { Injekt.get<AiTaskProfileStore>() }
        val aiSettings = remember { Injekt.get<AiSettingsStore>() }
        val preferences = remember { Injekt.get<TranslationPreferences>() }
        val profilesJson by preferences.aiTaskProfilesJson().collectAsState()
        val providersJson by preferences.aiProvidersJson().collectAsState()
        val guidelinesJson by preferences.userGuidelinesJson().collectAsState()
        val activeProviderId by preferences.activeAiProviderId().collectAsState()
        val activeGuidelinesId by preferences.activeGuidelinesId().collectAsState()
        val profiles = remember(profilesJson) { store.profiles() }
        val providers = remember(providersJson) { aiSettings.providers() }
        val allGuidelines = remember(guidelinesJson) { aiSettings.guidelines() }
        val defaultName = stringResource(TDMR.strings.pref_profile_default)
        val noProvider = stringResource(TDMR.strings.pref_ai_no_active_provider)

        ManagerScreen(
            title = stringResource(TDMR.strings.pref_ai_task_profiles),
            entries = profiles,
            entryKey = { it.id },
            addLabel = stringResource(TDMR.strings.pref_profile_add),
            onAdd = { navigator.push(AiTaskProfileEditorScreen()) },
            onBack = back::invoke,
        ) { profile ->
            ManagerRow(
                title = profile.name.ifBlank { defaultName },
                subtitle = describeTaskProfile(
                    profile,
                    providers,
                    allGuidelines,
                    activeProviderId,
                    activeGuidelinesId,
                    noProvider,
                ),
                icon = { Icon(Icons.Outlined.AutoAwesome, null) },
                onClick = { navigator.push(AiTaskProfileEditorScreen(profile.id)) },
                onDelete = profile.takeIf { it.deletable }?.let { { store.delete(it.id) } },
            )
        }
    }
}

private data class AiTaskProfileEditorScreen(private val profileId: String? = null) : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val back = LocalBackPress.currentOrThrow
        val store = remember { Injekt.get<AiTaskProfileStore>() }
        val aiSettings = remember { Injekt.get<AiSettingsStore>() }

        val original = remember(profileId) { profileId?.let(store::profile) }
        val isDefault = original?.id == DEFAULT_PROFILE_ID
        val id = original?.id ?: remember { UUID.randomUUID().toString() }
        // "Use the globally active one" is a real choice, so both pickers carry a null entry.
        val providers = remember { listOf<AIProvider?>(null) + aiSettings.providers() }
        val guidelinesOptions = remember { listOf<UserGuidelines?>(null) + aiSettings.guidelines() }

        var name by remember { mutableStateOf(original?.name.orEmpty()) }
        var provider by remember {
            mutableStateOf(original?.providerId?.let { id -> providers.firstOrNull { it?.id == id } })
        }
        var selectedGuidelines by remember {
            mutableStateOf(original?.guidelinesId?.let { id -> guidelinesOptions.firstOrNull { it?.id == id } })
        }

        val useActive = stringResource(TDMR.strings.pref_profile_use_active)
        val defaultName = stringResource(TDMR.strings.pref_profile_default)

        fun save() {
            store.save(
                AiTaskProfile(
                    id = id,
                    // The default profile has no editable name; it is labelled by the UI.
                    name = if (isDefault) "" else name.trim(),
                    providerId = provider?.id,
                    guidelinesId = selectedGuidelines?.id,
                ),
            )
            navigator.pop()
        }

        Scaffold(topBar = {
            AppBar(
                title = when {
                    isDefault -> defaultName
                    original == null -> stringResource(TDMR.strings.pref_profile_new)
                    else -> stringResource(TDMR.strings.pref_ai_task_profiles)
                },
                navigateUp = back::invoke,
                actions = {
                    TextButton(onClick = ::save, enabled = isDefault || name.isNotBlank()) {
                        Text(stringResource(MR.strings.action_save))
                    }
                },
            )
        }) { padding ->
            Column(
                Modifier.padding(padding).padding(16.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!isDefault) {
                    ManagerTextField(name, { name = it }, stringResource(TDMR.strings.pref_profile_name))
                }
                SettingsDropdownField(
                    label = stringResource(TDMR.strings.pref_ai_active_provider),
                    value = provider,
                    values = providers,
                    valueLabel = { it?.alias ?: useActive },
                    onSelected = { provider = it },
                )
                SettingsDropdownField(
                    label = stringResource(TDMR.strings.pref_ai_active_guidelines),
                    value = selectedGuidelines,
                    values = guidelinesOptions,
                    valueLabel = { it?.name ?: useActive },
                    onSelected = { selectedGuidelines = it },
                )
            }
        }
    }
}

/**
 * The subtitle under a task profile: what it will actually run with. Resolves through the same
 * [resolve] the store applies against preferences, so the label cannot drift from what runs.
 */
private fun describeTaskProfile(
    profile: AiTaskProfile,
    providers: List<AIProvider>,
    guidelines: List<UserGuidelines>,
    activeProviderId: String,
    activeGuidelinesId: String,
    noProviderLabel: String,
): String {
    val resolved = guidelines.resolve(profile.guidelinesId, activeGuidelinesId)
    return listOfNotNull(
        providers.resolve(profile.providerId, activeProviderId)?.alias ?: noProviderLabel,
        resolved.name.takeIf { it.isNotBlank() && resolved.id != UserGuidelines.DEFAULT_ID },
    ).joinToString(" · ")
}
