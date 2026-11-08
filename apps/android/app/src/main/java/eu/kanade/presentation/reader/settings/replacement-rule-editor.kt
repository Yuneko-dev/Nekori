@file:Suppress("ktlint:standard:filename")

package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.more.settings.screen.ManagerSwitchRow
import eu.kanade.tachiyomi.ui.reader.setting.RegexReplacement
import eu.kanade.tachiyomi.ui.reader.setting.ReplacementTarget
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun ReplacementRuleEditor(
    initial: RegexReplacement,
    target: ReplacementTarget?,
    onDismiss: () -> Unit,
    onSave: (RegexReplacement) -> Unit,
) {
    var title by rememberSaveable(initial.id) { mutableStateOf(initial.title) }
    var pattern by rememberSaveable(initial.id) { mutableStateOf(initial.pattern) }
    var replacement by rememberSaveable(initial.id) { mutableStateOf(initial.replacement) }
    var scope by rememberSaveable(initial.id) { mutableStateOf(initial.scope) }
    var isRegex by rememberSaveable(initial.id) { mutableStateOf(initial.isRegex) }
    var caseSensitive by rememberSaveable(initial.id) { mutableStateOf(initial.caseSensitive) }
    var wholeWord by rememberSaveable(initial.id) { mutableStateOf(initial.matchWholeWord) }
    var error by remember { mutableStateOf<String?>(null) }
    val rule = initial.copy(
        title = title.trim(),
        pattern = pattern,
        replacement = replacement,
        scope = scope,
        target = target.takeIf { scope == RegexReplacement.NOVEL },
        isRegex = isRegex,
        caseSensitive = caseSensitive,
        matchWholeWord = wholeWord,
    )
    ScopedContentEditor(
        title = stringResource(
            if (initial.pattern.isEmpty()) TDMR.strings.novel_add_rule else TDMR.strings.novel_edit_rule,
        ),
        scope = scope,
        target = target,
        onScope = { scope = it },
        canSave = title.isNotBlank() && pattern.isNotBlank() && (scope != RegexReplacement.NOVEL || target != null),
        onDismiss = onDismiss,
        onSave = {
            try {
                rule.compile()
                onSave(rule)
            } catch (e: IllegalArgumentException) {
                error = e.localizedMessage
            }
        },
    ) {
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text(stringResource(MR.strings.title)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = pattern,
            onValueChange = {
                pattern = it
                error = null
            },
            label = {
                Text(
                    stringResource(
                        if (isRegex) TDMR.strings.novel_regex_pattern else TDMR.strings.novel_find_text,
                    ),
                )
            },
            isError = error != null,
            supportingText = error?.let { message -> { Text(message) } },
            maxLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = replacement,
            onValueChange = { replacement = it },
            label = { Text(stringResource(TDMR.strings.novel_replace_with)) },
            supportingText = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(stringResource(TDMR.strings.replacement_remove_hint))
                }
            },
            maxLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )
        Column {
            ManagerSwitchRow(stringResource(TDMR.strings.novel_use_regex), isRegex) {
                isRegex = it
                error = null
            }
            ManagerSwitchRow(stringResource(TDMR.strings.label_case_sensitive_matching), caseSensitive) {
                caseSensitive = it
            }
            if (!isRegex) {
                ManagerSwitchRow(stringResource(TDMR.strings.novel_match_whole_word), wholeWord) {
                    wholeWord = it
                }
            }
        }
        ReplacementRuleTest(rule)
    }
}
