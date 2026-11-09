package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.more.settings.widget.ListPreferenceWidget
import eu.kanade.tachiyomi.data.font.FontManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
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
    onSelect: (String) -> Unit,
) {
    val options = rememberNovelFontOptions(selected)
    val selectedOption = options.find { it.value == selected }
    val unavailableLabel = stringResource(TDMR.strings.settings_font_manager_font_unavailable)
    ListPreferenceWidget(
        value = selected,
        title = title,
        subtitle = null,
        icon = null,
        entries = options.associate {
            it.value to if (it.fontFamily == null) "${it.label} ($unavailableLabel)" else it.label
        },
        entryTextStyle = { value ->
            MaterialTheme.typography.bodyLarge.copy(fontFamily = options.find { it.value == value }?.fontFamily)
        },
        isEntryEnabled = { value -> options.find { it.value == value }?.fontFamily != null },
        onValueChange = onSelect,
        preferenceContent = { openDialog ->
            Row(
                modifier = Modifier
                    .clickable(onClick = openDialog)
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
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).padding(start = 8.dp),
                )
            }
        },
    )
}
