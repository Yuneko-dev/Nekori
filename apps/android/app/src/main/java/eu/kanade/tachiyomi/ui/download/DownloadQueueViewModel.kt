package eu.kanade.tachiyomi.ui.download

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.isNovelSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.domain.library.service.LibraryPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.time.Duration.Companion.milliseconds

class NovelDownloadItem(
    val mangaId: Long,
    val mangaTitle: String,
    val sourceName: String,
    val subItems: List<Download>,
    val initialTotal: Int, // Total chapters when the download batch started
) {
    val remainingChapters: Int get() = subItems.size

    // Downloaded chapters = initial total - remaining (since completed are removed from queue)
    val downloadedChapters: Int get() = (initialTotal - remainingChapters).coerceAtLeast(0)
    val totalChapters: Int get() = initialTotal
    val currentDownload: Download? get() = subItems.find { it.status == Download.State.DOWNLOADING }

    val overallProgress: Float get() {
        if (totalChapters == 0) return 0f
        val partialChapters = subItems.sumOf { it.progress.coerceIn(0, 100) } / 100f
        return ((downloadedChapters + partialChapters) / totalChapters).coerceIn(0f, 1f)
    }

    val isActive: Boolean get() = currentDownload != null
    val erroredDownloads: List<Download> get() = subItems.filter { it.status == Download.State.ERROR }
    val hasError: Boolean get() = erroredDownloads.isNotEmpty()

    /** Per-chapter failure reason (first line of the stored error). */
    val errorDetails: List<Pair<String, String>> get() = erroredDownloads.map { dl ->
        val reason = dl.error?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() } ?: "Unknown error"
        dl.chapterName to reason
    }

    /** Full multi-line error report for copying. */
    val fullErrorReport: String get() = erroredDownloads.joinToString("\n\n") { dl ->
        "${dl.chapterName}\n${dl.error ?: "Unknown error"}"
    }

    val statusText: String get() = when {
        hasError -> "Error"
        isActive -> "Downloading"
        subItems.all { it.status == Download.State.QUEUE } && subItems.isNotEmpty() -> "Queued"
        downloadedChapters == totalChapters -> "Completed"
        else -> "Pending"
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadQueueViewModel(
    private val downloadManager: DownloadManager = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
) : ViewModel() {

    private val _novelState = MutableStateFlow(emptyList<NovelDownloadItem>())
    val novelState = _novelState.asStateFlow()

    // Track the initial total for each manga to show accurate progress
    private val initialTotals = mutableMapOf<Long, Int>()

    val titleMaxLines = libraryPreferences.titleMaxLines.changes()
        .stateIn(viewModelScope, SharingStarted.Lazily, libraryPreferences.titleMaxLines.get())

    init {
        // Novel groups: this is a Compose list, so it must also re-emit on per-download status
        // changes (e.g. -> ERROR, -> DOWNLOADED); queueState alone only changes on add/remove.
        viewModelScope.launch {
            downloadManager.queueState
                .map { downloads -> downloads.filter { it.source.isNovelSource() } }
                .flatMapLatest { novels ->
                    if (novels.isEmpty()) {
                        flowOf(emptyList())
                    } else {
                        combine(
                            novels.map { download ->
                                download.statusFlow.flatMapLatest { status ->
                                    if (status == Download.State.DOWNLOADING) {
                                        download.progressFlow
                                    } else {
                                        flowOf(download.progress)
                                    }
                                }
                            },
                        ) { novels }
                    }
                }
                // Sampled, not debounced, and sampled before the regrouping below. Every active
                // download reports progress about every 50 ms (Download.progressFlow debounces by
                // that much already), so a debounce window here never elapsed while anything was
                // downloading and the list stayed empty until the queue went quiet. Sampling caps
                // the rate instead of waiting for a gap, and doing it upstream of the grouping
                // means the whole queue is regrouped five times a second rather than per progress
                // tick.
                .sample(200.milliseconds)
                .map { novels ->
                    // Clean up initialTotals for manga no longer in queue
                    val currentMangaIds = novels.map { it.mangaId }.toSet()
                    initialTotals.keys.removeAll { it !in currentMangaIds }

                    novels.groupBy { it.mangaId }
                        .map { (mangaId, downloads) ->
                            // Track initial total - use max of current count and stored count
                            val currentCount = downloads.size
                            val storedTotal = initialTotals[mangaId] ?: 0
                            val initialTotal = if (currentCount > storedTotal) {
                                initialTotals[mangaId] = currentCount
                                currentCount
                            } else {
                                storedTotal
                            }

                            NovelDownloadItem(
                                mangaId = mangaId,
                                mangaTitle = downloads.first().mangaTitle,
                                sourceName = downloads.first().source.name,
                                subItems = downloads,
                                initialTotal = initialTotal,
                            )
                        }
                        .distinctBy { it.mangaId }
                }
                .flowOn(Dispatchers.Default)
                .collect { novelItems -> _novelState.update { novelItems } }
        }
    }

    val isDownloaderRunning = downloadManager.isDownloaderRunning
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun startDownloads() {
        downloadManager.startDownloads()
    }

    fun pauseDownloads() {
        downloadManager.pauseDownloads()
    }

    val pausedNovelMangaIds = downloadManager.pausedNovelMangaIds

    fun pauseNovelGroup(mangaId: Long) {
        downloadManager.pauseNovelGroup(mangaId)
    }

    fun resumeNovelGroup(mangaId: Long) {
        downloadManager.resumeNovelGroup(mangaId)
    }

    fun clearQueue() {
        downloadManager.clearQueue()
    }

    fun reorder(downloads: List<Download>) {
        reorderSubsetKeepingOthers(downloads)
    }

    private fun reorderSubsetKeepingOthers(downloads: List<Download>) {
        if (downloads.isEmpty()) return

        // Reorder only the provided downloads, while keeping everything else in-place.
        // This prevents, for example, sorting the Manga tab from dropping Novel downloads.
        val current = downloadManager.queueState.value
        val chapterIds = downloads.asSequence().map { it.chapterId }.toSet()
        val iterator = downloads.iterator()

        val merged = current.map { existing ->
            if (chapterIds.contains(existing.chapterId) && iterator.hasNext()) {
                iterator.next()
            } else {
                existing
            }
        }

        downloadManager.reorderQueue(merged)
    }

    fun cancel(downloads: List<Download>) {
        downloadManager.cancelQueuedDownloads(downloads)
    }

    fun reorderNovelQueueByGroupOrder(groupOrder: List<Long>) {
        // Build a reordered list of novel downloads based on the desired series (mangaId) ordering.
        val groups = novelState.value.associateBy { it.mangaId }
        val newDownloads = groupOrder.flatMap { mangaId ->
            groups[mangaId]?.subItems.orEmpty()
        }
        reorderSubsetKeepingOthers(newDownloads)
    }
}
