package tachiyomi.data.novel

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import kotlinx.serialization.json.JsonObject
import tachiyomi.data.Database
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.novel.model.NovelLayout
import tachiyomi.domain.novel.model.NovelSection
import tachiyomi.domain.novel.model.NovelStructure
import tachiyomi.domain.novel.model.NovelStructureSnapshot
import tachiyomi.domain.novel.repository.NovelStructureRepository

class NovelStructureRepositoryImpl(
    private val database: Database,
) : NovelStructureRepository {

    override suspend fun get(mangaId: Long): NovelStructureSnapshot? {
        val layout = database.novel_structureQueries.getLayoutByMangaId(mangaId) { _, value, totalPages ->
            NovelLayout.entries.firstOrNull { it.value == value } to totalPages
        }.awaitAsOneOrNull() ?: return null
        val novelLayout = layout.first ?: return null
        val sections = database.novel_structureQueries.getSectionsByMangaId(mangaId, ::mapSection).awaitAsList()
        val chapterIdsBySection = database.novel_structureQueries.getChapterSectionsByMangaId(
            mangaId,
        ) { chapterId, sectionId, _ ->
            sectionId to chapterId
        }.awaitAsList().groupBy({ it.first }, { it.second })

        return NovelStructureSnapshot(
            layout = novelLayout,
            totalPages = layout.second,
            sections = sections.map { section ->
                NovelSection(
                    name = section.name,
                    pageNumber = section.pageNumber,
                    path = section.path,
                    cover = section.cover,
                    chapterIds = chapterIdsBySection[section.id].orEmpty(),
                )
            },
        )
    }

    override suspend fun replace(
        mangaId: Long,
        structure: NovelStructure,
        chapters: List<Chapter>,
    ) {
        database.transaction {
            database.novel_structureQueries.upsertLayout(
                mangaId = mangaId,
                layout = structure.layout.value,
                totalPages = structure.totalPages,
            )
            if (structure.layout != NovelLayout.PAGED) {
                database.novel_structureQueries.deleteSectionsByMangaId(mangaId)
            }

            val orderedChapters = chapters
                .asSequence()
                .filter { it.url in structure.chapterPages }
                .sortedBy { it.sourceOrder }
                .toList()
            val sectionNames = when (structure.layout) {
                NovelLayout.FLAT -> listOf(DEFAULT_SECTION)
                NovelLayout.VOLUME -> structure.chapterPages.values.distinct()
                NovelLayout.PAGED -> structure.chapterPages.values.distinct().sortedBy(String::toLong)
            }

            val sectionIds = sectionNames.mapIndexed { index, name ->
                val pageNumber = name.toLongOrNull().takeIf { structure.layout == NovelLayout.PAGED }
                val existingId = if (structure.layout == NovelLayout.PAGED) {
                    database.novel_structureQueries.getSectionByMangaIdAndName(
                        mangaId,
                        name,
                    ) { id, _, _, _, _, _, _, _ -> id }.awaitAsOneOrNull()
                } else {
                    null
                }
                val sectionId = existingId ?: database.novel_structureQueries.insertSection(
                    mangaId = mangaId,
                    kind = structure.layout.sectionKind,
                    name = name,
                    pageNumber = pageNumber,
                    path = null,
                    cover = null,
                    sortOrder = if (pageNumber != null) pageNumber - 1 else index.toLong(),
                ).awaitAsOne()
                if (existingId != null) {
                    database.novel_structureQueries.deleteChapterSectionsBySectionId(sectionId)
                }
                name to sectionId
            }.toMap()

            val positions = mutableMapOf<String, Long>()
            orderedChapters.forEach { chapter ->
                val sectionName = structure.chapterPages.getValue(chapter.url)
                val sectionId = sectionIds[sectionName] ?: return@forEach
                val position = positions.getOrDefault(sectionName, 0)
                database.novel_structureQueries.deleteChapterSectionByChapterId(chapter.id)
                database.novel_structureQueries.insertChapterSection(
                    chapterId = chapter.id,
                    sectionId = sectionId,
                    position = position,
                )
                positions[sectionName] = position + 1
            }
        }
    }

    override suspend fun reconcilePage(
        mangaId: Long,
        page: String,
        chapters: List<Chapter>,
    ) {
        database.transaction {
            val layout = database.novel_structureQueries.getLayoutByMangaId(mangaId) { _, value, totalPages ->
                value to totalPages
            }.awaitAsOneOrNull() ?: error("Novel structure is missing")
            val pageNumber = page.toLongOrNull()
                ?.takeIf { page == it.toString() && it in 1..layout.second }
                ?: error("Invalid novel page: $page")
            check(layout.first == NovelLayout.PAGED.value) { "Novel is not paged" }

            val existingSectionId = database.novel_structureQueries.getSectionByMangaIdAndName(
                mangaId,
                page,
            ) { id, _, _, _, _, _, _, _ -> id }.awaitAsOneOrNull()
            val sectionId = existingSectionId ?: database.novel_structureQueries.insertSection(
                mangaId = mangaId,
                kind = NovelLayout.PAGED.sectionKind,
                name = page,
                pageNumber = pageNumber,
                path = null,
                cover = null,
                sortOrder = pageNumber - 1,
            ).awaitAsOne()

            val oldPageChapterIds = database.novel_structureQueries.getChapterSectionsByMangaId(
                mangaId,
            ) { chapterId, placedSectionId, _ ->
                placedSectionId to chapterId
            }.awaitAsList()
                .asSequence()
                .filter { it.first == sectionId }
                .map { it.second }
                .toSet()
            val existingChapters = database.chaptersQueries.getChaptersByMangaIdUnfiltered(
                mangaId,
                ::mapChapter,
            ).awaitAsList()
            val existingByUrl = existingChapters.associateBy { it.url }

            val desiredChapterIds = chapters.distinctBy { it.url }.mapIndexed { index, chapter ->
                val existing = existingByUrl[chapter.url]
                val chapterId = if (existing == null) {
                    database.chaptersQueries.insertReturningId(
                        mangaId = mangaId,
                        url = chapter.url,
                        name = chapter.name,
                        scanlator = chapter.scanlator,
                        read = chapter.read,
                        bookmark = chapter.bookmark,
                        lastPageRead = chapter.lastPageRead,
                        chapterNumber = chapter.chapterNumber,
                        sourceOrder = chapter.sourceOrder,
                        dateFetch = chapter.dateFetch,
                        dateUpload = chapter.dateUpload,
                        version = chapter.version,
                        memo = chapter.memo,
                    ).awaitAsOne()
                } else {
                    database.chaptersQueries.update(
                        mangaId = null,
                        url = null,
                        name = chapter.name,
                        scanlator = chapter.scanlator,
                        read = null,
                        bookmark = null,
                        lastPageRead = null,
                        chapterNumber = chapter.chapterNumber,
                        sourceOrder = chapter.sourceOrder,
                        dateFetch = null,
                        dateUpload = chapter.dateUpload.takeIf { it != 0L },
                        chapterId = existing.id,
                        version = null,
                        isSyncing = 0,
                        memo = null,
                    )
                    existing.id
                }
                database.novel_structureQueries.deleteChapterSectionByChapterId(chapterId)
                database.novel_structureQueries.insertChapterSection(
                    chapterId = chapterId,
                    sectionId = sectionId,
                    position = index.toLong(),
                )
                chapterId
            }.toSet()

            val staleChapterIds = oldPageChapterIds - desiredChapterIds
            if (staleChapterIds.isNotEmpty()) {
                database.chaptersQueries.removeChaptersWithIds(staleChapterIds.toList())
            }
        }
    }

    private fun mapSection(
        id: Long,
        @Suppress("UNUSED_PARAMETER") mangaId: Long,
        @Suppress("UNUSED_PARAMETER") kind: Long,
        name: String,
        pageNumber: Long?,
        path: String?,
        cover: String?,
        @Suppress("UNUSED_PARAMETER") sortOrder: Long,
    ) = DbSection(id, name, pageNumber, path, cover)

    private fun mapChapter(
        id: Long,
        mangaId: Long,
        url: String,
        name: String,
        scanlator: String?,
        read: Boolean,
        bookmark: Boolean,
        lastPageRead: Long,
        chapterNumber: Double,
        sourceOrder: Long,
        dateFetch: Long,
        dateUpload: Long,
        lastModifiedAt: Long,
        version: Long,
        @Suppress("UNUSED_PARAMETER") isSyncing: Long,
        memo: JsonObject,
    ) = Chapter(
        id = id,
        mangaId = mangaId,
        url = url,
        name = name,
        scanlator = scanlator,
        read = read,
        bookmark = bookmark,
        lastPageRead = lastPageRead,
        chapterNumber = chapterNumber,
        sourceOrder = sourceOrder,
        dateFetch = dateFetch,
        dateUpload = dateUpload,
        lastModifiedAt = lastModifiedAt,
        version = version,
        memo = memo,
    )

    private val NovelLayout.sectionKind: Long
        get() = when (this) {
            NovelLayout.FLAT -> 0
            NovelLayout.VOLUME -> 1
            NovelLayout.PAGED -> 2
        }

    private data class DbSection(
        val id: Long,
        val name: String,
        val pageNumber: Long?,
        val path: String?,
        val cover: String?,
    )

    private companion object {
        const val DEFAULT_SECTION = "Default"
    }
}
