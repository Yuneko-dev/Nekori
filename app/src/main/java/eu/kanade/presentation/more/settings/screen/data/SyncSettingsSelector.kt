package eu.kanade.presentation.more.settings.screen.data

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.domain.sync.models.SyncSettings
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import kotlinx.coroutines.flow.update
import mihon.core.viewmodel.StateViewModel
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.components.LazyColumnWithAction
import tachiyomi.presentation.core.components.SectionCard
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SyncSettingsSelector : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model = viewModel<SyncSettingsSelectorModel>()
        val state by model.state.collectAsState()

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(MR.strings.pref_choose_what_to_sync),
                    navigateUp = navigator::pop,
                    scrollBehavior = it,
                )
            },
        ) { contentPadding ->
            LazyColumnWithAction(
                contentPadding = contentPadding,
                actionLabel = stringResource(MR.strings.action_save),
                actionEnabled = true,
                onClickAction = {
                    navigator.pop()
                },
            ) {
                item {
                    SectionCard(MR.strings.label_library) {
                        Options(BackupOptions.libraryOptions, state, model)
                    }
                }

                item {
                    SectionCard(MR.strings.label_settings) {
                        Options(BackupOptions.settingsOptions, state, model)
                    }
                }
            }
        }
    }

    @Composable
    private fun Options(
        options: List<BackupOptions.Entry>,
        state: SyncSettingsSelectorModel.State,
        model: SyncSettingsSelectorModel,
    ) {
        options.forEach { option ->
            LabeledCheckbox(
                label = stringResource(option.label),
                checked = option.getter(state.options),
                onCheckedChange = {
                    model.toggle(option.setter, it)
                },
                enabled = option.enabled(state.options),
            )
        }
    }
}

class SyncSettingsSelectorModel(
    private val syncPreferences: SyncPreferences = Injekt.get(),
) : StateViewModel<SyncSettingsSelectorModel.State>(
    State(syncOptionsToBackupOptions(syncPreferences.getSyncSettings())),
) {
    fun toggle(setter: (BackupOptions, Boolean) -> BackupOptions, enabled: Boolean) {
        mutableState.update {
            val updatedOptions = setter(it.options, enabled)
            syncPreferences.setSyncSettings(backupOptionsToSyncOptions(updatedOptions))
            it.copy(options = updatedOptions)
        }
    }

    @Immutable
    data class State(
        val options: BackupOptions = BackupOptions(),
    )

    companion object {
        private fun syncOptionsToBackupOptions(syncSettings: SyncSettings): BackupOptions {
            return BackupOptions(
                libraryEntries = syncSettings.libraryEntries,
                includeManga = syncSettings.includeManga,
                includeNovels = syncSettings.includeNovels,
                categories = syncSettings.categories,
                chapters = syncSettings.chapters,
                tracking = syncSettings.tracking,
                history = syncSettings.history,
                appSettings = syncSettings.appSettings,
                extensionStores = syncSettings.extensionStores,
                sourceSettings = syncSettings.sourceSettings,
                privateSettings = syncSettings.privateSettings,
                readEntries = syncSettings.readEntries,
            )
        }

        private fun backupOptionsToSyncOptions(backupOptions: BackupOptions): SyncSettings {
            return SyncSettings(
                libraryEntries = backupOptions.libraryEntries,
                includeManga = backupOptions.includeManga,
                includeNovels = backupOptions.includeNovels,
                categories = backupOptions.categories,
                chapters = backupOptions.chapters,
                tracking = backupOptions.tracking,
                history = backupOptions.history,
                appSettings = backupOptions.appSettings,
                extensionStores = backupOptions.extensionStores,
                sourceSettings = backupOptions.sourceSettings,
                privateSettings = backupOptions.privateSettings,
                readEntries = backupOptions.readEntries,
            )
        }
    }
}
