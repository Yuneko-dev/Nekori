package eu.kanade.presentation.reader.appbars

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.reader.setting.NovelPagePosition
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.components.material.Slider
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun NovelPagedProgressSlider(
    position: NovelPagePosition,
    onProgressChange: (Int) -> Unit,
    backgroundColor: Color,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val interactionSource = remember { MutableInteractionSource() }
    val sliderDragged by interactionSource.collectIsDraggedAsState()
    val unitCount = position.unitCount.coerceAtLeast(1)
    val lastUnitIndex = unitCount - 1
    val unitIndex = position.unitIndex.coerceIn(0, lastUnitIndex)
    val label = novelPagedProgressLabel(position)

    LaunchedEffect(unitIndex) {
        if (sliderDragged) {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(backgroundColor)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = label)
        Slider(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { stateDescription = label },
            value = unitIndex,
            valueRange = 0..lastUnitIndex.coerceAtLeast(1),
            steps = (unitCount - 2).coerceAtLeast(0),
            enabled = unitCount > 1,
            onValueChange = { newUnitIndex ->
                if (newUnitIndex != unitIndex) {
                    onProgressChange(newUnitIndex)
                }
            },
            colors = if (unitCount == 1) {
                SliderDefaults.colors(disabledActiveTrackColor = Color.Transparent)
            } else {
                SliderDefaults.colors()
            },
            interactionSource = interactionSource,
        )
    }
}

@Composable
internal fun novelPagedProgressLabel(position: NovelPagePosition): String =
    if (position.firstPage == position.lastPage) {
        stringResource(
            TDMR.strings.novel_page_progress_single,
            position.firstPage,
            position.totalPages,
        )
    } else {
        stringResource(
            TDMR.strings.novel_page_progress_range,
            position.firstPage,
            position.lastPage,
            position.totalPages,
        )
    }
