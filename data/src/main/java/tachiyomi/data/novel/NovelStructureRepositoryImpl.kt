package tachiyomi.data.novel

import app.cash.sqldelight.async.coroutines.awaitAsOne
import tachiyomi.data.Database
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.novel.model.NovelLayout
import tachiyomi.domain.novel.model.NovelStructure
import tachiyomi.domain.novel.repository.NovelStructureRepository

class NovelStructureRepositoryImpl(
    private val database: Database,
) : NovelStructureRepository {

    override suspend fun replace(
        mangaId: Long,
        structure: NovelStructure,
        chapters: List<Chapter>,
    ) {
        database.transaction {
            database.novel_structureQueries.deleteSectionsByMangaId(mangaId)
            database.novel_structureQueries.upsertLayout(
                mangaId = mangaId,
                layout = structure.layout.value,
                totalPages = structure.totalPages,
            )

            val orderedChapters = chapters
                .asSequence()
                .filter { it.url in structure.chapterPages }
                .sortedBy { it.sourceOrder }
                .toList()
            val sectionNames = orderedChapters
                .mapNotNull { structure.chapterPages[it.url] }
                .distinct()
                .let { names ->
                    when (structure.layout) {
                        NovelLayout.FLAT -> listOf(DEFAULT_SECTION)
                        NovelLayout.VOLUME -> names
                        NovelLayout.PAGED -> names.sortedBy(String::toLong)
                    }
                }

            val sectionIds = sectionNames.mapIndexed { index, name ->
                val pageNumber = name.toLongOrNull().takeIf { structure.layout == NovelLayout.PAGED }
                name to database.novel_structureQueries.insertSection(
                    mangaId = mangaId,
                    kind = structure.layout.sectionKind,
                    name = name,
                    pageNumber = pageNumber,
                    path = null,
                    cover = null,
                    sortOrder = if (pageNumber != null) pageNumber - 1 else index.toLong(),
                ).awaitAsOne()
            }.toMap()

            val positions = mutableMapOf<String, Long>()
            orderedChapters.forEach { chapter ->
                val sectionName = structure.chapterPages.getValue(chapter.url)
                val sectionId = sectionIds[sectionName] ?: return@forEach
                val position = positions.getOrDefault(sectionName, 0)
                database.novel_structureQueries.insertChapterSection(
                    chapterId = chapter.id,
                    sectionId = sectionId,
                    position = position,
                )
                positions[sectionName] = position + 1
            }
        }
    }

    private val NovelLayout.sectionKind: Long
        get() = when (this) {
            NovelLayout.FLAT -> 0
            NovelLayout.VOLUME -> 1
            NovelLayout.PAGED -> 2
        }

    private companion object {
        const val DEFAULT_SECTION = "Default"
    }
}
