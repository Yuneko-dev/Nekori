package eu.kanade.presentation.more.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.more.stats.components.StatsItem
import eu.kanade.presentation.more.stats.data.StatsData
import eu.kanade.tachiyomi.source.model.SManga
import tachiyomi.domain.history.model.MangaReadStats
import tachiyomi.domain.history.model.ReadingSessionWithRelations
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.components.SectionCard
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun AdvancedStatsScreenContent(
    state: StatsScreenState.Success,
    paddingValues: PaddingValues,
    onSelectYear: (Int) -> Unit,
    onOpenManga: (Long) -> Unit,
    onRefreshStorage: () -> Unit,
) {
    val advanced = state.advanced ?: return
    var selectedDay by remember { mutableStateOf<HeatDay?>(null) }
    var selectedManga by remember { mutableStateOf<Pair<Int, MangaReadStats>?>(null) }

    LazyColumn(
        contentPadding = paddingValues,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        item { AdvancedOverviewSection(state) }
        item { ReadingRhythmSection(advanced.recentSessions) }
        item {
            ReadingHistorySection(
                data = advanced,
                onSelectYear = onSelectYear,
                onSelectDay = { selectedDay = it },
            )
        }
        item { LibrarySection(state) }
        if (state.storage != null || state.storageLoading || state.storageError) {
            item {
                StorageSection(
                    data = state.storage,
                    loading = state.storageLoading,
                    error = state.storageError,
                    onRefresh = onRefreshStorage,
                )
            }
        }
        item { PublicationStatusSection(state.titles.publicationStatusCounts) }
        item {
            MostReadSection(advanced.mostReadManga) { rank, manga ->
                selectedManga = rank to manga
            }
        }
        item { TrackerStats(state.trackers) }
    }

    selectedDay?.let { day ->
        DayDetailsSheet(day = day, onDismiss = { selectedDay = null })
    }
    selectedManga?.let { (rank, manga) ->
        MostReadDetailsSheet(
            rank = rank,
            manga = manga,
            recentSessions = advanced.recentSessions,
            onOpenManga = {
                selectedManga = null
                onOpenManga(manga.mangaId)
            },
            onDismiss = { selectedManga = null },
        )
    }
}

@Composable
private fun LazyItemScope.AdvancedOverviewSection(state: StatsScreenState.Success) {
    val reading = (state.titles.startedMangaCount - state.overview.completedMangaCount).coerceAtLeast(0)

    SectionCard(MR.strings.label_overview_section) {
        Text(
            text = stringResource(MR.strings.label_read_duration),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = exactDuration(state.overview.totalReadDuration),
            modifier = Modifier.padding(top = MaterialTheme.padding.extraSmall),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(TDMR.strings.stats_chapter_duration_source),
            modifier = Modifier.padding(top = MaterialTheme.padding.extraSmall),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = MaterialTheme.padding.medium))
        Row(modifier = Modifier.fillMaxWidth()) {
            StatsItem(
                title = formatCount(state.overview.libraryMangaCount.toLong()),
                subtitle = stringResource(MR.strings.in_library),
            )
            StatsItem(
                title = formatCount(reading.toLong()),
                subtitle = stringResource(MR.strings.reading),
            )
            StatsItem(
                title = formatCount(state.overview.completedMangaCount.toLong()),
                subtitle = stringResource(MR.strings.label_completed_titles),
            )
        }
    }
}

private enum class ReadingRange { WEEK, MONTH, YEAR }

private data class ReadingBucket(val label: String, val duration: Long)

@Composable
private fun LazyItemScope.ReadingRhythmSection(sessions: List<ReadingSessionWithRelations>) {
    var range by remember { mutableStateOf(ReadingRange.WEEK) }
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }
    val weekdayLabels = shortWeekdayLabels()
    val monthLabels = shortMonthLabels()
    val buckets = remember(range, sessions, zone, today, weekdayLabels, monthLabels) {
        buildReadingBuckets(range, sessions, today, zone, weekdayLabels, monthLabels)
    }
    var selectedBucket by remember(range) { mutableIntStateOf(buckets.lastIndex) }
    val labels = listOf(
        ReadingRange.WEEK to stringResource(TDMR.strings.stats_last_7_days),
        ReadingRange.MONTH to stringResource(TDMR.strings.stats_last_30_days),
        ReadingRange.YEAR to stringResource(TDMR.strings.stats_last_12_months),
    )

    SectionCard(TDMR.strings.stats_reading_rhythm) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            labels.forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = range == value,
                    onClick = { range = value },
                    shape = SegmentedButtonDefaults.itemShape(index, labels.size),
                    icon = {},
                ) {
                    Text(label)
                }
            }
        }

        val selected = buckets.getOrNull(selectedBucket)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = MaterialTheme.padding.medium),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(TDMR.strings.stats_detailed_sessions),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = selected?.let { "${it.label} · ${exactDuration(it.duration)}" }.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (buckets.all { it.duration == 0L }) {
            Text(
                text = stringResource(TDMR.strings.stats_no_session_data),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = MaterialTheme.padding.extraLarge),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        } else {
            ReadingBarChart(
                buckets = buckets,
                selectedIndex = selectedBucket,
                onSelect = { selectedBucket = it },
            )
        }
    }
}

