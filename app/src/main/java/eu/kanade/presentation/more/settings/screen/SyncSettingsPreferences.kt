package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.resources.StringResource
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.screen.data.SyncSettingsSelector
import eu.kanade.presentation.more.settings.screen.data.SyncTriggerOptionsScreen
import eu.kanade.presentation.util.relativeTimeSpanString
import eu.kanade.tachiyomi.data.sync.SyncDataJob
import eu.kanade.tachiyomi.data.sync.SyncManager
import eu.kanade.tachiyomi.data.sync.service.GoogleDriveService
import eu.kanade.tachiyomi.data.sync.service.GoogleDriveSyncService
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.components.material.TextButton
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
internal fun SettingsDataScreen.getSyncPreferences(
    syncPreferences: SyncPreferences,
    syncService: Int,
): List<Preference> {
    val context = LocalContext.current
    return listOf(
        Preference.PreferenceGroup(
            title = stringResource(TDMR.strings.pref_sync_service_category),
            preferenceItems = listOf(
                Preference.PreferenceItem.ListPreference(
                    preference = syncPreferences.syncService(),
                    entries = mapOf(
                        SyncManager.SyncService.NONE.value to stringResource(MR.strings.off),
                        SyncManager.SyncService.GOOGLE_DRIVE.value to stringResource(TDMR.strings.google_drive),
                        SyncManager.SyncService.WEB_DAV.value to stringResource(TDMR.strings.web_dav),
                    ),
                    title = stringResource(TDMR.strings.pref_sync_service),
                    onValueChanged = {
                        if (it != SyncManager.SyncService.NONE.value) {
                            SyncDataJob.setupTask(context)
                        } else {
                            SyncDataJob.setupTask(context, prefInterval = 0)
                        }
                        true
                    },
                ),
            ),
        ),
    ) + getSyncServicePreferences(syncPreferences, syncService)
}

@Composable
private fun SettingsDataScreen.getSyncServicePreferences(
    syncPreferences: SyncPreferences,
    syncService: Int,
): List<Preference> {
    val syncServiceType = SyncManager.SyncService.fromInt(syncService)
    val basePreferences = getBaseSyncPreferences(syncServiceType, syncPreferences)

    return if (syncServiceType != SyncManager.SyncService.NONE) {
        basePreferences + getAdditionalSyncPreferences(syncPreferences)
    } else {
        basePreferences
    }
}

@Composable
private fun SettingsDataScreen.getBaseSyncPreferences(
    syncServiceType: SyncManager.SyncService,
    syncPreferences: SyncPreferences,
): List<Preference> {
    val navigator = LocalNavigator.currentOrThrow
    val preferences = when (syncServiceType) {
        SyncManager.SyncService.NONE -> emptyList()
        SyncManager.SyncService.GOOGLE_DRIVE -> getGoogleDrivePreferences()
        SyncManager.SyncService.WEB_DAV -> getWebDavPreferences(syncPreferences)
    }

    return if (syncServiceType != SyncManager.SyncService.NONE) {
        preferences + Preference.PreferenceItem.TextPreference(
            title = stringResource(TDMR.strings.pref_choose_what_to_sync),
            onClick = {
                navigator.push(SyncSettingsSelector())
            },
        )
    } else {
        preferences
    }
}

@Composable
private fun SettingsDataScreen.getAdditionalSyncPreferences(syncPreferences: SyncPreferences): List<Preference> {
    return listOf(
        getSyncNowPref(),
        getAutomaticSyncGroup(syncPreferences),
    )
}

@Composable
private fun SettingsDataScreen.getGoogleDrivePreferences(): List<Preference> {
    val context = LocalContext.current
    val googleDriveSync = Injekt.get<GoogleDriveService>()
    return listOf(
        Preference.PreferenceItem.TextPreference(
            title = stringResource(MR.strings.login),
            onClick = {
                val intent = googleDriveSync.getSignInIntent()
                context.startActivity(intent)
            },
        ),
        getGoogleDrivePurge(),
    )
}

@Composable
private fun SettingsDataScreen.getGoogleDrivePurge(): Preference.PreferenceItem.TextPreference {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val googleDriveSync = remember { GoogleDriveSyncService(context) }
    var showPurgeDialog by remember { mutableStateOf(false) }

    if (showPurgeDialog) {
        PurgeConfirmationDialog(
            onConfirm = {
                showPurgeDialog = false
                scope.launch {
                    val result = googleDriveSync.deleteSyncDataFromGoogleDrive()
                    when (result) {
                        GoogleDriveSyncService.DeleteSyncDataStatus.NOT_INITIALIZED -> context.toast(
                            TDMR.strings.google_drive_not_signed_in,
                            duration = android.widget.Toast.LENGTH_LONG,
                        )
                        GoogleDriveSyncService.DeleteSyncDataStatus.NO_FILES -> context.toast(
                            TDMR.strings.google_drive_sync_data_not_found,
                            duration = android.widget.Toast.LENGTH_LONG,
                        )
                        GoogleDriveSyncService.DeleteSyncDataStatus.SUCCESS -> context.toast(
                            TDMR.strings.google_drive_sync_data_purged,
                            duration = android.widget.Toast.LENGTH_LONG,
                        )
                        GoogleDriveSyncService.DeleteSyncDataStatus.ERROR -> context.toast(
                            TDMR.strings.google_drive_sync_data_purge_error,
                            duration = android.widget.Toast.LENGTH_LONG,
                        )
                    }
                }
            },
            onDismissRequest = { showPurgeDialog = false },
        )
    }

    return Preference.PreferenceItem.TextPreference(
        title = stringResource(TDMR.strings.pref_google_drive_purge_sync_data),
        onClick = { showPurgeDialog = true },
    )
}

