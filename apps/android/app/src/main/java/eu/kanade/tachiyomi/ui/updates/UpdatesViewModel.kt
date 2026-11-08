package eu.kanade.tachiyomi.ui.updates

import android.app.Application
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.util.fastFilter
import androidx.lifecycle.viewModelScope
import eu.kanade.core.preference.asState
import eu.kanade.core.util.addOrRemove
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.updates.UpdatesUiModel
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.source.isNovelSource
import eu.kanade.tachiyomi.util.lang.toLocalDate
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import logcat.LogPriority
import mihon.core.viewmodel.StateViewModel
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.applyFilter
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.updates.interactor.GetUpdates
import tachiyomi.domain.updates.model.UpdatesWithRelations
import tachiyomi.domain.updates.service.UpdatesPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.time.Clock

enum class UpdatesFilter {
    ALL,
    MANGA,
    NOVELS,
}

class UpdatesViewModel(
    private val sourceManager: SourceManager = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    private val downloadCache: DownloadCache = Injekt.get(),
    private val updateChapter: UpdateChapter = Injekt.get(),
    private val setReadStatus: SetReadStatus = Injekt.get(),
    private val getUpdates: GetUpdates = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val getChapter: GetChapter = Injekt.get(),
    private val getChaptersByMangaId: GetChaptersByMangaId = Injekt.get(),
    private val getLibraryManga: GetLibraryManga = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val updatesPreferences: UpdatesPreferences = Injekt.get(),
    val snackbarHostState: SnackbarHostState = SnackbarHostState(),
) : StateViewModel<UpdatesViewModel.State>(State()) {

    @Volatile
    private var latestUpdates: List<UpdatesWithRelations> = emptyList()

    private val _events: Channel<Event> = Channel(Int.MAX_VALUE)
    val events: Flow<Event> = _events.receiveAsFlow()

    val lastUpdated by libraryPreferences.lastUpdatedTimestamp.asState(viewModelScope)

    private val selectedChapterIds: HashSet<Long> = HashSet()

    // DB-level pagination: start with one page, grow as user scrolls
    private val currentLimit = MutableStateFlow(GetUpdates.PAGE_SIZE)

    init {
        viewModelScope.launchIO {
            // Set date limit for recent chapters
            val dateThreshold = Clock.System.now().minus(3, DateTimeUnit.MONTH, TimeZone.currentSystemDefault())

            combine(
                // needed for SQL filters (unread, started, bookmarked, etc)
                combine(
                    getUpdatesItemPreferenceFlow().distinctUntilChanged(),
                    currentLimit,
                ) { prefs, dbLimit -> prefs to dbLimit }
                    .flatMapLatest { (prefs, dbLimit) ->
                        getUpdates.subscribe(
                            dateThreshold,
                            limit = dbLimit,
                            unread = prefs.filterUnread.toBooleanOrNull(),
                            started = prefs.filterStarted.toBooleanOrNull(),
                            bookmarked = prefs.filterBookmarked.toBooleanOrNull(),
                            hideExcludedScanlators = prefs.filterExcludedScanlators,
                            includedCategories = prefs.filterIncludedCategories,
                            excludedCategories = prefs.filterExcludedCategories,
                        ).distinctUntilChanged()
                    },
                downloadCache.changes,
                downloadManager.queueState,
                libraryPreferences.lastUpdatesClearedTimestamp.changes(),
                // needed for Kotlin filters (downloaded)
                getUpdatesItemPreferenceFlow().distinctUntilChanged { old, new ->
                    old.filterDownloaded == new.filterDownloaded
                },
            ) { updates, _, _, clearedAt, itemPreferences ->
                val filteredUpdates = if (clearedAt > 0L) {
                    updates.filter { it.dateFetch > clearedAt }
                } else {
                    updates
                }
                latestUpdates = filteredUpdates
                val items = filteredUpdates
                    .toUpdateItems()
                    .applyFilters(itemPreferences)
                    .toList()
                // If returned items fill the limit, there may be more
                val hasMore = updates.size.toLong() >= currentLimit.value
                items to hasMore
            }
                .catch {
                    logcat(LogPriority.ERROR, it)
                    _events.send(Event.InternalError)
                }
                .collectLatest { (updateItems, hasMore) ->
                    mutableState.update {
                        it.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            items = updateItems,
                            hasMorePages = hasMore,
                        )
                    }
                }
        }

        viewModelScope.launchIO {
            merge(downloadManager.statusFlow(), downloadManager.progressFlow())
                .catch { logcat(LogPriority.ERROR, it) }
                .collect(this@UpdatesViewModel::updateDownloadState)
        }

        getUpdatesItemPreferenceFlow()
            .map { prefs ->
                listOf(
                    prefs.filterUnread,
                    prefs.filterDownloaded,
                    prefs.filterStarted,
                    prefs.filterBookmarked,
                )
                    .any { it != TriState.DISABLED }
            }
            .distinctUntilChanged()
            .onEach {
                mutableState.update { state ->
                    state.copy(hasActiveFilters = it)
                }
            }
            .launchIn(viewModelScope)
    }

    private fun List<UpdatesItem>.applyFilters(
        preferences: ItemPreferences,
    ): List<UpdatesItem> {
        val filterDownloaded = preferences.filterDownloaded

        val filterFnDownloaded: (UpdatesItem) -> Boolean = {
            applyFilter(filterDownloaded) {
                it.downloadStateProvider() == Download.State.DOWNLOADED
            }
        }

        return fastFilter {
            filterFnDownloaded(it)
        }
    }

    private fun List<UpdatesWithRelations>.toUpdateItems(): List<UpdatesItem> {
        val filter = state.value.filter
        // Cache source lookups to avoid repeated getOrStub + isNovelSource calls
        val novelSourceCache = mutableMapOf<Long, Boolean>()
        fun isNovel(sourceId: Long): Boolean = novelSourceCache.getOrPut(sourceId) {
            sourceManager.getOrStub(sourceId).isNovelSource()
        }

        return this
            .filter { update ->
                when (filter) {
                    UpdatesFilter.ALL -> true
                    UpdatesFilter.MANGA -> !isNovel(update.sourceId)
                    UpdatesFilter.NOVELS -> isNovel(update.sourceId)
                }
            }
            .map { update ->
                val activeDownload = downloadManager.getQueuedDownloadOrNull(update.chapterId)
                val downloaded = downloadManager.isChapterDownloaded(
                    update.chapterName,
                    update.scanlator,
                    update.chapterUrl,
                    update.mangaTitle,
                    update.sourceId,
                )
                val downloadState = when {
                    activeDownload != null -> activeDownload.status
                    downloaded -> Download.State.DOWNLOADED
                    else -> Download.State.NOT_DOWNLOADED
                }
                UpdatesItem(
                    update = update,
                    downloadStateProvider = { downloadState },
                    downloadProgressProvider = { activeDownload?.progress ?: 0 },
                    selected = update.chapterId in selectedChapterIds,
                    isNovel = isNovel(update.sourceId),
                )
            }
    }

    fun updateLibrary(): Boolean {
        val started = LibraryUpdateJob.startNow(Injekt.get<Application>())
        viewModelScope.launch {
            _events.send(Event.LibraryUpdateTriggered(started))
        }
        return started
    }

    /**
     * Update status of chapters.
     *
     * @param download download object containing progress.
     */
    private fun updateDownloadState(download: Download) {
        mutableState.update { state ->
            val modifiedIndex = state.items.indexOfFirst { it.update.chapterId == download.chapterId }
            if (modifiedIndex < 0) return@update state
            val newItems = state.items.toMutableList()
            newItems[modifiedIndex] = newItems[modifiedIndex].copy(
                downloadStateProvider = { download.status },
                downloadProgressProvider = { download.progress },
            )
            state.copy(items = newItems)
        }
    }

    fun downloadChapters(items: List<UpdatesItem>, action: ChapterDownloadAction) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            when (action) {
                ChapterDownloadAction.START -> {
                    downloadChapters(items)
                    if (items.any { it.downloadStateProvider() == Download.State.ERROR }) {
                        downloadManager.startDownloads()
                    }
                }
                ChapterDownloadAction.START_NOW -> {
                    val chapterId = items.singleOrNull()?.update?.chapterId ?: return@launch
                    startDownloadingNow(chapterId)
                }
                ChapterDownloadAction.CANCEL -> {
                    val chapterId = items.singleOrNull()?.update?.chapterId ?: return@launch
                    cancelDownload(chapterId)
                }
                ChapterDownloadAction.DELETE -> {
                    deleteChapters(items)
                }
            }
            toggleAllSelection(false)
        }
    }

    private fun startDownloadingNow(chapterId: Long) {
        downloadManager.startDownloadNow(chapterId)
    }

    private fun cancelDownload(chapterId: Long) {
        val activeDownload = downloadManager.getQueuedDownloadOrNull(chapterId) ?: return
        downloadManager.cancelQueuedDownloads(listOf(activeDownload))
        updateDownloadState(activeDownload.apply { status = Download.State.NOT_DOWNLOADED })
    }

    /**
     * Mark the selected updates list as read/unread.
     * @param updates the list of selected updates.
     * @param read whether to mark chapters as read or unread.
     */
    fun markUpdatesRead(updates: List<UpdatesItem>, read: Boolean) {
        viewModelScope.launchIO {
            val chapterIds = updates.map { it.update.chapterId }
            val chapters = getChapter.awaitAll(chapterIds)
            setReadStatus.await(
                read = read,
                chapters = chapters.toTypedArray(),
            )

            // Notify the library of badge changes so unread counts update immediately.
            // GetLibraryManga is a manual StateFlow that isn't DB-reactive, so each
            // affected manga's read count must be recomputed and pushed explicitly.
            val mangaIds = updates.map { it.update.mangaId }.distinct()
            val batch = mangaIds.associateWith { mangaId ->
                val mangaChapters = getChaptersByMangaId.await(mangaId)
                val readCount = mangaChapters.count { it.read }.toLong()
                val totalCount = mangaChapters.size.toLong();
                { libraryManga: LibraryManga ->
                    libraryManga.copy(totalChapters = totalCount, readCount = readCount)
                }
            }
            getLibraryManga.applyBatchChapterUpdates(batch)
        }
        toggleAllSelection(false)
    }

    /**
     * Bookmarks the given list of chapters.
     * @param updates the list of chapters to bookmark.
     */
    fun bookmarkUpdates(updates: List<UpdatesItem>, bookmark: Boolean) {
        viewModelScope.launchIO {
            updates
                .filterNot { it.update.bookmark == bookmark }
                .map { ChapterUpdate(id = it.update.chapterId, bookmark = bookmark) }
                .let { updateChapter.awaitAll(it) }
        }
        toggleAllSelection(false)
    }

    /**
     * Downloads the given list of chapters with the manager.
     * @param updatesItem the list of chapters to download.
     */
    private fun downloadChapters(updatesItem: List<UpdatesItem>) {
        viewModelScope.launchNonCancellable {
            val groupedUpdates = updatesItem.groupBy { it.update.mangaId }.values
            for (updates in groupedUpdates) {
                val mangaId = updates.first().update.mangaId
                val manga = getManga.await(mangaId) ?: continue
                // Don't download if source isn't available
                sourceManager.get(manga.source) ?: continue
                val chapterIds = updates.map { it.update.chapterId }
                val chapters = getChapter.awaitAll(chapterIds)
                downloadManager.downloadChapters(manga, chapters)
            }
        }
    }

    /**
     * Delete selected chapters
     *
     * @param updatesItem list of chapters
     */
    fun deleteChapters(updatesItem: List<UpdatesItem>) {
        viewModelScope.launchNonCancellable {
            updatesItem
                .groupBy { it.update.mangaId }
                .entries
                .forEach { (mangaId, updates) ->
                    val manga = getManga.await(mangaId) ?: return@forEach
                    val source = sourceManager.get(manga.source) ?: return@forEach
                    val chapterIds = updates.map { it.update.chapterId }
                    val chapters = getChapter.awaitAll(chapterIds)
                    downloadManager.deleteChapters(chapters, manga, source)
                }
        }
        toggleAllSelection(false)
    }

    fun showConfirmDeleteChapters(updatesItem: List<UpdatesItem>) {
        setDialog(Dialog.DeleteConfirmation(updatesItem))
    }

    fun toggleSelection(
        item: UpdatesItem,
        selected: Boolean,
    ) = updateSelection(listOf(item), selected)

    fun toggleGroupSelection(
        items: List<UpdatesItem>,
        selected: Boolean,
    ) = updateSelection(items, selected)

    private fun updateSelection(
        items: List<UpdatesItem>,
        selected: Boolean,
    ) {
        val chapterIds = items.mapTo(HashSet(items.size)) { it.update.chapterId }
        if (chapterIds.isEmpty()) return

        mutableState.update { state ->
            var changed = false
            val newItems = state.items.map { current ->
                if (current.update.chapterId !in chapterIds) {
                    current
                } else {
                    selectedChapterIds.addOrRemove(current.update.chapterId, selected)
                    if (current.selected == selected) {
                        current
                    } else {
                        changed = true
                        current.copy(selected = selected)
                    }
                }
            }
            if (changed) state.copy(items = newItems) else state
        }
    }

    fun toggleAllSelection(selected: Boolean) {
        updateSelection(state.value.items, selected)
    }

    fun invertSelection() {
        mutableState.update { state ->
            val newItems = state.items.map {
                selectedChapterIds.addOrRemove(it.update.chapterId, !it.selected)
                it.copy(selected = !it.selected)
            }
            state.copy(items = newItems)
        }
    }

    fun setDialog(dialog: Dialog?) {
        mutableState.update { it.copy(dialog = dialog) }
    }

    fun resetNewUpdatesCount() {
        libraryPreferences.newUpdatesCount.set(0)
    }

    /**
     * Load more updates from the database.
     * Increases the SQL LIMIT to fetch the next page of results.
     */
    fun loadMore() {
        if (!state.value.hasMorePages || state.value.isLoadingMore) return
        mutableState.update { it.copy(isLoadingMore = true) }
        currentLimit.value += GetUpdates.PAGE_SIZE
    }

    private fun getUpdatesItemPreferenceFlow(): Flow<ItemPreferences> {
        return combine(
            updatesPreferences.filterDownloaded.changes(),
            updatesPreferences.filterUnread.changes(),
            updatesPreferences.filterStarted.changes(),
            updatesPreferences.filterBookmarked.changes(),
            updatesPreferences.filterExcludedScanlators.changes(),
            updatesPreferences.filterIncludedCategories.changes(),
            updatesPreferences.filterExcludedCategories.changes(),
        ) {
            @Suppress("UNCHECKED_CAST")
            ItemPreferences(
                filterDownloaded = it[0] as TriState,
                filterUnread = it[1] as TriState,
                filterStarted = it[2] as TriState,
                filterBookmarked = it[3] as TriState,
                filterExcludedScanlators = it[4] as Boolean,
                filterIncludedCategories = it[5] as List<Long>,
                filterExcludedCategories = it[6] as List<Long>,
            )
        }
    }

    fun showFilterDialog() {
        mutableState.update { it.copy(dialog = Dialog.FilterSheet) }
    }

    @Immutable
    private data class ItemPreferences(
        val filterDownloaded: TriState,
        val filterUnread: TriState,
        val filterStarted: TriState,
        val filterBookmarked: TriState,
        val filterExcludedScanlators: Boolean,
        val filterIncludedCategories: List<Long>,
        val filterExcludedCategories: List<Long>,
    )

    @Immutable
    data class State(
        val isLoading: Boolean = true,
        val hasActiveFilters: Boolean = false,
        val items: List<UpdatesItem> = listOf(),
        val dialog: Dialog? = null,
        val filter: UpdatesFilter = UpdatesFilter.NOVELS,
        val hasMorePages: Boolean = true,
        val isLoadingMore: Boolean = false,
    ) {
        val selected = items.filter { it.selected }
        val selectionMode = selected.isNotEmpty()

        fun getUiModel(): List<UpdatesUiModel> {
            return buildList {
                items
                    .groupBy { it.update.dateFetch.toLocalDate() }
                    .forEach { (date, dateItems) ->
                        add(UpdatesUiModel.Header(date))
                        dateItems
                            .groupBy { it.update.mangaId }
                            .forEach { (mangaId, mangaItems) ->
                                add(
                                    if (mangaItems.size == 1) {
                                        UpdatesUiModel.Item(mangaItems.single())
                                    } else {
                                        UpdatesUiModel.Group(
                                            key = UpdatesUiModel.GroupKey(date, mangaId),
                                            items = mangaItems,
                                        )
                                    },
                                )
                            }
                    }
            }
        }
    }

    sealed interface Dialog {
        data class DeleteConfirmation(val toDelete: List<UpdatesItem>) : Dialog
        data object FilterSheet : Dialog
    }

    sealed interface Event {
        data object InternalError : Event
        data class LibraryUpdateTriggered(val started: Boolean) : Event
    }
}

private fun TriState.toBooleanOrNull(): Boolean? {
    return when (this) {
        TriState.DISABLED -> null
        TriState.ENABLED_IS -> true
        TriState.ENABLED_NOT -> false
    }
}

@Immutable
data class UpdatesItem(
    val update: UpdatesWithRelations,
    val downloadStateProvider: () -> Download.State,
    val downloadProgressProvider: () -> Int,
    val selected: Boolean = false,
    val isNovel: Boolean = false,
)