@Composable
private fun ReadingBarChart(
    buckets: List<ReadingBucket>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val maximum = max(1L, buckets.maxOfOrNull { it.duration } ?: 1L)
    val dense = buckets.size > 7
    val scrollState = rememberScrollState(Int.MAX_VALUE)
    LaunchedEffect(scrollState.maxValue) {
        scrollState.scrollTo(scrollState.maxValue)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (dense) Modifier.horizontalScroll(scrollState) else Modifier),
    ) {
        Row(
            modifier = Modifier
                .then(if (dense) Modifier.width((buckets.size * 48).dp) else Modifier.fillMaxWidth())
                .height(148.dp)
                .padding(top = MaterialTheme.padding.medium),
            horizontalArrangement = Arrangement.spacedBy(if (dense) 3.dp else MaterialTheme.padding.small),
            verticalAlignment = Alignment.Bottom,
        ) {
            buckets.forEachIndexed { index, bucket ->
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clip(MaterialTheme.shapes.small)
                        .clickable { onSelect(index) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .widthIn(max = if (dense) 15.dp else 24.dp),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 6.dp)
                                .fillMaxHeight(fraction = (bucket.duration.toFloat() / maximum).coerceAtLeast(0.06f))
                                .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                                .background(
                                    if (index == selectedIndex) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.secondary
                                    },
                                ),
                        )
                    }
                    Text(
                        text = bucket.label,
                        modifier = Modifier.padding(top = MaterialTheme.padding.small),
                        style = if (dense) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
                        color = if (index == selectedIndex) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun LazyItemScope.ReadingHistorySection(
    data: StatsData.Advanced,
    onSelectYear: (Int) -> Unit,
    onSelectDay: (HeatDay) -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    val days = remember(data.selectedYear, data.yearSessions, zone) {
        buildHeatmap(data.selectedYear, data.yearSessions, zone)
    }
    val visibleDays = days.filter { it.inYear && !it.future }
    val activeDays = visibleDays.count { it.duration > 0 }
    val longestStreak = remember(days) { longestStreak(days) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.padding.extraLarge),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(TDMR.strings.stats_reading_history),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
        )
        YearPicker(data.selectedYear, data.availableYears, onSelectYear)
    }

    SectionCard {
        Row(modifier = Modifier.fillMaxWidth()) {
            StatsItem(
                title = exactDuration(visibleDays.sumOf { it.duration }),
                subtitle = stringResource(TDMR.strings.stats_sessions_in_year),
            )
            StatsItem(
                title = stringResource(TDMR.strings.stats_days_count, activeDays),
                subtitle = stringResource(TDMR.strings.stats_active_days),
            )
            StatsItem(
                title = stringResource(TDMR.strings.stats_days_count, longestStreak),
                subtitle = stringResource(TDMR.strings.stats_longest_streak),
            )
        }
        Heatmap(days, onSelectDay)
        HorizontalDivider(modifier = Modifier.padding(top = MaterialTheme.padding.medium))
        DataNote(
            text = stringResource(TDMR.strings.stats_tap_day_hint),
            modifier = Modifier.padding(top = MaterialTheme.padding.medium),
        ) {
            HeatLegend()
        }
    }
}

@Composable
private fun YearPicker(selectedYear: Int, years: List<Int>, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { expanded = true },
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(selectedYear.toString(), style = MaterialTheme.typography.labelLarge)
                Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            years.forEach { year ->
                DropdownMenuItem(
                    text = { Text(year.toString()) },
                    onClick = {
                        expanded = false
                        onSelect(year)
                    },
                )
            }
        }
    }
}

