@file:Suppress("ktlint:standard:filename")

package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.reader.setting.RegexReplacement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun ReplacementRuleTest(rule: RegexReplacement) {
    var input by rememberSaveable { mutableStateOf("") }
    var result by remember(rule, input) { mutableStateOf<Result<String>?>(null) }
    var running by remember { mutableStateOf(false) }
    val currentRule by rememberUpdatedState(rule)
    val currentInput by rememberUpdatedState(input)
    val coroutineScope = rememberCoroutineScope()
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Outlined.Science,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    stringResource(TDMR.strings.novel_test),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text(stringResource(TDMR.strings.novel_sample_input)) },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            FilledTonalButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = rule.pattern.isNotBlank() && !running,
                onClick = {
                    val sample = input
                    coroutineScope.launch {
                        running = true
                        try {
                            val output = withContext(Dispatchers.Default) { runCatching { rule.replace(sample) } }
                            if (currentRule == rule && currentInput == sample) result = output
                        } finally {
                            running = false
                        }
                    }
                },
            ) {
                if (running) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp).size(16.dp),
                        strokeWidth = 2.dp,
                    )
                }
                if (!running) {
                    Icon(
                        Icons.AutoMirrored.Outlined.FactCheck,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp).size(18.dp),
                    )
                }
                Text(stringResource(TDMR.strings.novel_run_test))
            }
            result?.let { output ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    color = if (output.isFailure) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    contentColor = if (output.isFailure) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                ) {
                    SelectionContainer {
                        Text(
                            text = output.fold(
                                onSuccess = { stringResource(TDMR.strings.novel_output_format, it) },
                                onFailure = {
                                    it.localizedMessage ?: stringResource(TDMR.strings.novel_invalid_regex)
                                },
                            ),
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}
