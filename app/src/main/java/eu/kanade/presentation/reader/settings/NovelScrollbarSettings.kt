package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.components.InlineSettingsChipRow
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

@Composable
internal fun ColumnScope.NovelScrollbarSettings(preferences: ReaderPreferences) {
    // Progress slider mode
    val showProgressSlider by preferences.novelShowProgressSlider.collectAsState()
    val showVerticalScrollbar by preferences.novelVerticalScrollbar.collectAsState()
    val verticalScrollbarPosition by preferences.novelVerticalScrollbarPosition.collectAsState()
    val scrollbarMode = when {
        !showProgressSlider -> "none"
        showVerticalScrollbar && verticalScrollbarPosition == "left" -> "vertical_left"
        showVerticalScrollbar && verticalScrollbarPosition == "right" -> "vertical_right"
        else -> "horizontal"
    }
    val scrollbarModeOptions = listOf(
        stringResource(MR.strings.none) to "none",
        stringResource(TDMR.strings.novel_scrollbar_horizontal) to "horizontal",
        stringResource(TDMR.strings.novel_vertical_scrollbar_left) to "vertical_left",
        stringResource(TDMR.strings.novel_vertical_scrollbar_right) to "vertical_right",
    )
    SettingsChipRow(TDMR.strings.pref_novel_scrollbar_mode) {
        scrollbarModeOptions.forEach { (label, value) ->
            FilterChip(
                selected = scrollbarMode == value,
                onClick = { preferences.setNovelScrollbarMode(value) },
                label = { Text(label) },
            )
        }
    }

    val verticalProgressSliderSize by preferences.novelVerticalProgressSliderSize.collectAsState()
    if (scrollbarMode == "vertical_left" || scrollbarMode == "vertical_right") {
        val verticalSizeOptions = listOf(
            stringResource(TDMR.strings.novel_vertical_progress_slider_half) to "half",
            stringResource(TDMR.strings.novel_vertical_progress_slider_full) to "full",
        )
        InlineSettingsChipRow(TDMR.strings.pref_novel_vertical_progress_slider_size) {
            verticalSizeOptions.forEach { (label, value) ->
                FilterChip(
                    selected = verticalProgressSliderSize == value,
                    onClick = { preferences.novelVerticalProgressSliderSize.set(value) },
                    label = { Text(label) },
                )
            }
        }
    }
}

internal fun ReaderPreferences.setNovelScrollbarMode(mode: String) {
    when (mode) {
        "none", "horizontal" -> {
            novelShowProgressSlider.set(mode != "none")
            novelVerticalScrollbar.set(false)
        }
        "vertical_left", "vertical_right" -> {
            novelVerticalScrollbarPosition.set(mode.removePrefix("vertical_"))
            novelVerticalScrollbar.set(true)
            novelShowProgressSlider.set(true)
        }
    }
}
