package eu.kanade.tachiyomi.data.backup.create.creators

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupNovelSection
import eu.kanade.tachiyomi.data.backup.models.BackupNovelStructure
import eu.kanade.tachiyomi.data.backup.models.BackupReadingSession
import eu.kanade.tachiyomi.data.backup.models.backupChapterRawMemoMapper
import eu.kanade.tachiyomi.data.backup.models.backupTrackMapper
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import tachiyomi.data.Database
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.novel.repository.NovelStructureRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MangaBackupCreator(
    private val database: Database = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val getHistory: GetHistory = Injekt.get(),
    private val novelStructureRepository: NovelStructureRepository = Injekt.get(),
) {

    /**
     * Creates backup manga entries using a streaming approach.
     * This avoids loading all chapters into memory at once, which prevents OOM on large libraries.
     * Each manga is processed and emitted individually to allow streaming to output.
     */
    fun backupMangaStream(
        mangas: List<Manga>,
        options: BackupOptions,
        allowedCategoryOrders: Set<Long>,
    ): Flow<BackupManga> = flow {
        for (manga in mangas) {
            emit(backupManga(manga, options, allowedCategoryOrders))
            // Yield between each manga to allow other coroutines to run
            // and prevent connection pool starvation
            kotlinx.coroutines.yield()
        }
    }

    private suspend fun backupManga(
        manga: Manga,
        options: BackupOptions,
        allowedCategoryOrders: Set<Long>,
    ): BackupManga {
        // Entry for this manga
        val mangaObject = manga.toBackupManga()

        mangaObject.excludedScanlators = database.excluded_scanlatorsQueries
            .getExcludedScanlatorsByMangaId(manga.id)
            .awaitAsList()

        if (options.chapters) {
            // Backup all the chapters. The raw-memo query returns memo as bytes (no per-chapter
            // JSON decode/re-encode), which was the main allocation hotspot on large libraries.
            val allChapters = database.chaptersQueries
                .getChaptersByMangaIdForBackup(
                    mangaId = manga.id,
                    mapper = backupChapterRawMemoMapper,
                )
                .awaitAsList()

            if (allChapters.isNotEmpty()) {
                val sessionsByUrl = database.reading_sessionsQueries
                    .getByMangaIdForBackup(manga.id) { url, startedAt, endedAt, readDuration ->
                        url to BackupReadingSession(startedAt, endedAt, readDuration)
                    }
                    .awaitAsList()
                    .groupBy({ it.first }, { it.second })
                allChapters.forEach { it.readingSessions = sessionsByUrl[it.url].orEmpty() }
                mangaObject.chapters = allChapters
            }

            val snapshot = novelStructureRepository.get(manga.id)
            if (snapshot != null) {
                val chapterUrlsById = database.chaptersQueries
                    .getChapterIdUrlsByMangaId(manga.id) { id, url -> id to url }
                    .awaitAsList()
                    .toMap()
                mangaObject.novelStructure = BackupNovelStructure(
                    layout = snapshot.layout.value,
                    totalPages = snapshot.totalPages,
                    sections = snapshot.sections.map { section ->
                        BackupNovelSection(
                            name = section.name,
                            pageNumber = section.pageNumber,
                            path = section.path,
                            cover = section.cover,
                            chapterUrls = section.chapterIds.mapNotNull(chapterUrlsById::get),
                        )
                    },
                )
            }
        }

        if (options.categories) {
            // Backup categories for this manga
            val categoriesForManga = getCategories.await(manga.id)
            if (categoriesForManga.isNotEmpty()) {
                mangaObject.categories = categoriesForManga
                    .map { it.order }
                    .filter { allowedCategoryOrders.isEmpty() || it in allowedCategoryOrders }
            }
        }

        if (options.tracking) {
            val tracks = database.manga_syncQueries
                .getTracksByMangaId(manga.id, backupTrackMapper)
                .awaitAsList()
            if (tracks.isNotEmpty()) {
                mangaObject.tracking = tracks
            }
        }

        if (options.history) {
            val historyByMangaId = getHistory.await(manga.id)
            if (historyByMangaId.isNotEmpty()) {
                val history = historyByMangaId.map { history ->
                    val chapter = database.chaptersQueries
                        .getChapterById(history.chapterId)
                        .awaitAsOne()
                    BackupHistory(chapter.url, history.readAt?.time ?: 0L, history.readDuration)
                }
                if (history.isNotEmpty()) {
                    mangaObject.history = history
                }
            }
        }

        return mangaObject
    }
}

private fun Manga.toBackupManga() =
    BackupManga(
        url = this.url,
        title = this.title,
        artist = this.artist,
        author = this.author,
        description = this.description,
        genre = this.genre.orEmpty(),
        alternativeTitles = this.alternativeTitles,
        status = this.status.toInt(),
        thumbnailUrl = this.thumbnailUrl,
        favorite = this.favorite,
        source = this.source,
        dateAdded = this.dateAdded,
        viewer = (this.viewerFlags.toInt() and ReadingMode.MASK),
        viewer_flags = this.viewerFlags.toInt(),
        chapterFlags = this.chapterFlags.toInt(),
        updateStrategy = this.updateStrategy,
        lastModifiedAt = this.lastModifiedAt,
        favoriteModifiedAt = this.favoriteModifiedAt,
        version = this.version,
        notes = this.notes,
        initialized = this.initialized,
        isNovel = this.isNovel,
        memo = MemoColumnAdapter.encode(this.memo),
    )
