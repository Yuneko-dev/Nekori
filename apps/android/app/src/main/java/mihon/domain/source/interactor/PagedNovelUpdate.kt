package mihon.domain.source.interactor

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.novel.PagedNovelSource
import kotlinx.coroutines.CancellationException
import tachiyomi.domain.novel.model.NovelLayout
import tachiyomi.domain.novel.model.NovelStructure
import tachiyomi.domain.novel.model.NovelStructureSnapshot

internal data class PagedNovelUpdate(
    val chapters: List<SChapter>,
    val structure: NovelStructure,
)

internal fun selectPagedUpdatePages(
    previous: NovelStructureSnapshot?,
    newTotalPages: Long,
): List<Long> {
    if (previous?.layout != NovelLayout.PAGED || previous.totalPages < 1 || newTotalPages < 1) {
        return emptyList()
    }

    return buildList {
        val previousTail = minOf(previous.totalPages, newTotalPages)
        if (previousTail > 1) add(previousTail)
        if (newTotalPages > previous.totalPages) addAll((previous.totalPages + 1)..newTotalPages)
    }
}

internal suspend fun PagedNovelSource.fetchPagedNovelUpdate(
    mangaUrl: String,
    initialChapters: List<SChapter>,
    initialStructure: NovelStructure,
    previousStructure: NovelStructureSnapshot?,
): PagedNovelUpdate {
    val pages = selectPagedUpdatePages(previousStructure, initialStructure.totalPages)
    val fetchedPages = buildMap {
        pages.forEach { page ->
            try {
                put(page, getPage(mangaUrl, page.toString(), forceRefresh = true))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // LNReader lets the remaining update pages continue.
            }
        }
    }
    return mergePagedUpdate(initialChapters, initialStructure, fetchedPages)
}

internal fun mergePagedUpdate(
    initialChapters: List<SChapter>,
    initialStructure: NovelStructure,
    fetchedPages: Map<Long, List<SChapter>>,
): PagedNovelUpdate {
    require(initialStructure.layout == NovelLayout.PAGED)

    val chaptersByPage = initialChapters
        .groupBy { chapter -> initialStructure.chapterPages.getValue(chapter.url) }
        .mapKeysTo(sortedMapOf()) { (page) -> page.toLong() }
    fetchedPages.forEach { (page, chapters) ->
        require(page in 1..initialStructure.totalPages)
        chaptersByPage[page] = chapters
    }

    val winningPageByUrl = buildMap {
        chaptersByPage.forEach { (page, chapters) ->
            chapters.forEach { chapter -> put(chapter.url, page) }
        }
    }
    val chapters = chaptersByPage.flatMap { (page, pageChapters) ->
        pageChapters.distinctBy { it.url }.filter { winningPageByUrl[it.url] == page }
    }
    val chapterPages = chapters.associate { chapter ->
        chapter.url to winningPageByUrl.getValue(chapter.url).toString()
    }

    return PagedNovelUpdate(
        chapters = chapters,
        structure = initialStructure.copy(chapterPages = chapterPages),
    )
}
