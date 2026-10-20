package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.LocalBackPress
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.translation.AiSettingsStore
import eu.kanade.tachiyomi.util.system.toast
import tachiyomi.domain.translation.model.UserGuidelines
import tachiyomi.domain.translation.service.TranslationPreferences
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.UUID

object UserGuidelinesManagerScreen : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val back = LocalBackPress.currentOrThrow
        val store = remember { Injekt.get<AiSettingsStore>() }
        val preferences = remember { Injekt.get<TranslationPreferences>() }
        val guidelinesJson by preferences.userGuidelinesJson().collectAsState()
        val entriesResult = remember(guidelinesJson) { runCatching { store.guidelines() } }
        val entries = entriesResult.getOrDefault(emptyList())
        val context = LocalContext.current
        val loadError = stringResource(TDMR.strings.ai_guidelines_load_error)
        val conflictError = stringResource(TDMR.strings.ai_guidelines_save_conflict)
        val emptyHint = stringResource(TDMR.strings.pref_ai_guidelines_hint)

        if (entriesResult.isFailure) {
            Scaffold(topBar = {
                AppBar(stringResource(TDMR.strings.pref_ai_user_guidelines), navigateUp = back::invoke)
            }) { padding ->
                Text(loadError, Modifier.padding(padding).padding(16.dp), color = MaterialTheme.colorScheme.error)
            }
            return
        }

        ManagerScreen(
            title = stringResource(TDMR.strings.pref_ai_user_guidelines),
            entries = entries,
            entryKey = { it.id },
            addLabel = stringResource(TDMR.strings.pref_ai_add_guidelines),
            onAdd = { navigator.push(UserGuidelinesEditorScreen()) },
            onBack = back::invoke,
        ) { entry ->
            ManagerRow(
                title = entry.name,
                subtitle = entry.guidelines.ifBlank { emptyHint },
                icon = { Icon(Icons.Outlined.Description, null) },
                onClick = { navigator.push(UserGuidelinesEditorScreen(entry.id)) },
                onDelete = entry.takeIf { it.deletable }?.let {
                    {
                        runCatching { store.deleteGuidelines(it.id, guidelinesJson) }
                            .onSuccess { saved -> if (!saved) context.toast(conflictError) }
                            .onFailure { context.toast(loadError) }
                        Unit
                    }
                },
            )
        }
    }
}

data class UserGuidelinesEditorScreen(private val guidelinesId: String? = null) : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val back = LocalBackPress.currentOrThrow
        val store = remember { Injekt.get<AiSettingsStore>() }
        val preferences = remember { Injekt.get<TranslationPreferences>() }
        val originalJson = remember { preferences.userGuidelinesJson().get() }
        val entriesResult = remember(originalJson) { runCatching { UserGuidelines.decode(originalJson) } }
        val original = entriesResult.getOrNull()?.firstOrNull { it.id == guidelinesId }
        val id = original?.id ?: remember { UUID.randomUUID().toString() }
        var name by remember { mutableStateOf(original?.name.orEmpty()) }
        var text by remember { mutableStateOf(original?.guidelines.orEmpty()) }
        var saveFailed by remember { mutableStateOf(false) }
        val context = LocalContext.current
        val loadError = stringResource(TDMR.strings.ai_guidelines_load_error)
        val conflictError = stringResource(TDMR.strings.ai_guidelines_save_conflict)

        fun save() {
            if (name.isBlank()) return
            runCatching { store.saveGuidelines(UserGuidelines(id, name.trim(), text), originalJson) }
                .onSuccess { saved -> if (saved) navigator.pop() else saveFailed = true }
                .onFailure { context.toast(loadError) }
        }

        Scaffold(topBar = {
            AppBar(
                title = stringResource(
                    if (original == null) {
                        TDMR.strings.pref_ai_add_guidelines
                    } else {
                        TDMR.strings.pref_ai_edit_guidelines
                    },
                ),
                navigateUp = back::invoke,
                actions = {
                    TextButton(
                        onClick = ::save,
                        enabled = name.isNotBlank() && entriesResult.isSuccess && !saveFailed,
                    ) {
                        Text(stringResource(MR.strings.action_save))
                    }
                },
            )
        }) { padding ->
            Column(
                Modifier.padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (entriesResult.isFailure || saveFailed) {
                    Text(
                        if (saveFailed) conflictError else loadError,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                ManagerTextField(
                    name,
                    { name = it },
                    stringResource(MR.strings.name),
                    readOnly = id == UserGuidelines.DEFAULT_ID,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(TDMR.strings.pref_ai_guidelines_text)) },
                    supportingText = { Text(stringResource(TDMR.strings.pref_ai_guidelines_hint)) },
                    minLines = 8,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    }
}
