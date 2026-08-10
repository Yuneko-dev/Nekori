package eu.kanade.domain.chapter.interactor

import eu.kanade.domain.chapter.model.copyFromSChapter
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.novel.NovelStructureSource
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import tachiyomi.data.chapter.ChapterSanitizer
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.NoChaptersException
import tachiyomi.domain.chapter.model.toChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.chapter.service.ChapterRecognition
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.novel.model.NovelLayout
import tachiyomi.domain.novel.model.NovelStructure
import tachiyomi.domain.novel.repository.NovelStructureRepository
import tachiyomi.domain.translation.repository.TranslatedChapterRepository
import tachiyomi.source.local.isLocal
import java.lang.Long.max
import java.util.TreeSet
import kotlin.time.Clock

class SyncChaptersWithSource(
    private val downloadManager: DownloadManager,
    private val downloadProvider: DownloadProvider,
    private val chapterRepository: ChapterRepository,
    private val shouldUpdateDbChapter: ShouldUpdateDbChapter,
    private val updateManga: UpdateManga,
    private val updateChapter: UpdateChapter,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val getExcludedScanlators: GetExcludedScanlators,
    private val libraryPreferences: LibraryPreferences,
    private val translatedChapterRepository: TranslatedChapterRepository,
    private val novelStructureRepository: NovelStructureRepository,
) {

    /**
     * Method to synchronize db chapters with source ones
     *
     * @param rawSourceChapters the chapters from the source.
     * @param manga the manga the chapters belong to.
     * @param source the source the manga belongs to.
     * @return Newly added chapters
     */
    suspend fun await(
        rawSourceChapters: List<SChapter>,
        manga: Manga,
        source: Source,
        manualFetch: Boolean = false,
        fetchWindow: Pair<Long, Long> = Pair(0, 0),
    ): List<Chapter> {
        val novelStructure = (source as? NovelStructureSource)?.getNovelStructure(manga.url)
        if (
            rawSourceChapters.isEmpty() &&
            !source.isLocal() &&
            novelStructure?.layout != NovelLayout.PAGED
        ) {
            throw NoChaptersException()
        }

        val timeZone = TimeZone.currentSystemDefault()
        val now = Clock.System.now().toLocalDateTime(timeZone)
        val nowMillis = now.toInstant(timeZone).toEpochMilliseconds()

        // Check if this source should have reversed chapter list
        val reversedSources = libraryPreferences.reversedChapterSources.get()
        val shouldReverse = source.id.toString() in reversedSources

        // Apply reversal if configured for this source
        val orderedChapters = if (shouldReverse) rawSourceChapters.reversed() else rawSourceChapters

        val pageChapterIndexes = mutableMapOf<Long, Int>()
        val sourceChapters = orderedChapters
            .distinctBy { it.url }
            .mapIndexed { i, sChapter ->
                val sourceOrder = if (novelStructure?.layout == NovelLayout.PAGED) {
                    val pageNumber = novelStructure.chapterPages[sChapter.url]
                        ?.toLongOrNull()
                        ?: throw IllegalArgumentException("Invalid novel page for ${sChapter.url}")
                    val indexInPage = pageChapterIndexes.getOrDefault(pageNumber, 0)
                    pageChapterIndexes[pageNumber] = indexInPage + 1
                    pagedSourceOrder(novelStructure.totalPages, pageNumber, indexInPage)
                } else {
                    i.toLong()
                }
                Chapter.create()
                    .copyFromSChapter(sChapter)
                    .copy(name = with(ChapterSanitizer) { sChapter.name.sanitize(manga.title) })
                    .copy(mangaId = manga.id, sourceOrder = sourceOrder)
            }

        val dbChapters = getChaptersByMangaId.await(manga.id)
        val replaceableChapterIds = if (novelStructure?.layout == NovelLayout.PAGED) {
            val returnedPages = novelStructure.chapterPages.values.toSet()
            novelStructureRepository.get(manga.id)
                ?.sections
                .orEmpty()
                .asSequence()
                .filter { it.name in returnedPages }
                .flatMap { it.chapterIds.asSequence() }
                .toSet()
        } else {
            null
        }

        val newChapters = mutableListOf<Chapter>()
        val updatedChapters = mutableListOf<Chapter>()
        val removedChapters = dbChapters.filterNot { dbChapter ->
            val returned = sourceChapters.any { sourceChapter ->
                dbChapter.url == sourceChapter.url
            }
            returned || (replaceableChapterIds != null && dbChapter.id !in replaceableChapterIds)
        }

        // Used to not set upload date of older chapters
        // to a higher value than newer chapters
        var maxSeenUploadDate = 0L

        for (sourceChapter in sourceChapters) {
            var chapter = sourceChapter

            // Update metadata from source if necessary.
            if (source is HttpSource) {
                val sChapter = chapter.toSChapter()
                @Suppress("DEPRECATION")
                source.prepareNewChapter(sChapter, manga.toSManga())
                chapter = chapter.copyFromSChapter(sChapter)
            }

            // Recognize chapter number for the chapter.
            val chapterNumber = ChapterRecognition.parseChapterNumber(manga.title, chapter.name, chapter.chapterNumber)
            chapter = chapter.copy(chapterNumber = chapterNumber)

            val dbChapter = dbChapters.find { it.url == chapter.url }

            if (dbChapter == null) {
                val toAddChapter = if (chapter.dateUpload == 0L) {
                    val altDateUpload = if (maxSeenUploadDate == 0L) nowMillis else maxSeenUploadDate
                    chapter.copy(dateUpload = altDateUpload)
                } else {
                    maxSeenUploadDate = max(maxSeenUploadDate, sourceChapter.dateUpload)
                    chapter
                }
                newChapters.add(toAddChapter)
            } else {
                if (shouldUpdateDbChapter.await(dbChapter, chapter)) {
                    val shouldRenameChapter = downloadProvider.isChapterDirNameChanged(dbChapter, chapter) &&
                        downloadManager.isChapterDownloaded(
                            dbChapter.name,
                            dbChapter.scanlator,
                            dbChapter.url,
                            manga.title,
                            manga.source,
                        )

                    if (shouldRenameChapter) {
                        downloadManager.renameChapter(source, manga, dbChapter, chapter)
                    }
                    if (dbChapter.name != chapter.name) {
                        translatedChapterRepository.renameChapter(
                            source.toString(),
                            manga.title,
                            dbChapter.name,
                            chapter.name,
                            chapter.url,
                        )
                    }

                    var toChangeChapter = dbChapter.copy(
                        name = chapter.name,
                        chapterNumber = chapter.chapterNumber,
                        scanlator = chapter.scanlator,
                        sourceOrder = chapter.sourceOrder,
                        memo = chapter.memo,
                    )

                    if (chapter.dateUpload != 0L) {
                        toChangeChapter = toChangeChapter.copy(dateUpload = chapter.dateUpload)
                    }
                    updatedChapters.add(toChangeChapter)
                }
            }
        }

        // Return if there's nothing to add, delete, or update to avoid unnecessary db transactions.
        if (newChapters.isEmpty() && removedChapters.isEmpty() && updatedChapters.isEmpty()) {
            replaceNovelStructure(manga.id, novelStructure, dbChapters)
            if (manualFetch || manga.fetchInterval == 0 || manga.nextUpdate < fetchWindow.first) {
                updateManga.awaitUpdateFetchInterval(
                    manga,
                    timeZone,
                    now,
                    fetchWindow,
                )
            }
            return emptyList()
        }

        val changedOrDuplicateReadUrls = mutableSetOf<String>()

        val deletedChapterNumbers = TreeSet<Double>()
        val deletedReadChapterNumbers = TreeSet<Double>()
        val deletedBookmarkedChapterNumbers = TreeSet<Double>()

        val readChapterNumbers = dbChapters
            .asSequence()
            .filter { it.read && it.isRecognizedNumber }
            .map { it.chapterNumber }
            .toSet()

        removedChapters.forEach { chapter ->
            if (chapter.read) deletedReadChapterNumbers.add(chapter.chapterNumber)
            if (chapter.bookmark) deletedBookmarkedChapterNumbers.add(chapter.chapterNumber)
            deletedChapterNumbers.add(chapter.chapterNumber)
        }

        val deletedChapterNumberDateFetchMap = removedChapters.sortedByDescending { it.dateFetch }
            .associate { it.chapterNumber to it.dateFetch }

        val markDuplicateAsRead = libraryPreferences.markDuplicateReadChapterAsRead.get()
            .contains(LibraryPreferences.MARK_DUPLICATE_CHAPTER_READ_NEW)

        // Date fetch is set in such a way that the upper ones will have bigger value than the lower ones
        // Sources MUST return the chapters from most to less recent, which is common.
        var itemCount = newChapters.size
        var updatedToAdd = newChapters.map { toAddItem ->
            var chapter = toAddItem.copy(dateFetch = nowMillis + itemCount--)

            if (chapter.chapterNumber in readChapterNumbers && markDuplicateAsRead) {
                changedOrDuplicateReadUrls.add(chapter.url)
                chapter = chapter.copy(read = true)
            }

            if (!chapter.isRecognizedNumber || chapter.chapterNumber !in deletedChapterNumbers) return@map chapter

            chapter = chapter.copy(
                read = chapter.chapterNumber in deletedReadChapterNumbers,
                bookmark = chapter.chapterNumber in deletedBookmarkedChapterNumbers,
            )

            // Try to to use the fetch date of the original entry to not pollute 'Updates' tab
            deletedChapterNumberDateFetchMap[chapter.chapterNumber]?.let {
                chapter = chapter.copy(dateFetch = it)
            }

            changedOrDuplicateReadUrls.add(chapter.url)

            chapter
        }

        if (removedChapters.isNotEmpty()) {
            val toDeleteIds = removedChapters.map { it.id }
            chapterRepository.removeChaptersWithIds(toDeleteIds)
        }

        if (updatedToAdd.isNotEmpty()) {
            updatedToAdd = chapterRepository.addAll(updatedToAdd)
        }

        if (updatedChapters.isNotEmpty()) {
            val chapterUpdates = updatedChapters.map { it.toChapterUpdate() }
            updateChapter.awaitAll(chapterUpdates)
        }
        replaceNovelStructure(
            mangaId = manga.id,
            structure = novelStructure,
            chapters = getChaptersByMangaId.await(manga.id),
        )
        updateManga.awaitUpdateFetchInterval(manga, timeZone, now, fetchWindow)

        // Set this manga as updated since chapters were changed
        // Note that last_update actually represents last time the chapter list changed at all
        updateManga.awaitUpdateLastUpdate(manga.id)

        val excludedScanlators = getExcludedScanlators.await(manga.id).toHashSet()

        return updatedToAdd.filterNot { it.url in changedOrDuplicateReadUrls || it.scanlator in excludedScanlators }
    }

    suspend fun awaitPage(
        rawSourceChapters: List<SChapter>,
        manga: Manga,
        source: Source,
        page: String,
    ) {
        if (rawSourceChapters.isEmpty()) throw NoChaptersException()
        val pageNumber = page.toLongOrNull()
            ?.takeIf { page == it.toString() && it >= 1 }
            ?: throw IllegalArgumentException("Invalid novel page: $page")
        val novelStructure = novelStructureRepository.get(manga.id)
            ?.takeIf { it.layout == NovelLayout.PAGED }
            ?: throw IllegalArgumentException("Novel ${manga.id} is not paged")
        val nowMillis = Clock.System.now().toEpochMilliseconds()
        val orderedChapters = if (source.id.toString() in libraryPreferences.reversedChapterSources.get()) {
            rawSourceChapters.reversed()
        } else {
            rawSourceChapters
        }
        val chapters = orderedChapters
            .distinctBy { it.url }
            .mapIndexed { index, sourceChapter ->
                Chapter.create()
                    .copyFromSChapter(sourceChapter)
                    .copy(
                        mangaId = manga.id,
                        name = with(ChapterSanitizer) { sourceChapter.name.sanitize(manga.title) },
                        chapterNumber = ChapterRecognition.parseChapterNumber(
                            manga.title,
                            sourceChapter.name,
                            sourceChapter.chapter_number.toDouble(),
                        ),
                        sourceOrder = pagedSourceOrder(novelStructure.totalPages, pageNumber, index),
                        dateFetch = nowMillis + orderedChapters.size - index,
                        dateUpload = sourceChapter.date_upload.takeIf { it != 0L } ?: nowMillis,
                    )
            }
        novelStructureRepository.reconcilePage(manga.id, page, chapters)
        updateManga.awaitUpdateLastUpdate(manga.id)
    }

    private suspend fun replaceNovelStructure(
        mangaId: Long,
        structure: NovelStructure?,
        chapters: List<Chapter>,
    ) {
        structure ?: return
        novelStructureRepository.replace(mangaId, structure, chapters)
    }
}

private const val PAGE_ORDER_SIZE = 1_000_000L

internal fun pagedSourceOrder(totalPages: Long, pageNumber: Long, indexInPage: Int): Long {
    require(totalPages >= 1)
    require(pageNumber in 1..totalPages)
    require(indexInPage >= 0 && indexInPage.toLong() < PAGE_ORDER_SIZE)
    return Math.subtractExact(
        indexInPage.toLong(),
        Math.multiplyExact(pageNumber - 1, PAGE_ORDER_SIZE),
    )
}
