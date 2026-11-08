package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

// Presentation order is independent of the persisted navigation IDs.
internal val novelTapZoneOptions = ReaderPreferences.TapZones.mapIndexed { index, label -> index to label }
    .filterNot { it.first == ReaderPreferences.TAPZONE_DISABLED_INDEX } + listOf(
    ReaderPreferences.TAPZONE_CENTER_INDEX to TDMR.strings.novel_nav_center_only,
    ReaderPreferences.TAPZONE_CENTER_LARGE_INDEX to TDMR.strings.novel_nav_center_large,
    ReaderPreferences.TAPZONE_BOTTOM_INDEX to TDMR.strings.novel_status_bar_position_bottom,
    ReaderPreferences.TAPZONE_DISABLED_INDEX to ReaderPreferences.TapZones[ReaderPreferences.TAPZONE_DISABLED_INDEX],
)

internal fun novelTapZoneInvertOptions(
    mode: Int,
): List<Pair<ReaderPreferences.TappingInvertMode, StringResource>> = when (mode) {
    ReaderPreferences.TAPZONE_DISABLED_INDEX,
    ReaderPreferences.TAPZONE_CENTER_INDEX,
    ReaderPreferences.TAPZONE_CENTER_LARGE_INDEX,
    -> emptyList()
    ReaderPreferences.TAPZONE_BOTTOM_INDEX -> listOf(
        ReaderPreferences.TappingInvertMode.NONE to TDMR.strings.novel_status_bar_position_bottom,
        ReaderPreferences.TappingInvertMode.VERTICAL to TDMR.strings.novel_status_bar_position_top,
    )
    else -> ReaderPreferences.TappingInvertMode.entries.map { it to it.titleRes }
}

@Composable
internal fun ColumnScope.NovelTapZoneSettings(preferences: ReaderPreferences) {
    // Tap-zone navigation settings for novel viewer
    val navigationModeNovel by preferences.navigationModeNovel.collectAsState()
    val novelNavInverted by preferences.novelNavInverted.collectAsState()
    SettingsChipRow(MR.strings.pref_viewer_nav) {
        novelTapZoneOptions.forEach { (index, label) ->
            FilterChip(
                selected = navigationModeNovel == index,
                onClick = { preferences.navigationModeNovel.set(index) },
                label = { Text(stringResource(label)) },
            )
        }
    }

    val invertOptions = novelTapZoneInvertOptions(navigationModeNovel)
    if (invertOptions.isNotEmpty()) {
        SettingsChipRow(MR.strings.pref_read_with_tapping_inverted) {
            invertOptions.forEach { (entry, label) ->
                FilterChip(
                    selected = entry == novelNavInverted,
                    onClick = { preferences.novelNavInverted.set(entry) },
                    label = { Text(stringResource(label)) },
                )
            }
        }
    }

    if (navigationModeNovel == ReaderPreferences.TAPZONE_BOTTOM_INDEX) {
        val bottomZoneHeight by preferences.novelBottomZoneHeight.collectAsState()
        SliderItem(
            label = stringResource(TDMR.strings.novel_nav_zone_height),
            value = bottomZoneHeight,
            valueRange = 5..50,
            valueString = "$bottomZoneHeight%",
            onChange = { preferences.novelBottomZoneHeight.set(it) },
        )
    }
}