internal data class HeatDay(
    val date: LocalDate,
    val inYear: Boolean,
    val future: Boolean,
    val sessions: List<ReadingSessionWithRelations>,
) {
    val duration = sessions.sumOf { it.readDuration }
}

internal data class ReadingHeatmapScale(
    val quartiles: List<Double>,
    val maximum: Long,
)

private const val HEAT_ACTIVE_LEVEL_COUNT = 4
private const val HEAT_MIN_INTENSITY = 0.3f

@Composable
private fun Heatmap(days: List<HeatDay>, onSelectDay: (HeatDay) -> Unit) {
    val cellSize = 16.dp
    val cellGap = 4.dp
    val weekWidth = cellSize + cellGap
    val headerHeight = 20.dp
    val monthLabelWidth = 40.dp
    val weeks = remember(days) { days.chunked(7) }
    val dayFormatter = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG) }
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val weekdayLabels = shortWeekdayLabels()
    val monthLabels = shortMonthLabels()
    val scale = remember(days) { readingHeatmapScale(eligibleHeatDurations(days)) }
    val monthStarts = remember(days) {
        (1..12).mapNotNull { month ->
            days.indexOfFirst { it.inYear && it.date.monthValue == month && it.date.dayOfMonth == 1 }
                .takeIf { it >= 0 }
                ?.let { it / 7 to month }
        }
    }
    LaunchedEffect(scrollState.maxValue, scrollState.viewportSize, days, density) {
        val latestWeek = days.indexOfLast { it.inYear && !it.future }.coerceAtLeast(0) / 7
        val latestOffset = with(density) { (weekWidth * latestWeek).roundToPx() }
        val trailingSpace = with(density) { 42.dp.roundToPx() }
        scrollState.scrollTo(
            (latestOffset - scrollState.viewportSize + trailingSpace).coerceIn(0, scrollState.maxValue),
        )
    }
    Row(modifier = Modifier.padding(top = MaterialTheme.padding.large)) {
        Column(
            modifier = Modifier.padding(top = headerHeight, end = 5.dp),
            verticalArrangement = Arrangement.spacedBy(cellGap),
        ) {
            weekdayLabels.forEachIndexed { index, weekday ->
                val label = weekday.takeIf { index % 2 == 0 || index == 6 }.orEmpty()
                Box(
                    modifier = Modifier.width(20.dp).height(cellSize),
                    contentAlignment = Alignment.Center,
                ) {
                    val labelStyle = MaterialTheme.typography.labelSmall
                    Text(label, style = labelStyle.copy(lineHeight = labelStyle.fontSize))
                }
            }
        }
        Column(modifier = Modifier.horizontalScroll(scrollState)) {
            Box(modifier = Modifier.width(weekWidth * weeks.size + monthLabelWidth).height(headerHeight)) {
                monthStarts.forEach { (weekIndex, month) ->
                    Box(
                        modifier = Modifier
                            .offset(x = weekWidth * weekIndex)
                            .width(monthLabelWidth)
                            .height(headerHeight),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            text = monthLabels[month - 1],
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(cellGap)) {
                weeks.forEach { week ->
                    Column(verticalArrangement = Arrangement.spacedBy(cellGap)) {
                        week.forEach { day ->
                            val description = "${dayFormatter.format(day.date)}: ${exactDuration(day.duration)}"
                            Box(
                                modifier = Modifier
                                    .size(cellSize)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(heatColor(day, scale))
                                    .semantics { contentDescription = description }
                                    .clickable(
                                        enabled = day.inYear && !day.future,
                                        onClick = { onSelectDay(day) },
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun heatColor(day: HeatDay, scale: ReadingHeatmapScale): Color {
    if (!day.inYear) return Color.Transparent
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    val primary = MaterialTheme.colorScheme.primary
    if (day.future || day.duration == 0L) return empty
    return lerp(empty, primary, heatIntensity(heatLevel(day.duration, scale)))
}

@Composable
private fun HeatLegend() {
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    val primary = MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(stringResource(TDMR.strings.stats_less), style = MaterialTheme.typography.labelSmall)
        Row(
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(empty),
            )
            repeat(HEAT_ACTIVE_LEVEL_COUNT) { level ->
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(lerp(empty, primary, heatIntensity(level + 1))),
                )
            }
        }
        Text(stringResource(MR.strings.label_more), style = MaterialTheme.typography.labelSmall)
    }
}

internal fun eligibleHeatDurations(days: List<HeatDay>): List<Long> = days
    .filter { it.inYear && !it.future && it.duration > 0 }
    .map { it.duration }

internal fun readingHeatmapScale(durations: List<Long>): ReadingHeatmapScale {
    val sorted = durations.sorted()
    if (sorted.isEmpty()) return ReadingHeatmapScale(emptyList(), 0L)
    val quartiles = listOf(0.25, 0.5, 0.75).map { percentile ->
        val index = sorted.lastIndex * percentile
        val lower = index.toInt()
        val upper = minOf(lower + 1, sorted.lastIndex)
        val fraction = index - lower
        sorted[lower].toDouble() + (sorted[upper].toDouble() - sorted[lower]) * fraction
    }
    return ReadingHeatmapScale(quartiles, sorted.last())
}

internal fun heatLevel(duration: Long, scale: ReadingHeatmapScale): Int {
    if (duration <= 0 || scale.maximum <= 0) return 0
    if (duration >= scale.maximum) return HEAT_ACTIVE_LEVEL_COUNT
    return scale.quartiles.indexOfFirst { duration <= it }
        .let { index -> if (index < 0) HEAT_ACTIVE_LEVEL_COUNT else index + 1 }
}

internal fun heatIntensity(level: Int): Float =
    HEAT_MIN_INTENSITY + (level - 1).toFloat() / (HEAT_ACTIVE_LEVEL_COUNT - 1) * (1f - HEAT_MIN_INTENSITY)

@Composable
private fun LazyItemScope.LibrarySection(state: StatsScreenState.Success) {
    val total = state.chapters.totalChapterCount
    val read = state.chapters.readChapterCount
    val progress = if (total == 0) 0f else (read.toFloat() / total).coerceIn(0f, 1f)
    val progressPercent = (progress * 100).roundToInt()

    Text(
        text = stringResource(TDMR.strings.stats_library_yours),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.padding.extraLarge),
        style = MaterialTheme.typography.titleSmall,
    )
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(92.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxSize(),
                    strokeWidth = 9.dp,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$progressPercent%", style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(TDMR.strings.stats_read_progress),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(modifier = Modifier.padding(start = MaterialTheme.padding.large)) {
                Text(
                    text = stringResource(
                        TDMR.strings.stats_chapters_progress,
                        formatCount(read.toLong()),
                        formatCount(total.toLong()),
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(
                        TDMR.strings.stats_chapters_remaining,
                        formatCount((total - read).coerceAtLeast(0).toLong()),
                    ),
                    modifier = Modifier.padding(top = MaterialTheme.padding.small),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${formatCount(state.chapters.downloadCount.toLong())} " +
                        stringResource(MR.strings.downloaded_chapters),
                    modifier = Modifier.padding(top = MaterialTheme.padding.extraSmall),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = MaterialTheme.padding.large),
        ) {
            StatsItem(
                title = formatCount(state.titles.globalUpdateItemCount.toLong()),
                subtitle = stringResource(MR.strings.label_titles_in_global_update),
            )
            StatsItem(
                title = formatCount(state.titles.startedMangaCount.toLong()),
                subtitle = stringResource(MR.strings.label_started),
            )
            StatsItem(
                title = formatCount(state.titles.localMangaCount.toLong()),
                subtitle = stringResource(MR.strings.label_local),
            )
        }
    }
}

private data class PublicationStatusItem(
    val status: Int,
    val label: StringResource,
    val count: Int,
    val color: Color,
)

@Composable
private fun LazyItemScope.PublicationStatusSection(statusCounts: Map<Long, Int>) {
    val colorScheme = MaterialTheme.colorScheme
    fun status(status: Int, label: StringResource, color: Color) = PublicationStatusItem(
        status = status,
        label = label,
        count = statusCounts[status.toLong()] ?: 0,
        color = color,
    )

    val statuses = listOf(
        status(SManga.ONGOING, MR.strings.ongoing, colorScheme.primary),
        status(SManga.COMPLETED, MR.strings.completed, colorScheme.tertiary),
        status(SManga.ON_HIATUS, MR.strings.on_hiatus, colorScheme.secondary),
        status(SManga.LICENSED, MR.strings.licensed, colorScheme.inversePrimary),
        status(SManga.PUBLISHING_FINISHED, MR.strings.publishing_finished, colorScheme.onTertiaryContainer),
        status(SManga.CANCELLED, MR.strings.cancelled, colorScheme.error),
        status(SManga.UNKNOWN, MR.strings.unknown, colorScheme.outline),
    ).filter { it.count > 0 }
    val total = statuses.sumOf { it.count }
    var selectedStatus by remember(statusCounts) {
        mutableIntStateOf(statuses.firstOrNull()?.status ?: SManga.UNKNOWN)
    }
    val selected = statuses.firstOrNull { it.status == selectedStatus } ?: statuses.firstOrNull()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.padding.extraLarge),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(TDMR.strings.stats_publication_status),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(TDMR.strings.stats_titles_count, formatCount(total.toLong())),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    SectionCard {
        if (selected == null) {
            Text(
                text = stringResource(MR.strings.information_empty_library),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = MaterialTheme.padding.large),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            return@SectionCard
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    text = formatCount(selected.count.toLong()),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(selected.label),
                    modifier = Modifier.padding(top = MaterialTheme.padding.extraSmall),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                text = "${selected.count * 100 / total}%",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = MaterialTheme.padding.medium)
                .height(10.dp)
                .clip(CircleShape),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            statuses.forEach { status ->
                Spacer(
                    modifier = Modifier
                        .weight(status.count.toFloat())
                        .fillMaxHeight()
                        .background(status.color),
                )
            }
        }

        Column(
            modifier = Modifier.padding(top = MaterialTheme.padding.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
        ) {
            statuses.chunked(2).forEach { rowStatuses ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                ) {
                    rowStatuses.forEach { status ->
                        Surface(
                            onClick = { selectedStatus = status.status },
                            modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.large,
                            color = if (status.status == selected.status) {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            } else {
                                Color.Transparent
                            },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(status.color),
                                )
                                Text(
                                    text = stringResource(status.label),
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = MaterialTheme.padding.small),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = formatCount(status.count.toLong()),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        }
                    }
                    if (rowStatuses.size == 1) Spacer(modifier = Modifier.weight(1f))
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(top = MaterialTheme.padding.medium))
        DataNote(
            text = stringResource(TDMR.strings.stats_publication_status_note),
            modifier = Modifier.padding(top = MaterialTheme.padding.medium),
        )
    }
}

@Composable
private fun LazyItemScope.MostReadSection(
    items: List<MangaReadStats>,
    onSelectManga: (Int, MangaReadStats) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val visibleItems = if (expanded) items else items.take(3)
    val topReadDuration = items.firstOrNull()?.readDuration ?: 0L

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.padding.extraLarge),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(TDMR.strings.stats_most_read),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
        )
        if (items.size > 3) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(stringResource(if (expanded) TDMR.strings.stats_collapse else TDMR.strings.stats_top_20))
            }
        }
    }
    SectionCard {
        visibleItems.forEachIndexed { index, item ->
            val progress = if (topReadDuration <= 0L) {
                0f
            } else {
                (item.readDuration.toFloat() / topReadDuration).coerceIn(0f, 1f)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.large)
                    .clickable { onSelectManga(index + 1, item) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = (index + 1).toString(),
                    modifier = Modifier.width(28.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                MangaCover.Book(
                    data = item.coverData,
                    modifier = Modifier.width(44.dp),
                    contentDescription = item.title,
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = MaterialTheme.padding.medium),
                ) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${exactDuration(item.readDuration)} · " +
                            stringResource(
                                TDMR.strings.stats_chapters_count,
                                formatCount(item.chapterCount),
                            ),
                        modifier = Modifier.padding(top = MaterialTheme.padding.extraSmall),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = MaterialTheme.padding.small)
                            .height(4.dp)
                            .clip(CircleShape),
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
            }
            if (index != visibleItems.lastIndex) {
                HorizontalDivider(modifier = Modifier.padding(start = 84.dp))
            }
        }
    }
}

