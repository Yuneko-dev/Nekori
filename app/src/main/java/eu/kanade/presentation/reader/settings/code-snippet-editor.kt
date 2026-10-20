@file:Suppress("ktlint:standard:filename")

package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import eu.kanade.presentation.more.settings.screen.ManagerSwitchRow
import eu.kanade.tachiyomi.ui.reader.setting.RegexReplacement
import eu.kanade.tachiyomi.ui.reader.setting.ReplacementTarget
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun SnippetEditDialog(
    title: String,
    initialSnippet: CodeSnippet?,
    focusCodeFieldByDefault: Boolean,
    showRunOnAppend: Boolean,
    existingSafeTitles: Set<String>,
    target: ReplacementTarget? = null,
    onDismiss: () -> Unit,
    onConfirm: (CodeSnippet) -> Unit,
) {
    var snippetTitle by remember { mutableStateOf(initialSnippet?.title ?: "") }
    var snippetCode by remember { mutableStateOf(initialSnippet?.code ?: "") }
    var runOnAppend by remember { mutableStateOf(initialSnippet?.runOnAppend ?: false) }
    var scope by remember { mutableStateOf(initialSnippet?.scope ?: RegexReplacement.GLOBAL) }
    val codeFocusRequester = remember { FocusRequester() }
    val trimmedSafeTitle = safeTitleOf(snippetTitle.trim())
    val isDuplicateTitle = existingSafeTitles.contains(trimmedSafeTitle)

    LaunchedEffect(focusCodeFieldByDefault) {
        if (focusCodeFieldByDefault) {
            codeFocusRequester.requestFocus()
        }
    }

    ScopedContentEditor(
        title = title,
        scope = scope,
        target = target,
        onScope = { scope = it },
        canSave = snippetTitle.isNotBlank() && snippetCode.isNotBlank() && !isDuplicateTitle &&
            (scope != RegexReplacement.NOVEL || target != null),
        onDismiss = onDismiss,
        onSave = {
            onConfirm(
                CodeSnippet(
                    title = snippetTitle.trim(),
                    code = snippetCode,
                    enabled = initialSnippet?.enabled ?: true,
                    runOnAppend = runOnAppend,
                    id = initialSnippet?.id ?: java.util.UUID.randomUUID().toString(),
                    scope = scope,
                    target = target.takeIf { scope == RegexReplacement.NOVEL },
                ),
            )
        },
    ) {
        OutlinedTextField(
            value = snippetTitle,
            onValueChange = { snippetTitle = it },
            label = { Text(stringResource(MR.strings.title)) },
            singleLine = true,
            isError = isDuplicateTitle,
            supportingText = if (isDuplicateTitle) {
                { Text(stringResource(TDMR.strings.novel_snippet_duplicate_title, trimmedSafeTitle)) }
            } else {
                null
            },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = snippetCode,
            onValueChange = { snippetCode = it },
            label = { Text(stringResource(TDMR.strings.novel_snippet_code)) },
            minLines = 5,
            maxLines = 10,
            modifier = Modifier.fillMaxWidth().focusRequester(codeFocusRequester),
        )
        if (showRunOnAppend) {
            ManagerSwitchRow(stringResource(TDMR.strings.novel_snippet_run_on_append), runOnAppend) {
                runOnAppend = it
            }
        }
    }
}