@Composable
private fun PurgeConfirmationDialog(
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(TDMR.strings.pref_purge_confirmation_title)) },
        text = { Text(text = stringResource(TDMR.strings.pref_purge_confirmation_message)) },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
    )
}

@Composable
private fun SettingsDataScreen.getWebDavPreferences(syncPreferences: SyncPreferences): List<Preference> {
    val scope = rememberCoroutineScope()

    return listOf(
        Preference.PreferenceItem.EditTextPreference(
            preference = syncPreferences.webDavUrl(),
            title = stringResource(TDMR.strings.pref_webdav_url),
            subtitle = stringResource(TDMR.strings.pref_webdav_url_summ),
            onValueChanged = { newValue ->
                scope.launch {
                    syncPreferences.webDavUrl().set(newValue.trim())
                }
                true
            },
        ),
        Preference.PreferenceItem.EditTextPreference(
            preference = syncPreferences.webDavUsername(),
            title = stringResource(MR.strings.username),
            subtitle = stringResource(TDMR.strings.pref_webdav_username_summ),
            onValueChanged = { newValue ->
                scope.launch {
                    syncPreferences.webDavUsername().set(newValue.trim())
                }
                true
            },
        ),
        run {
            var dialogOpen by remember { mutableStateOf(false) }
            if (dialogOpen) {
                SyncPasswordDialog(
                    onDismissRequest = { dialogOpen = false },
                    onReturnPassword = { password ->
                        dialogOpen = false
                        scope.launch {
                            syncPreferences.webDavPassword().set(password.replace("\n", ""))
                        }
                    },
                    title = MR.strings.password,
                )
            }
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.password),
                subtitle = stringResource(TDMR.strings.pref_webdav_password_summ),
                onClick = {
                    dialogOpen = true
                },
            )
        },
        Preference.PreferenceItem.EditTextPreference(
            preference = syncPreferences.webDavFolder(),
            title = stringResource(TDMR.strings.pref_webdav_folder),
            subtitle = stringResource(TDMR.strings.pref_webdav_folder_summ),
            onValueChanged = { newValue ->
                scope.launch {
                    syncPreferences.webDavFolder().set(newValue.trim())
                }
                true
            },
        ),
    )
}

@Composable
private fun SyncPasswordDialog(
    onDismissRequest: () -> Unit,
    onReturnPassword: (String) -> Unit,
    title: StringResource,
) {
    var password by rememberSaveable { mutableStateOf("") }
    var passwordVisibility by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(title)) },
        text = {
            TextField(
                value = password,
                onValueChange = { password = it },
                maxLines = 1,
                placeholder = { Text(text = stringResource(MR.strings.password)) },
                label = { Text(text = stringResource(MR.strings.password)) },
                trailingIcon = {
                    IconButton(onClick = { passwordVisibility = !passwordVisibility }) {
                        Icon(
                            imageVector = if (passwordVisibility) {
                                Icons.Default.Visibility
                            } else {
                                Icons.Default.VisibilityOff
                            },
                            contentDescription = null,
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = if (passwordVisibility) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
            )
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        confirmButton = {
            TextButton(onClick = { onReturnPassword(password) }) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
    )
}

@Composable
private fun SettingsDataScreen.getSyncNowPref(): Preference.PreferenceGroup {
    val context = LocalContext.current
    return Preference.PreferenceGroup(
        title = stringResource(TDMR.strings.pref_sync_now_group_title),
        preferenceItems = listOf(
            getSyncOptionsPref(),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(TDMR.strings.pref_sync_now),
                subtitle = stringResource(TDMR.strings.pref_sync_now_subtitle),
                onClick = {
                    if (!SyncDataJob.isRunning(context)) {
                        SyncDataJob.startNow(context, manual = true)
                    } else {
                        context.toast(TDMR.strings.sync_in_progress)
                    }
                },
            ),
        ),
    )
}

@Composable
private fun SettingsDataScreen.getSyncOptionsPref(): Preference.PreferenceItem.TextPreference {
    val navigator = LocalNavigator.currentOrThrow
    return Preference.PreferenceItem.TextPreference(
        title = stringResource(TDMR.strings.pref_sync_options),
        subtitle = stringResource(TDMR.strings.pref_sync_options_summ),
        onClick = { navigator.push(SyncTriggerOptionsScreen()) },
    )
}

@Composable
private fun SettingsDataScreen.getAutomaticSyncGroup(syncPreferences: SyncPreferences): Preference.PreferenceGroup {
    val context = LocalContext.current
    val syncIntervalPref = syncPreferences.syncInterval()
    val lastSync by syncPreferences.lastSyncTimestamp().collectAsState()

    return Preference.PreferenceGroup(
        title = stringResource(TDMR.strings.pref_sync_automatic_category),
        preferenceItems = listOf(
            Preference.PreferenceItem.ListPreference(
                preference = syncIntervalPref,
                entries = mapOf(
                    0 to stringResource(MR.strings.off),
                    30 to stringResource(TDMR.strings.update_30min),
                    60 to stringResource(TDMR.strings.update_1hour),
                    180 to stringResource(TDMR.strings.update_3hour),
                    360 to stringResource(MR.strings.update_6hour),
                    720 to stringResource(MR.strings.update_12hour),
                    1440 to stringResource(MR.strings.update_24hour),
                    2880 to stringResource(MR.strings.update_48hour),
                    10080 to stringResource(MR.strings.update_weekly),
                ),
                title = stringResource(TDMR.strings.pref_sync_interval),
                onValueChanged = {
                    SyncDataJob.setupTask(context, prefInterval = it)
                    true
                },
            ),
            Preference.PreferenceItem.InfoPreference(
                stringResource(TDMR.strings.last_synchronization, relativeTimeSpanString(lastSync)),
            ),
        ),
    )
}