@Composable
private fun DayDetailsSheet(day: HeatDay, onDismiss: () -> Unit) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.PartiallyExpanded, SheetValue.Expanded),
    )
    val dateFormatter = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL) }
    val timeFormatter = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }
    val zone = remember { ZoneId.systemDefault() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp),
            contentPadding = PaddingValues(bottom = MaterialTheme.padding.extraLarge),
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MaterialTheme.padding.large),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(TDMR.strings.stats_day_details),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = dateFormatter.format(day.date),
                            modifier = Modifier.padding(top = MaterialTheme.padding.extraSmall),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    FilledTonalIconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(MR.strings.action_close),
                        )
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MaterialTheme.padding.large, vertical = MaterialTheme.padding.large),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                ) {
                    DetailMetric(
                        modifier = Modifier.weight(1.2f),
                        value = exactDuration(day.duration),
                        label = stringResource(MR.strings.label_read_duration),
                    )
                    DetailMetric(
                        modifier = Modifier.weight(0.8f),
                        value = formatCount(day.sessions.size.toLong()),
                        label = stringResource(TDMR.strings.stats_sessions),
                    )
                    DetailMetric(
                        modifier = Modifier.weight(0.8f),
                        value = formatCount(day.sessions.distinctBy { it.chapterId }.size.toLong()),
                        label = stringResource(MR.strings.chapters),
                    )
                }
            }
            if (day.sessions.isEmpty()) {
                item {
                    Text(
                        text = stringResource(TDMR.strings.stats_no_sessions_day),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MaterialTheme.padding.extraLarge),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(day.sessions, key = { it.id }) { session ->
                    ReadingSessionTimelineItem(
                        session = session,
                        timeFormatter = timeFormatter,
                        zone = zone,
                        isLast = session.id == day.sessions.last().id,
                    )
                }
            }
            item {
                DataNote(
                    text = stringResource(TDMR.strings.stats_session_data_note),
                    modifier = Modifier.padding(
                        start = MaterialTheme.padding.large,
                        top = MaterialTheme.padding.small,
                        end = MaterialTheme.padding.large,
                    ),
                )
            }
        }
    }
}

