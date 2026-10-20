@file:Suppress("ktlint:standard:filename")

package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import eu.kanade.tachiyomi.ui.reader.setting.RegexReplacement
import eu.kanade.tachiyomi.ui.reader.setting.ReplacementTarget
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.i18n.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScopedContentEditor(
    title: String,
    scope: String,
    target: ReplacementTarget?,
    onScope: (String) -> Unit,
    canSave: Boolean,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        // Compose owns IME insets so the window does not also pan to the cursor.
        properties = DialogProperties(decorFitsSystemWindows = false),
        modifier = Modifier.systemBarsPadding().imePadding(),
        title = { Text(title) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = scope == RegexReplacement.NOVEL,
                        enabled = target != null,
                        onClick = { onScope(RegexReplacement.NOVEL) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        label = { Text(stringResource(TDMR.strings.replacement_scope_novel)) },
                    )
                    SegmentedButton(
                        selected = scope == RegexReplacement.GLOBAL,
                        onClick = { onScope(RegexReplacement.GLOBAL) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        label = { Text(stringResource(TDMR.strings.replacement_scope_global)) },
                    )
                }
                content()
            }
        },
        confirmButton = {
            TextButton(enabled = canSave, onClick = onSave) { Text(stringResource(MR.strings.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_cancel)) } },
    )
}
