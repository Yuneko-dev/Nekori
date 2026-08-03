package eu.kanade.tachiyomi.data.backup.restore.restorers

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupNovelStructure
import eu.kanade.tachiyomi.data.backup.models.BackupTracking
import tachiyomi.data.AlternativeTitlesColumnAdapter
import tachiyomi.data.Database
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.novel.model.NovelLayout
import tachiyomi.domain.novel.model.NovelSection
import tachiyomi.domain.novel.model.NovelStructureSnapshot
import tachiyomi.domain.novel.repository.NovelStructureRepository
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.track.model.Track
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.ZonedDateTime
import java.util.Date
import kotlin.math.max

class MangaRestorer(
    private val database: Database = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val getMangaByUrlAndSourceId: GetMangaByUrlAndSourceId = Injekt.get(),
    private val getChaptersByMangaId: GetChaptersByMangaId = Injekt.get(),
    private val updateManga: UpdateManga = Injekt.get(),
    private val getTracks: GetTracks = Injekt.get(),
    private val insertTrack: InsertTrack = Injekt.get(),
    private val novelStructureRepository: NovelStructureRepository = Injekt.get(),
    fetchInterval: FetchInterval = Injekt.get(),
) {

    private var now = ZonedDateTime.now()
    private var currentFetchWindow = fetchInterval.getWindow(now)

    init {
        now = ZonedDateTime.now()
        currentFetchWindow = fetchInterval.getWindow(now)
    }

    suspend fun sortByNew(backupMangas: List<BackupManga>): List<BackupManga> {
        val urlsBySource = database.mangasQueries
            .getAllMangaSourceAndUrl()
            .awaitAsList()
            .groupBy({ it.source }, { it.url })

        return backupMangas
            .sortedWith(
                compareBy<BackupManga> { it.url in urlsBySource[it.source].orEmpty() }
                    .then(compareByDescending { it.lastModifiedAt }),
            )
    }

    suspend fun restore(
        backupManga: BackupManga,
        backupCategories: List<BackupCategory>,
    ): Long {
        var mangaId = 0L
        database.transaction {
            val dbManga = findExistingManga(backupManga)
            val manga = backupManga.getMangaImpl()
            val restoredManga = if (dbManga == null) {
                restoreNewManga(manga)
            } else {
                restoreExistingManga(manga, dbManga)
            }

            restoreMangaDetails(
                manga = restoredManga,
                chapters = backupManga.chapters,
                categories = backupManga.categories,
                backupCategories = backupCategories,
                history = backupManga.history,
                tracks = backupManga.tracking,
                excludedScanlators = backupManga.excludedScanlators,
            )
            restoreNovelStructure(
                manga = restoredManga,
                structure = backupManga.novelStructure,
                createDefault = dbManga == null,
            )
            restoreReadingSessions(restoredManga, backupManga.chapters)
            mangaId = restoredManga.id
        }
        return mangaId
    }

    private suspend fun findExistingManga(backupManga: BackupManga): Manga? {
        return getMangaByUrlAndSourceId.await(backupManga.url, backupManga.source)
    }

    private suspend fun restoreExistingManga(manga: Manga, dbManga: Manga): Manga {
        return if (manga.version > dbManga.version) {
            updateManga(dbManga.copyFrom(manga).copy(id = dbManga.id))
        } else {
            updateManga(manga.copyFrom(dbManga).copy(id = dbManga.id))
        }
    }

    private fun Manga.copyFrom(newer: Manga): Manga {
        return this.copy(
            favorite = this.favorite || newer.favorite,
            author = newer.author,
            artist = newer.artist,
            description = newer.description,
            genre = newer.genre,
            thumbnailUrl = newer.thumbnailUrl,
            status = newer.status,
            initialized = this.initialized || newer.initialized,
            version = newer.version,
        )
    }

    private suspend fun updateManga(manga: Manga): Manga {
        database.mangasQueries.update(
            source = manga.source,
            url = manga.url,
            artist = manga.artist,
            author = manga.author,
            description = manga.description,
            genre = manga.genre?.joinToString(separator = ", "),
            title = manga.title,
            status = manga.status,
            thumbnailUrl = manga.thumbnailUrl,
            favorite = manga.favorite,
            lastUpdate = manga.lastUpdate,
            nextUpdate = null,
            calculateInterval = null,
            initialized = manga.initialized,
            viewer = manga.viewerFlags,
            chapterFlags = manga.chapterFlags,
            coverLastModified = manga.coverLastModified,
            dateAdded = manga.dateAdded,
            mangaId = manga.id,
            updateStrategy = manga.updateStrategy.let(UpdateStrategyColumnAdapter::encode),
            version = manga.version,
            isSyncing = 1,
            notes = manga.notes,
            alternativeTitles = manga.alternativeTitles.takeIf {
                it.isNotEmpty()
            }?.let { AlternativeTitlesColumnAdapter.encode(it) },
            isNovel = manga.isNovel,
            memo = manga.memo.let(MemoColumnAdapter::encode),
        )
        return manga
    }

    private suspend fun restoreNewManga(
        manga: Manga,
    ): Manga {
        return manga.copy(
            id = insertManga(manga),
        )
    }

    private suspend fun restoreChapters(manga: Manga, backupChapters: List<BackupChapter>) {
        val dbChaptersByUrl = getChaptersByMangaId.await(manga.id)
            .associateBy { it.url }

        val (existingChapters, newChapters) = backupChapters
            .mapNotNull {
                val chapter = it.toChapterImpl().copy(mangaId = manga.id)

                val dbChapter = dbChaptersByUrl[chapter.url]
                    ?: // New chapter
                    return@mapNotNull chapter

                if (chapter.forComparison() == dbChapter.forComparison()) {
                    // Same state; skip
                    return@mapNotNull null
                }

                // Update to an existing chapter
                var updatedChapter = chapter
                    .copyFrom(dbChapter)
                    .copy(
                        id = dbChapter.id,
                        bookmark = chapter.bookmark || dbChapter.bookmark,
                    )
                if (dbChapter.read && !updatedChapter.read) {
                    updatedChapter = updatedChapter.copy(
                        read = true,
                        lastPageRead = dbChapter.lastPageRead,
                    )
                } else if (updatedChapter.lastPageRead == 0L && dbChapter.lastPageRead != 0L) {
                    updatedChapter = updatedChapter.copy(
                        lastPageRead = dbChapter.lastPageRead,
                    )
                }
                updatedChapter
            }
            .partition { it.id > 0 }

        insertNewChapters(newChapters)
        updateExistingChapters(existingChapters)
    }

    private fun Chapter.forComparison() =
        this.copy(id = 0L, mangaId = 0L, dateFetch = 0L, dateUpload = 0L, lastModifiedAt = 0L, version = 0L)

    private suspend fun insertNewChapters(chapters: List<Chapter>) {
        database.transaction {
            chapters.forEach { chapter ->
                database.chaptersQueries.insert(
                    chapter.mangaId,
                    chapter.url,
                    chapter.name,
                    chapter.scanlator,
                    chapter.read,
                    chapter.bookmark,
                    chapter.lastPageRead,
                    chapter.chapterNumber,
                    chapter.sourceOrder,
                    chapter.dateFetch,
                    chapter.dateUpload,
                    chapter.version,
                    chapter.memo,
                )
            }
        }
    }

    private suspend fun updateExistingChapters(chapters: List<Chapter>) {
        database.transaction {
            chapters.forEach { chapter ->
                database.chaptersQueries.update(
                    mangaId = null,
                    url = null,
                    name = null,
                    scanlator = null,
                    read = chapter.read,
                    bookmark = chapter.bookmark,
                    lastPageRead = chapter.lastPageRead,
                    chapterNumber = null,
                    sourceOrder = null,
                    dateFetch = null,
                    dateUpload = null,
                    chapterId = chapter.id,
                    version = chapter.version,
                    isSyncing = 0,
                    memo = chapter.memo.let(MemoColumnAdapter::encode),
                )
            }
        }
    }

    /**
     * Inserts manga and returns id
     *
     * @return id of [Manga], null if not found
     */
    private suspend fun insertManga(manga: Manga): Long {
        return database.mangasQueries.insertReturningId(
            source = manga.source,
            url = manga.url,
            artist = manga.artist,
            author = manga.author,
            description = manga.description,
            genre = manga.genre,
            title = manga.title,
            status = manga.status,
            thumbnailUrl = manga.thumbnailUrl,
            favorite = manga.favorite,
            lastUpdate = manga.lastUpdate,
            nextUpdate = 0L,
            calculateInterval = 0L,
            initialized = manga.initialized,
            viewerFlags = manga.viewerFlags,
            chapterFlags = manga.chapterFlags,
            coverLastModified = manga.coverLastModified,
            dateAdded = manga.dateAdded,
            updateStrategy = manga.updateStrategy,
            version = manga.version,
            notes = manga.notes,
            alternativeTitles = manga.alternativeTitles,
            isNovel = manga.isNovel,
            memo = manga.memo,
        )
            .awaitAsOne()
    }

    private suspend fun restoreMangaDetails(
        manga: Manga,
        chapters: List<BackupChapter>,
        categories: List<Long>,
        backupCategories: List<BackupCategory>,
        history: List<BackupHistory>,
        tracks: List<BackupTracking>,
        excludedScanlators: List<String>,
    ): Manga {
        restoreCategories(manga, categories, backupCategories)
        restoreChapters(manga, chapters)
        restoreTracking(manga, tracks)
        restoreHistory(manga, history)
        restoreExcludedScanlators(manga, excludedScanlators)
        updateManga.awaitUpdateFetchInterval(manga, now, currentFetchWindow)
        return manga
    }

    /**
     * Restores the categories a manga is in.
     *
     * @param manga the manga whose categories have to be restored.
     * @param categories the categories to restore.
     */
    private suspend fun restoreCategories(
        manga: Manga,
        categories: List<Long>,
        backupCategories: List<BackupCategory>,
    ) {
        val dbCategories = getCategories.await()
        val dbCategoriesByName = dbCategories.groupBy { it.name }

        val backupCategoriesByOrder = backupCategories.associateBy { it.order }

        val mangaCategoriesToUpdate = categories.mapNotNull { backupCategoryOrder ->
            backupCategoriesByOrder[backupCategoryOrder]?.let { backupCategory ->
                val dbCategory = if (backupCategory.contentType !=
                    tachiyomi.domain.category.model.Category.CONTENT_TYPE_ALL
                ) {
                    dbCategoriesByName[backupCategory.name]?.firstOrNull {
                        it.contentType == backupCategory.contentType
                    }
                } else {
                    dbCategoriesByName[backupCategory.name]?.firstOrNull()
                }
                dbCategory?.let { Pair(manga.id, it.id) }
            }
        }

        if (mangaCategoriesToUpdate.isNotEmpty()) {
            database.transaction {
                database.mangas_categoriesQueries.deleteMangaCategoryByMangaId(manga.id)
                mangaCategoriesToUpdate.forEach { (mangaId, categoryId) ->
                    database.mangas_categoriesQueries.insert(mangaId, categoryId)
                }
            }
        }
    }

    private suspend fun restoreHistory(manga: Manga, backupHistory: List<BackupHistory>) {
        if (backupHistory.isEmpty()) return

        val chaptersByUrl = getChaptersByMangaId.await(manga.id).associateBy { it.url }
        val existingHistory = database.historyQueries.getHistoryByMangaId(manga.id)
            .awaitAsList()
            .associateBy { it.chapter_id }

        val toUpdate = backupHistory.mapNotNull { history ->
            val chapter = chaptersByUrl[history.url] ?: return@mapNotNull null
            val item = history.getHistoryImpl()
            val dbHistory = existingHistory[chapter.id]

            if (dbHistory == null) {
                item.copy(chapterId = chapter.id)
            } else {
                item.copy(
                    id = dbHistory._id,
                    chapterId = dbHistory.chapter_id,
                    readAt = max(item.readAt?.time ?: 0L, dbHistory.last_read?.time ?: 0L)
                        .takeIf { it > 0L }
                        ?.let { Date(it) },
                    readDuration = max(item.readDuration, dbHistory.time_read) - dbHistory.time_read,
                )
            }
        }

        if (toUpdate.isEmpty()) return
        database.transaction {
            toUpdate.forEach {
                database.historyQueries.upsert(
                    it.chapterId,
                    it.readAt,
                    it.readDuration,
                )
            }
        }
    }

    private suspend fun restoreTracking(manga: Manga, backupTracks: List<BackupTracking>) {
        val dbTrackByTrackerId = getTracks.await(manga.id).associateBy { it.trackerId }

        val (existingTracks, newTracks) = backupTracks
            .mapNotNull {
                val track = it.getTrackImpl()
                val dbTrack = dbTrackByTrackerId[track.trackerId]
                    ?: // New track
                    return@mapNotNull track.copy(
                        id = 0, // Let DB assign new ID
                        mangaId = manga.id,
                    )

                if (track.forComparison() == dbTrack.forComparison()) {
                    // Same state; skip
                    return@mapNotNull null
                }

                // Update to an existing track
                dbTrack.copy(
                    remoteId = track.remoteId,
                    libraryId = track.libraryId,
                    lastChapterRead = max(dbTrack.lastChapterRead, track.lastChapterRead),
                )
            }
            .partition { it.id > 0 }

        if (newTracks.isNotEmpty()) {
            insertTrack.awaitAll(newTracks)
        }

        if (existingTracks.isEmpty()) return
        database.transaction {
            existingTracks.forEach { track ->
                database.manga_syncQueries.update(
                    track.mangaId,
                    track.trackerId,
                    track.remoteId,
                    track.libraryId,
                    track.title,
                    track.lastChapterRead,
                    track.totalChapters,
                    track.status,
                    track.score,
                    track.remoteUrl,
                    track.startDate,
                    track.finishDate,
                    track.private,
                    track.id,
                )
            }
        }
    }

    private fun Track.forComparison() = this.copy(id = 0L, mangaId = 0L)

    /**
     * Restores the excluded scanlators for the manga.
     *
     * @param manga the manga whose excluded scanlators have to be restored.
     * @param excludedScanlators the excluded scanlators to restore.
     */
    private suspend fun restoreExcludedScanlators(manga: Manga, excludedScanlators: List<String>) {
        if (excludedScanlators.isEmpty()) return
        val existingExcludedScanlators = database.excluded_scanlatorsQueries
            .getExcludedScanlatorsByMangaId(manga.id)
            .awaitAsList()
        val toInsert = excludedScanlators.filter { it !in existingExcludedScanlators }
        if (toInsert.isEmpty()) return
        toInsert.forEach { database.excluded_scanlatorsQueries.insert(manga.id, it) }
    }

    private suspend fun restoreNovelStructure(
        manga: Manga,
        structure: BackupNovelStructure?,
        createDefault: Boolean,
    ) {
        if (structure == null && !createDefault) return
        val chapters = getChaptersByMangaId.await(manga.id)
        val chaptersByUrl = chapters.associateBy { it.url }
        val snapshot = if (structure == null) {
            NovelStructureSnapshot(
                layout = NovelLayout.FLAT,
                totalPages = 0,
                sections = listOf(
                    NovelSection(
                        name = DEFAULT_SECTION,
                        pageNumber = null,
                        path = null,
                        cover = null,
                        chapterIds = chapters.sortedBy { it.sourceOrder }.map { it.id },
                    ),
                ),
            )
        } else {
            val layout = structure.novelLayout()
            require(
                if (layout == NovelLayout.PAGED) {
                    structure.totalPages >= 1
                } else {
                    structure.totalPages == 0L
                },
            ) {
                "Invalid novel structure totalPages"
            }
            NovelStructureSnapshot(
                layout = layout,
                totalPages = structure.totalPages,
                sections = structure.sections.map { section ->
                    if (layout == NovelLayout.PAGED) {
                        require(section.pageNumber != null && section.name == section.pageNumber.toString()) {
                            "Invalid paged novel section: ${section.name}"
                        }
                    }
                    NovelSection(
                        name = section.name,
                        pageNumber = section.pageNumber,
                        path = section.path,
                        cover = section.cover,
                        chapterIds = section.chapterUrls.mapNotNull { chaptersByUrl[it]?.id },
                    )
                },
            )
        }
        novelStructureRepository.replaceSnapshot(manga.id, snapshot)
    }

    private suspend fun restoreReadingSessions(manga: Manga, chapters: List<BackupChapter>) {
        val chapterIdsByUrl = getChaptersByMangaId.await(manga.id).associate { it.url to it.id }
        chapters.forEach chapterLoop@{ chapter ->
            val chapterId = chapterIdsByUrl[chapter.url] ?: return@chapterLoop
            val existing = database.reading_sessionsQueries.getByChapterId(chapterId)
                .awaitAsList()
                .mapTo(mutableSetOf()) { Triple(it.started_at, it.ended_at, it.read_duration) }
            chapter.readingSessions.forEach sessionLoop@{ session ->
                val identity = Triple(session.startedAt, session.endedAt, session.readDuration)
                if (session.endedAt < session.startedAt || session.readDuration < 0 || !existing.add(identity)) {
                    return@sessionLoop
                }
                database.reading_sessionsQueries.insert(
                    chapterId = chapterId,
                    startedAt = session.startedAt,
                    endedAt = session.endedAt,
                    readDuration = session.readDuration,
                )
            }
        }
    }

    private companion object {
        const val DEFAULT_SECTION = "Default"
    }
}
