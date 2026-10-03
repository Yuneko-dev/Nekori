package eu.kanade.presentation.manga.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.domain.manga.model.WordDensity
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import java.text.NumberFormat

/**
 * Shows the progress and outcome of counting a novel's downloaded chapters.
 *
 * @param result null while counting is still running.
 */
@Composable
fun WordCountDialog(
    checkedChapters: Int,
    chaptersToCount: Int,
    result: WordDensity?,
    onDismissRequest: () -> Unit,
) {
    val isCounting = result == null && chaptersToCount > 0
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                val label = if (isCounting) MR.strings.action_cancel else MR.strings.action_ok
                Text(text = stringResource(label))
            }
        },
        title = { Text(text = stringResource(TDMR.strings.action_word_count)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                when {
                    chaptersToCount == 0 -> Text(text = stringResource(TDMR.strings.word_count_no_downloads))
                    result == null -> CountingProgress(checkedChapters, chaptersToCount)
                    result.countedChapters == 0 -> Text(text = stringResource(TDMR.strings.word_count_unreadable))
                    else -> WordCountResult(result)
                }
            }
        },
    )
}

@Composable
private fun CountingProgress(checkedChapters: Int, chaptersToCount: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium)) {
        Text(text = stringResource(TDMR.strings.word_count_progress, checkedChapters, chaptersToCount))
        LinearProgressIndicator(
            progress = { checkedChapters.toFloat() / chaptersToCount },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun WordCountResult(result: WordDensity) {
    val numberFormat = remember { NumberFormat.getIntegerInstance() }
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
        StatRow(
            label = stringResource(TDMR.strings.word_count_total_words),
            value = numberFormat.format(result.totalWords),
        )
        StatRow(
            label = stringResource(TDMR.strings.word_count_average_words),
            value = numberFormat.format(result.averageWords),
        )
        StatRow(
            label = stringResource(TDMR.strings.word_count_chapters_counted),
            value = stringResource(
                TDMR.strings.word_count_chapters_counted_value,
                numberFormat.format(result.countedChapters),
                numberFormat.format(result.totalChapters),
            ),
        )
        if (result.notDownloadedChapters > 0) {
            CoverageNote(
                text = stringResource(
                    TDMR.strings.word_count_not_downloaded,
                    numberFormat.format(result.notDownloadedChapters),
                    numberFormat.format(result.totalChapters),
                ),
            )
        }
        if (result.unreadableChapters > 0) {
            CoverageNote(
                text = stringResource(
                    TDMR.strings.word_count_unreadable_some,
                    numberFormat.format(result.unreadableChapters),
                    numberFormat.format(result.totalChapters),
                ),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WordDensityBadge(tier = result.tier)
            Text(
                text = wordDensityTierDescription(result.tier, numberFormat),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun CoverageNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium),
    ) {
        Text(text = label, modifier = Modifier.weight(1f))
        Text(text = value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun WordDensityBadge(tier: Int) {
    val description = stringResource(TDMR.strings.word_density_indicator, tier)
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Box(
            modifier = Modifier.defaultMinSize(minWidth = 40.dp, minHeight = 40.dp)
                .padding(MaterialTheme.padding.small),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = tier.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun wordDensityTierDescription(tier: Int, numberFormat: NumberFormat): String {
    val maxWords = WordDensity.tierMaxWords(tier)
    return if (maxWords == null) {
        stringResource(
            TDMR.strings.word_density_indicator_range_top,
            tier,
            WordDensity.MAX_TIER,
            numberFormat.format(WordDensity.tierMinWords(tier) - 1),
        )
    } else {
        stringResource(
            TDMR.strings.word_density_indicator_range,
            tier,
            WordDensity.MAX_TIER,
            numberFormat.format(WordDensity.tierMinWords(tier)),
            numberFormat.format(maxWords),
        )
    }
}
