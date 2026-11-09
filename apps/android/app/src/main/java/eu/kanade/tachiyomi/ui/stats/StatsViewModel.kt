package eu.kanade.tachiyomi.ui.stats

import androidx.compose.ui.util.fastDistinctBy
import androidx.compose.ui.util.fastFilter
import androidx.lifecycle.viewModelScope
import eu.kanade.core.util.fastCountNot
import eu.kanade.presentation.more.stats.StatsScreenState
import eu.kanade.presentation.more.stats.data.StatsData
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import mihon.core.viewmodel.StateViewModel
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.history.interactor.GetMostReadManga
import tachiyomi.domain.history.interactor.GetReadingSessions
import tachiyomi.domain.history.interactor.GetTotalReadDuration
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.library.service.LibraryPreferences.Companion.MANGA_HAS_UNREAD
import tachiyomi.domain.library.service.LibraryPreferences.Companion.MANGA_NON_COMPLETED
import tachiyomi.domain.library.service.LibraryPreferences.Companion.MANGA_NON_READ
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.model.Track
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class StatsViewModel(
    private val downloadManager: DownloadManager = Injekt.get(),
    private val getLibraryManga: GetLibraryManga = Injekt.get(),
    private val getTotalReadDuration: GetTotalReadDuration = Injekt.get(),
    private val getReadingSessions: GetReadingSessions = Injekt.get(),
    private val getMostReadManga: GetMostReadManga = Injekt.get(),
    private val getTracks: GetTracks = Injekt.get(),
    private val storageManager: StorageManager = Injekt.get(),
    private val preferences: LibraryPreferences = Injekt.get(),
    private val trackerManager: TrackerManager = Injekt.get(),
) : StateViewModel<StatsScreenState>(StatsScreenState.Loading) {

    private val loggedInTrackers by lazy { trackerManager.loggedInTrackers() }
    private var advancedLoadJob: Job? = null
    private var yearLoadJob: Job? = null
    private var storageLoadJob: Job? = null

    init {
        viewModelScope.launchIO {
            val libraryManga = getLibraryManga.await()

            val distinctLibraryManga = libraryManga.fastDistinctBy { it.id }

            val mangaTrackMap = getMangaTrackMap(distinctLibraryManga)
            val scoredMangaTrackerMap = getScoredMangaTrackMap(mangaTrackMap)

            val meanScore = getTrackMeanScore(scoredMangaTrackerMap)

            val overviewStatData = StatsData.Overview(
                libraryMangaCount = distinctLibraryManga.size,
                completedMangaCount = distinctLibraryManga.count {
                    it.manga.status.toInt() == SManga.COMPLETED && it.unreadCount == 0L
                },
                totalReadDuration = getTotalReadDuration.await(),
            )

            val titlesStatData = StatsData.Titles(
                globalUpdateItemCount = getGlobalUpdateItemCount(libraryManga),
                startedMangaCount = distinctLibraryManga.count { it.hasStarted },
                localMangaCount = distinctLibraryManga.count { it.manga.isLocal() },
                publicationStatusCounts = distinctLibraryManga.groupingBy { it.manga.status }.eachCount(),
            )

            val chaptersStatData = StatsData.Chapters(
                totalChapterCount = distinctLibraryManga.sumOf { it.totalChapters }.toInt(),
                readChapterCount = distinctLibraryManga.sumOf { it.readCount }.toInt(),
                downloadCount = downloadManager.getDownloadCount(),
            )

            val trackersStatData = StatsData.Trackers(
                trackedTitleCount = mangaTrackMap.count { it.value.isNotEmpty() },
                meanScore = meanScore,
                trackerCount = loggedInTrackers.size,
            )

            mutableState.update {
                StatsScreenState.Success(
                    overview = overviewStatData,
                    titles = titlesStatData,
                    chapters = chaptersStatData,
                    trackers = trackersStatData,
                )
            }
        }
    }

    fun loadAdvancedStats() {
        val current = state.value as? StatsScreenState.Success ?: return
        if (current.advanced != null || advancedLoadJob?.isActive == true) return

        advancedLoadJob = viewModelScope.launchIO {
            val advanced = queryAdvancedStats()
            mutableState.update { state ->
                val success = state as? StatsScreenState.Success ?: return@update state
                success.copy(advanced = advanced)
            }
        }
    }

    fun selectYear(year: Int) {
        val current = state.value as? StatsScreenState.Success ?: return
        val advanced = current.advanced ?: return
        if (year !in advanced.availableYears) return

        yearLoadJob?.cancel()
        if (year == advanced.selectedYear) return

        yearLoadJob = viewModelScope.launchIO {
            val zone = ZoneId.systemDefault()
            val from = LocalDate.of(year, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
            val until = LocalDate.of(year + 1, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
            val sessions = getReadingSessions.await(from, until)
            mutableState.update { state ->
                val success = state as? StatsScreenState.Success ?: return@update state
                val currentAdvanced = success.advanced ?: return@update state
                success.copy(
                    advanced = currentAdvanced.copy(
                        selectedYear = year,
                        yearSessions = sessions,
                    ),
                )
            }
        }
    }

    fun refreshStorageStats() {
        if (state.value !is StatsScreenState.Success || storageLoadJob?.isActive == true) return

        mutableState.update { state ->
            val success = state as? StatsScreenState.Success ?: return@update state
            success.copy(storageLoading = true, storageError = false)
        }
        storageLoadJob = viewModelScope.launchIO {
            val storage = runCatching { storageManager.getStats() }.getOrNull()
            mutableState.update { state ->
                val success = state as? StatsScreenState.Success ?: return@update state
                success.copy(
                    storage = storage ?: success.storage,
                    storageLoading = false,
                    storageError = storage == null,
                )
            }
        }
    }

    private suspend fun queryAdvancedStats(): StatsData.Advanced {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val currentYear = today.year
        val yearStart = LocalDate.of(currentYear, 1, 1)
        val recentStart = today.minusYears(1).plusDays(1)
        val recentStartMillis = recentStart.atStartOfDay(zone).toInstant().toEpochMilli()
        val queryStart = minOf(yearStart, recentStart).atStartOfDay(zone).toInstant().toEpochMilli()
        val queryUntil = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val sessions = getReadingSessions.await(queryStart, queryUntil)
        val oldestYear = getReadingSessions.awaitOldestStartedAt()
            ?.let { Instant.ofEpochMilli(it).atZone(zone).year }
            ?.coerceAtMost(currentYear)
            ?: currentYear

        return StatsData.Advanced(
            selectedYear = currentYear,
            availableYears = (currentYear downTo oldestYear).toList(),
            recentSessions = sessions.filter { it.startedAt >= recentStartMillis },
            yearSessions = sessions.filter { it.startedAt >= yearStart.atStartOfDay(zone).toInstant().toEpochMilli() },
            mostReadManga = getMostReadManga.await(),
        )
    }

    private fun getGlobalUpdateItemCount(libraryManga: List<LibraryManga>): Int {
        val includedCategories = preferences.updateCategories.get().map { it.toLong() }
        val excludedCategories = preferences.updateCategoriesExclude.get().map { it.toLong() }
        val updateRestrictions = preferences.autoUpdateMangaRestrictions.get()

        return libraryManga.filter {
            val included = includedCategories.isEmpty() || it.categories.intersect(includedCategories).isNotEmpty()
            val excluded = it.categories.intersect(excludedCategories).isNotEmpty()
            included && !excluded
        }
            .fastCountNot {
                (MANGA_NON_COMPLETED in updateRestrictions && it.manga.status.toInt() == SManga.COMPLETED) ||
                    (MANGA_HAS_UNREAD in updateRestrictions && it.unreadCount != 0L) ||
                    (MANGA_NON_READ in updateRestrictions && it.totalChapters > 0 && !it.hasStarted)
            }
    }

    private suspend fun getMangaTrackMap(libraryManga: List<LibraryManga>): Map<Long, List<Track>> {
        val loggedInTrackerIds = loggedInTrackers.map { it.id }.toHashSet()
        val mangaIds = libraryManga.mapTo(HashSet()) { it.id }
        return getTracks.awaitAll()
            .fastFilter { it.mangaId in mangaIds && it.trackerId in loggedInTrackerIds }
            .groupBy { it.mangaId }
    }

    private fun getScoredMangaTrackMap(mangaTrackMap: Map<Long, List<Track>>): Map<Long, List<Track>> {
        return mangaTrackMap.mapNotNull { (mangaId, tracks) ->
            val trackList = tracks.mapNotNull { track ->
                track.takeIf { it.score > 0.0 }
            }
            if (trackList.isEmpty()) return@mapNotNull null
            mangaId to trackList
        }.toMap()
    }

    private fun getTrackMeanScore(scoredMangaTrackMap: Map<Long, List<Track>>): Double {
        return scoredMangaTrackMap
            .map { (_, tracks) ->
                tracks.map(::get10PointScore).average()
            }
            .fastFilter { !it.isNaN() }
            .average()
    }

    private fun get10PointScore(track: Track): Double {
        val service = trackerManager.get(track.trackerId)!!
        return service.get10PointScore(track)
    }
}