@Composable
private fun DetailMetric(modifier: Modifier, value: String, label: String) {
    Surface(
        modifier = modifier.heightIn(min = 64.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            StatsItem(title = value, subtitle = label)
        }
    }
}

@Composable
private fun ReadingSessionTimelineItem(
    session: ReadingSessionWithRelations,
    timeFormatter: DateTimeFormatter,
    zone: ZoneId,
    isLast: Boolean,
) {
    val start = remember(session.startedAt, zone) { Instant.ofEpochMilli(session.startedAt).atZone(zone) }
    val end = remember(session.endedAt, zone) { Instant.ofEpochMilli(session.endedAt).atZone(zone) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(horizontal = MaterialTheme.padding.large),
    ) {
        Column(
            modifier = Modifier
                .width(20.dp)
                .fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 7.dp)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
            )
            if (!isLast) {
                Spacer(
                    modifier = Modifier
                        .width(2.dp)
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = MaterialTheme.padding.small, bottom = MaterialTheme.padding.large),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${timeFormatter.format(start)} – ${timeFormatter.format(end)}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        text = sessionDuration(session.readDuration),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Text(
                text = session.mangaTitle,
                modifier = Modifier.padding(top = MaterialTheme.padding.extraSmall),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = session.chapterName,
                modifier = Modifier.padding(top = MaterialTheme.padding.extraSmall),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MostReadDetailsSheet(
    rank: Int,
    manga: MangaReadStats,
    recentSessions: List<ReadingSessionWithRelations>,
    onOpenManga: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.PartiallyExpanded, SheetValue.Expanded),
    )
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }
    val weekdayLabels = shortWeekdayLabels()
    val mangaSessions = remember(manga.mangaId, recentSessions) {
        recentSessions.filter { it.mangaId == manga.mangaId }
    }
    val weeklyBuckets = remember(mangaSessions, today, zone, weekdayLabels) {
        buildReadingBuckets(
            range = ReadingRange.WEEK,
            sessions = mangaSessions,
            today = today,
            zone = zone,
            weekdayLabels = weekdayLabels,
            monthLabels = emptyList(),
        )
    }
    val weeklySessions = remember(mangaSessions, today, zone) {
        val start = today.minusDays(6)
        mangaSessions.filter {
            val date = Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate()
            !date.isBefore(start) && !date.isAfter(today)
        }
    }
    val progress = if (manga.totalChapterCount == 0L) {
        0f
    } else {
        (manga.readChapterCount.toFloat() / manga.totalChapterCount).coerceIn(0f, 1f)
    }
    val progressPercent = (progress * 100).roundToInt()
    val lastReadFormatter = remember {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    }
    val lastRead = if (manga.lastRead > 0) {
        lastReadFormatter.format(Instant.ofEpochMilli(manga.lastRead).atZone(zone))
    } else {
        stringResource(MR.strings.none)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 700.dp),
            contentPadding = PaddingValues(
                start = MaterialTheme.padding.large,
                end = MaterialTheme.padding.large,
                bottom = MaterialTheme.padding.extraLarge,
            ),
        ) {
            item {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(TDMR.strings.stats_rank, rank),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(TDMR.strings.stats_read_details),
                            modifier = Modifier.padding(top = MaterialTheme.padding.extraSmall),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    FilledTonalIconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(MR.strings.action_close),
                        )
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier.padding(vertical = MaterialTheme.padding.large),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MangaCover.Book(
                        data = manga.coverData,
                        modifier = Modifier.width(62.dp),
                        contentDescription = manga.title,
                        shape = MaterialTheme.shapes.medium,
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = MaterialTheme.padding.large),
                    ) {
                        Text(
                            text = manga.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(TDMR.strings.stats_last_read, lastRead),
                            modifier = Modifier.padding(top = MaterialTheme.padding.small),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = MaterialTheme.padding.medium)
                                .height(5.dp)
                                .clip(CircleShape),
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        )
                        Text(
                            text = stringResource(TDMR.strings.stats_read_progress_percent, progressPercent),
                            modifier = Modifier.padding(top = MaterialTheme.padding.extraSmall),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
                        DetailMetric(
                            Modifier.weight(1f),
                            exactDuration(manga.readDuration),
                            stringResource(TDMR.strings.stats_total_time),
                        )
                        DetailMetric(
                            Modifier.weight(1f),
                            formatCount(manga.chapterCount),
                            stringResource(TDMR.strings.stats_read_chapters),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
                        DetailMetric(
                            Modifier.weight(1f),
                            formatCount(manga.sessionCount),
                            stringResource(TDMR.strings.stats_sessions),
                        )
                        DetailMetric(
                            Modifier.weight(1f),
                            sessionDuration(
                                if (manga.sessionCount == 0L) 0L else manga.sessionDuration / manga.sessionCount,
                            ),
                            stringResource(TDMR.strings.stats_average_session),
                        )
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier.padding(top = MaterialTheme.padding.extraLarge),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(TDMR.strings.stats_recent_7_days),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "${exactDuration(weeklySessions.sumOf { it.readDuration })} · " +
                            stringResource(
                                TDMR.strings.stats_chapters_count,
                                formatCount(weeklySessions.distinctBy { it.chapterId }.size.toLong()),
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                MiniReadingBarChart(weeklyBuckets)
            }
            item {
                FilledTonalButton(
                    onClick = onOpenManga,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = MaterialTheme.padding.large),
                ) {
                    Text(stringResource(TDMR.strings.stats_open_novel))
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
                }
            }
            item {
                HorizontalDivider(modifier = Modifier.padding(top = MaterialTheme.padding.medium))
                DataNote(
                    text = stringResource(TDMR.strings.stats_manga_data_note),
                    modifier = Modifier.padding(top = MaterialTheme.padding.medium),
                )
            }
        }
    }
}

