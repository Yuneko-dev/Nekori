package mihon.domain.source.interactor

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.novel.PagedNovelSource
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.novel.model.NovelLayout
import tachiyomi.domain.novel.model.NovelStructure
import tachiyomi.domain.novel.model.NovelStructureSnapshot

class PagedNovelUpdateTest {

    @Test
    fun `first load leaves non-first pages lazy`() {
        val pages = selectPagedUpdatePages(previous = null, newTotalPages = 7)

        assertEquals(emptyList<Long>(), pages)
    }

    @Test
    fun `refreshes the previous tail and newly declared pages`() {
        val pages = selectPagedUpdatePages(pagedSnapshot(totalPages = 5), newTotalPages = 7)

        assertEquals(listOf(5L, 6L, 7L), pages)
    }

    @Test
    fun `refreshes the tail when the page count is unchanged`() {
        val pages = selectPagedUpdatePages(pagedSnapshot(totalPages = 5), newTotalPages = 5)

        assertEquals(listOf(5L), pages)
    }

    @Test
    fun `chapter moved to a new page keeps one canonical entry`() {
        val pageOne = chapter("/one")
        val moved = chapter("/moved")
        val oldTail = chapter("/old-tail")
        val newTail = chapter("/new-tail")
        val initialStructure = NovelStructure(
            layout = NovelLayout.PAGED,
            totalPages = 6,
            chapterPages = mapOf(pageOne.url to "1"),
        )

        val update = mergePagedUpdate(
            initialChapters = listOf(pageOne),
            initialStructure = initialStructure,
            fetchedPages = linkedMapOf(
                5L to listOf(moved, oldTail),
                6L to listOf(moved, newTail),
            ),
        )

        assertEquals(listOf("/one", "/old-tail", "/moved", "/new-tail"), update.chapters.map { it.url })
        assertEquals("6", update.structure.chapterPages.getValue(moved.url))
        assertEquals(update.chapters.size, update.chapters.distinctBy { it.url }.size)
    }

    @Test
    fun `failed frontier page does not block later pages`() = runBlocking {
        val requestedPages = mutableListOf<String>()
        val source = object : PagedNovelSource {
            override fun getNovelStructure(mangaUrl: String): NovelStructure? = null

            override suspend fun getPage(
                mangaUrl: String,
                page: String,
                forceRefresh: Boolean,
            ): List<SChapter> {
                requestedPages += page
                if (page == "6") error("broken page")
                return listOf(chapter("/$page"))
            }
        }
        val pageOne = chapter("/one")
        val update = source.fetchPagedNovelUpdate(
            mangaUrl = "/novel",
            initialChapters = listOf(pageOne),
            initialStructure = NovelStructure(
                layout = NovelLayout.PAGED,
                totalPages = 7,
                chapterPages = mapOf(pageOne.url to "1"),
            ),
            previousStructure = pagedSnapshot(totalPages = 5),
        )

        assertEquals(listOf("5", "6", "7"), requestedPages)
        assertEquals(listOf("/one", "/5", "/7"), update.chapters.map { it.url })
    }

    private fun pagedSnapshot(totalPages: Long) = NovelStructureSnapshot(
        layout = NovelLayout.PAGED,
        totalPages = totalPages,
        sections = emptyList(),
    )

    private fun chapter(path: String) = SChapter.create().apply {
        url = path
        name = path
    }
}
