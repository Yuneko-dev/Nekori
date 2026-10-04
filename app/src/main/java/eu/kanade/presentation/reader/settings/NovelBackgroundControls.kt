package eu.kanade.presentation.reader.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.ui.reader.setting.NovelBackgroundSettings
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun backgroundControlPreferences(
    settings: NovelBackgroundSettings,
    onChange: (NovelBackgroundSettings) -> Unit,
): List<Preference> = buildList {
    add(
        Preference.PreferenceItem.BasicListPreference(
            title = stringResource(TDMR.strings.novel_background_mode),
            value = settings.mode,
            entries = mapOf(
                "fill" to stringResource(TDMR.strings.novel_background_fill),
                "fit" to stringResource(TDMR.strings.novel_background_fit),
                "stretch" to stringResource(TDMR.strings.novel_background_stretch),
                "tile" to stringResource(TDMR.strings.novel_background_tile),
                "center" to stringResource(TDMR.strings.novel_background_center),
            ),
            onValueChanged = { onChange(settings.withMode(it)) },
        ),
    )
    if (settings.mode == "fill" || settings.mode == "fit") {
        add(
            Preference.PreferenceItem.BasicListPreference(
                title = stringResource(TDMR.strings.novel_background_position),
                value = settings.position,
                entries = mapOf(
                    "center" to stringResource(TDMR.strings.novel_background_center),
                    "top" to stringResource(TDMR.strings.novel_background_top),
                    "bottom" to stringResource(TDMR.strings.novel_background_bottom),
                    "left" to stringResource(TDMR.strings.novel_background_left),
                    "right" to stringResource(TDMR.strings.novel_background_right),
                ),
                onValueChanged = { onChange(settings.copy(position = it)) },
            ),
        )
    }
    add(
        Preference.PreferenceItem.SliderPreference(
            title = stringResource(TDMR.strings.novel_background_opacity),
            value = settings.opacity,
            valueRange = 0..100,
            valueString = settings.opacity.toString() + "%",
            onValueChanged = { onChange(settings.copy(opacity = it)) },
        ),
    )
    add(
        Preference.PreferenceItem.SliderPreference(
            title = stringResource(TDMR.strings.novel_background_blur),
            value = settings.blur,
            valueRange = 0..20,
            valueString = settings.blur.toString() + "px",
            onValueChanged = { onChange(settings.copy(blur = it)) },
        ),
    )
}

@Composable
internal fun NovelBackgroundEditDialog(fileName: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember(fileName) { mutableStateOf(fileName.substringBeforeLast('.')) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(TDMR.strings.novel_background_rename)) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name) }) {
                Text(stringResource(MR.strings.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_cancel)) } },
    )
}

@Composable
internal fun NovelBackgroundDeleteDialog(fileName: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.action_delete)) },
        text = { Text(stringResource(TDMR.strings.novel_background_delete_confirmation, fileName)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(MR.strings.action_delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_cancel)) } },
    )
}
