package eu.kanade.presentation.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.more.settings.widget.ListPreferenceWidget
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.tachiyomi.data.translation.TranslationEngineManager
import tachiyomi.domain.translation.model.NovelAiTask
import tachiyomi.domain.translation.model.NovelTarget
import tachiyomi.domain.translation.model.TranslationEngineId
import tachiyomi.domain.translation.model.TranslationPurpose
import tachiyomi.domain.translation.service.TranslationPreferences
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun ReaderAiTaskSheet(
    task: NovelAiTask,
    target: NovelTarget?,
    onDismissRequest: () -> Unit,
    currentLanguage: String,
    autoTranslateEnabled: Boolean,
    onToggleAutoTranslate: (Boolean) -> Unit,
    onSelectLanguage: (String) -> Unit,
    onRun: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val translation = task == NovelAiTask.TRANSLATION
    val prefs = remember { Injekt.get<TranslationPreferences>() }
    val engineId by prefs.engineId(TranslationPurpose.CHAPTER).collectAsState()
    val engineManager = remember { Injekt.get<TranslationEngineManager>() }
    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) {
            Text(
                text = stringResource(
                    if (translation) TDMR.strings.action_translate else TDMR.strings.action_summarize_chapter,
                ),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            TextPreferenceWidget(
                title = stringResource(
                    if (translation) TDMR.strings.action_retranslate else TDMR.strings.action_resummarize_chapter,
                ),
                icon = Icons.Outlined.Refresh,
                onPreferenceClick = onRun,
            )
            if (translation) {
                TextPreferenceWidget(
                    title = stringResource(TDMR.strings.action_translation_settings),
                    icon = Icons.Outlined.Settings,
                    onPreferenceClick = onOpenSettings,
                )
            }
            if (!translation || TranslationEngineId.fromKey(engineId) == TranslationEngineId.LLM) {
                NovelGuidelinesSelect(task = task, target = target)
            }
            if (translation) {
                val languages = remember(engineId) {
                    engineManager.getSupportedLanguages(TranslationPurpose.CHAPTER).filter {
                        it.first != "auto"
                    }.toMap()
                }
                ListPreferenceWidget(
                    value = currentLanguage,
                    title = stringResource(TDMR.strings.pref_translation_target_language),
                    subtitle = languages[currentLanguage] ?: currentLanguage,
                    icon = null,
                    entries = languages,
                    onValueChange = {
                        onSelectLanguage(it)
                        onDismissRequest()
                    },
                )
                SwitchPreferenceWidget(
                    title = stringResource(TDMR.strings.translation_smart_auto),
                    subtitle = stringResource(TDMR.strings.translation_smart_auto_summary),
                    checked = autoTranslateEnabled,
                    onCheckedChanged = onToggleAutoTranslate,
                )
            }
        }
    }
}