@Composable
private fun MiniReadingBarChart(buckets: List<ReadingBucket>) {
    val maximum = max(1L, buckets.maxOfOrNull { it.duration } ?: 1L)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(118.dp)
            .padding(top = MaterialTheme.padding.medium),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        verticalAlignment = Alignment.Bottom,
    ) {
        buckets.forEach { bucket ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                Box(modifier = Modifier.weight(1f).widthIn(max = 18.dp), contentAlignment = Alignment.BottomCenter) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 6.dp)
                            .fillMaxHeight((bucket.duration.toFloat() / maximum).coerceAtLeast(0.06f))
                            .clip(RoundedCornerShape(topStart = 7.dp, topEnd = 7.dp))
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
                Text(
                    text = bucket.label,
                    modifier = Modifier.padding(top = MaterialTheme.padding.small),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
internal fun DataNote(
    text: String,
    modifier: Modifier = Modifier,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (trailingContent != null) {
                Box(modifier = Modifier.padding(start = MaterialTheme.padding.small)) {
                    trailingContent()
                }
            }
        }
    }
}

private fun buildReadingBuckets(
    range: ReadingRange,
    sessions: List<ReadingSessionWithRelations>,
    today: LocalDate,
    zone: ZoneId,
    weekdayLabels: List<String>,
    monthLabels: List<String>,
): List<ReadingBucket> {
    val durationsByDate = sessions.groupBy { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() }
        .mapValues { (_, values) -> values.sumOf { it.readDuration } }
    return when (range) {
        ReadingRange.WEEK -> (6 downTo 0).map { offset ->
            val date = today.minusDays(offset.toLong())
            ReadingBucket(
                weekdayLabels[date.dayOfWeek.value - 1],
                durationsByDate[date] ?: 0L,
            )
        }
        ReadingRange.MONTH -> {
            val rangeStart = today.minusDays(29)
            val first = rangeStart.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            buildList {
                var start = first
                while (!start.isAfter(today)) {
                    val visibleStart = maxOf(start, rangeStart)
                    val end = minOf(start.plusDays(6), today)
                    val duration = durationsByDate
                        .filterKeys { !it.isBefore(visibleStart) && !it.isAfter(end) }
                        .values
                        .sum()
                    val label = if (visibleStart.month == end.month) {
                        "${visibleStart.dayOfMonth}–${end.dayOfMonth}/${end.monthValue}"
                    } else {
                        "${visibleStart.dayOfMonth}/${visibleStart.monthValue}–${end.dayOfMonth}/${end.monthValue}"
                    }
                    add(ReadingBucket(label, duration))
                    start = start.plusWeeks(1)
                }
            }
        }
        ReadingRange.YEAR -> {
            val currentMonth = YearMonth.from(today)
            (11 downTo 0).map { offset ->
                val month = currentMonth.minusMonths(offset.toLong())
                val duration = durationsByDate.filterKeys { YearMonth.from(it) == month }.values.sum()
                ReadingBucket(
                    monthLabels[month.monthValue - 1],
                    duration,
                )
            }
        }
    }
}

