@file:Suppress("ktlint:standard:filename")

package eu.kanade.presentation.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.more.settings.widget.ListPreferenceWidget
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.tachiyomi.data.translation.AiSettingsStore
import tachiyomi.core.common.preference.ContentPreferencesLock
import tachiyomi.domain.translation.model.NovelAiTask
import tachiyomi.domain.translation.model.NovelGuidelinesChoice
import tachiyomi.domain.translation.model.NovelTarget
import tachiyomi.domain.translation.model.resolve
import tachiyomi.domain.translation.service.TranslationPreferences
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
internal fun NovelGuidelinesSelect(task: NovelAiTask, target: NovelTarget?) {
    val store = remember { Injekt.get<AiSettingsStore>() }
    val prefs = remember { Injekt.get<TranslationPreferences>() }
    val globalPreference = remember(task) {
        if (task == NovelAiTask.TRANSLATION) prefs.translationGuidelinesId() else prefs.chapterSummaryGuidelinesId()
    }
    val globalId by globalPreference.collectAsState()
    val raw by prefs.novelGuidelinesJson().collectAsState()
    val libraryRaw by prefs.userGuidelinesJson().collectAsState()
    val loaded = remember(raw, task, target) { runCatching { target?.let { store.choice(it, task) } } }
    val library = remember(libraryRaw) { runCatching { store.guidelines() } }
    val choice = loaded.getOrNull()
    val selected = if (target == null) {
        if (globalId.isBlank()) "none" else "shared:$globalId"
    } else {
        when (choice?.mode) {
            null -> "inherit"
            "shared" -> "shared:${choice.guidelinesId}"
            else -> choice.mode
        }
    }
    val entries = linkedMapOf<String, String>().apply {
        if (target != null) put("inherit", stringResource(TDMR.strings.ai_guidelines_inherit))
        put("none", stringResource(TDMR.strings.ai_guidelines_none))
        library.getOrNull().orEmpty().forEach { put("shared:${it.id}", it.name) }
        if (target != null) put("custom", stringResource(TDMR.strings.ai_guidelines_custom))
        if (selected !in this) put(selected, stringResource(TDMR.strings.ai_guidelines_missing))
    }
    var draft by remember(task, target) { mutableStateOf<String?>(null) }
    var draftOriginal by remember(task, target) { mutableStateOf("") }
    var saveError by remember { mutableStateOf(false) }
    var copyError by remember { mutableStateOf(false) }

    fun save(value: NovelGuidelinesChoice?, original: String = raw): Boolean {
        val saved = runCatching {
            if (target != null) {
                store.saveChoice(target, task, value, original)
            } else {
                synchronized(ContentPreferencesLock) {
                    if (globalPreference.get() != globalId) {
                        false
                    } else {
                        globalPreference.set(value?.guidelinesId.orEmpty())
                        true
                    }
                }
            }
        }.getOrDefault(false)
        saveError = !saved
        return saved
    }
    fun editCustom() {
        runCatching {
            when (choice?.mode) {
                "custom" -> choice.text.orEmpty()
                "none" -> ""
                "shared" -> library.getOrThrow().resolve(choice.guidelinesId).guidelines
                else -> store.effectiveGuidelines(globalId)
            }
        }.onSuccess {
            draftOriginal = raw
            draft = it
            copyError = false
            saveError = false
        }.onFailure { copyError = true }
    }

    if (loaded.isSuccess) {
        ListPreferenceWidget(
            value = selected,
            title = stringResource(TDMR.strings.pref_ai_user_guidelines),
            subtitle = entries[selected],
            icon = null,
            entries = entries,
            onValueChange = { value ->
                copyError = false
                saveError = false
                when (value) {
                    "custom" -> editCustom()
                    "inherit" -> save(null)
                    "none" -> save(NovelGuidelinesChoice("none"))
                    else -> save(NovelGuidelinesChoice("shared", guidelinesId = value.removePrefix("shared:")))
                }
            },
        )
        if (selected == "custom") {
            TextPreferenceWidget(
                title = stringResource(TDMR.strings.ai_guidelines_custom_text),
                subtitle = choice?.text,
                subtitleMaxLines = 2,
                onPreferenceClick = ::editCustom,
            )
        }
    }
    if (loaded.isFailure || library.isFailure || copyError || saveError) {
        TextPreferenceWidget(
            subtitle = stringResource(
                if (saveError) TDMR.strings.ai_guidelines_save_error else TDMR.strings.ai_guidelines_load_error,
            ),
        )
    }
    if (draft != null) {
        AlertDialog(
            onDismissRequest = { draft = null },
            title = { Text(stringResource(TDMR.strings.pref_ai_user_guidelines)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = draft.orEmpty(),
                        onValueChange = { draft = it },
                        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                        minLines = 5,
                        label = { Text(stringResource(TDMR.strings.pref_ai_guidelines_text)) },
                    )
                    if (saveError) {
                        Text(
                            stringResource(TDMR.strings.ai_guidelines_save_error),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (save(NovelGuidelinesChoice("custom", text = draft.orEmpty()), draftOriginal)) draft = null
                }) { Text(stringResource(MR.strings.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { draft = null }) { Text(stringResource(MR.strings.action_cancel)) }
            },
        )
    }
}
