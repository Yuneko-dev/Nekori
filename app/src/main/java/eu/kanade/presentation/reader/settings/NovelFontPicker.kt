package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.data.font.FontManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

internal data class NovelFontOption(
    val label: String,
    val value: String,
    val fontFamily: FontFamily?,
)

@Composable
internal fun rememberNovelFontOptions(selected: String): List<NovelFontOption> {
    val fontManager = remember { Injekt.get<FontManager>() }
    val systemFonts = fontManager.getSystemFonts().map { font ->
        NovelFontOption(font.name, font.path, fontManager.getTypeface(font)?.let(::FontFamily))
    }
    val customFonts by produceState<List<NovelFontOption>?>(null, fontManager) {
        value = withContext(Dispatchers.IO) {
            fontManager.getInstalledFonts().map { font ->
                NovelFontOption(
                    label = font.name,
                    value = font.path,
                    fontFamily = fontManager.getTypeface(font)?.let(::FontFamily),
                )
            }
        }
    }

    val options = systemFonts + customFonts.orEmpty()
    return if (customFonts != null && options.none { it.value == selected }) {
        options + NovelFontOption(selected.substringAfterLast('/'), selected, null)
    } else {
        options
    }
}

@Composable
internal fun NovelFontSelectItem(
    title: String,
    selected: String,
    defaultValue: String,
    onSelect: (String) -> Unit,
) {
    val options = rememberNovelFontOptions(selected)
    val selectedOption = options.find { it.value == selected }
    var showDialog by remember { mutableStateOf(false) }

    if (showDialog) {
        NovelFontPickerDialog(
            title = title,
            options = options,
            selected = selected,
            defaultValue = defaultValue,
            onSelect = {
                onSelect(it)
                showDialog = false
            },
            onDismissRequest = { showDialog = false },
        )
    }

    Row(
        modifier = Modifier
            .clickable { showDialog = true }
            .fillMaxWidth()
            .padding(
                horizontal = SettingsItemsPaddings.Horizontal,
                vertical = SettingsItemsPaddings.Vertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (selectedOption != null && selectedOption.fontFamily == null) {
                stringResource(TDMR.strings.settings_font_manager_font_unavailable)
            } else {
                selectedOption?.label ?: stringResource(MR.strings.loading)
            },
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = selectedOption?.fontFamily,
            ),
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
internal fun NovelFontPickerDialog(
    title: String,
    options: List<NovelFontOption>,
    selected: String,
    defaultValue: String? = null,
    onSelect: (String) -> Unit,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(title) },
        text = {
            Box {
                val state = androidx.compose.foundation.lazy.rememberLazyListState()
                ScrollbarLazyColumn(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .selectableGroup(),
                    state = state,
                ) {
                    items(
                        count = options.size,
                        key = { options[it].value },
                    ) { index ->
                        val option = options[index]
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(MaterialTheme.shapes.small)
                                .selectable(
                                    selected = selected == option.value,
                                    enabled = option.fontFamily != null,
                                    role = Role.RadioButton,
                                    onClick = { onSelect(option.value) },
                                )
                                .fillMaxWidth()
                                .minimumInteractiveComponentSize(),
                        ) {
                            RadioButton(
                                selected = selected == option.value,
                                enabled = option.fontFamily != null,
                                onClick = null,
                            )
                            Column(modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp)) {
                                Text(text = option.label, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = stringResource(
                                        if (option.fontFamily != null) {
                                            TDMR.strings.settings_font_manager_preview_sample
                                        } else {
                                            TDMR.strings.settings_font_manager_font_unavailable
                                        },
                                    ),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = option.fontFamily),
                                    color = if (option.fontFamily != null) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                )
                            }
                        }
                    }
                }
                if (state.canScrollBackward) {
                    HorizontalDivider(modifier = Modifier.align(Alignment.TopCenter))
                }
                if (state.canScrollForward) {
                    HorizontalDivider(modifier = Modifier.align(Alignment.BottomCenter))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
        dismissButton = if (defaultValue != null) {
            {
                TextButton(onClick = { onSelect(defaultValue) }) {
                    Text(stringResource(MR.strings.action_reset))
                }
            }
        } else {
            null
        },
    )
}