private fun buildHeatmap(
    year: Int,
    sessions: List<ReadingSessionWithRelations>,
    zone: ZoneId,
): List<HeatDay> {
    val sessionsByDate = sessions.groupBy { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() }
    val first = LocalDate.of(year, 1, 1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val last = LocalDate.of(year, 12, 31).with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
    val today = LocalDate.now(zone)
    return generateSequence(first) { current -> current.plusDays(1).takeUnless { it.isAfter(last) } }
        .map { date ->
            HeatDay(
                date = date,
                inYear = date.year == year,
                future = date.isAfter(today),
                sessions = sessionsByDate[date].orEmpty(),
            )
        }
        .toList()
}

private fun longestStreak(days: List<HeatDay>): Int {
    var current = 0
    var longest = 0
    days.forEach { day ->
        current = if (day.inYear && !day.future && day.duration > 0) current + 1 else 0
        longest = max(longest, current)
    }
    return longest
}

@Composable
private fun shortWeekdayLabels() = listOf(
    stringResource(TDMR.strings.stats_weekday_monday_short),
    stringResource(TDMR.strings.stats_weekday_tuesday_short),
    stringResource(TDMR.strings.stats_weekday_wednesday_short),
    stringResource(TDMR.strings.stats_weekday_thursday_short),
    stringResource(TDMR.strings.stats_weekday_friday_short),
    stringResource(TDMR.strings.stats_weekday_saturday_short),
    stringResource(TDMR.strings.stats_weekday_sunday_short),
)

@Composable
private fun shortMonthLabels() = listOf(
    stringResource(TDMR.strings.stats_month_january_short),
    stringResource(TDMR.strings.stats_month_february_short),
    stringResource(TDMR.strings.stats_month_march_short),
    stringResource(TDMR.strings.stats_month_april_short),
    stringResource(TDMR.strings.stats_month_may_short),
    stringResource(TDMR.strings.stats_month_june_short),
    stringResource(TDMR.strings.stats_month_july_short),
    stringResource(TDMR.strings.stats_month_august_short),
    stringResource(TDMR.strings.stats_month_september_short),
    stringResource(TDMR.strings.stats_month_october_short),
    stringResource(TDMR.strings.stats_month_november_short),
    stringResource(TDMR.strings.stats_month_december_short),
)

private fun formatCount(value: Long): String = NumberFormat.getIntegerInstance(Locale.getDefault()).format(value)

@Composable
private fun sessionDuration(durationMillis: Long): String {
    return if (durationMillis in 1..<60_000L) {
        stringResource(TDMR.strings.stats_less_than_minute)
    } else {
        exactDuration(durationMillis)
    }
}

@Composable
private fun exactDuration(durationMillis: Long): String {
    val totalMinutes = (durationMillis / 60_000L).coerceAtLeast(0)
    val days = totalMinutes / (24 * 60)
    val hours = totalMinutes / 60 % 24
    val minutes = totalMinutes % 60
    return when {
        days > 0 -> stringResource(TDMR.strings.stats_days_hours_minutes, days, hours, minutes)
        hours > 0 -> stringResource(TDMR.strings.stats_hours_minutes, hours, minutes)
        else -> stringResource(TDMR.strings.stats_minutes, minutes)
    }
}
