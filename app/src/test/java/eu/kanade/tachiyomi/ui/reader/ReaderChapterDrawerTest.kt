package eu.kanade.tachiyomi.ui.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.novel.model.NovelLayout
import tachiyomi.domain.novel.model.NovelSection
import tachiyomi.domain.novel.model.NovelStructureSnapshot

class ReaderChapterDrawerTest {

    @Test
    fun `flat novel keeps the full reader order`() {
        val snapshot = buildReaderChapterDrawerSnapshot(
            items = items(1L, 2L, 3L),
            structure = structure(
                layout = NovelLayout.FLAT,
                sections = listOf(section("Default", 1L, 2L, 3L)),
            ),
            currentChapterId = 2L,
        )

        snapshot!!.sectionKey shouldBe "flat"
        snapshot.items.map { it.id } shouldBe listOf(1L, 2L, 3L)
        snapshot.currentIndex shouldBe 1
    }

    @Test
    fun `volume novel keeps only the current volume`() {
        val snapshot = buildReaderChapterDrawerSnapshot(
            items = items(1L, 2L, 3L, 4L),
            structure = structure(
                layout = NovelLayout.VOLUME,
                sections = listOf(
                    section("Volume 1", 1L, 2L),
                    section("Volume 2", 3L, 4L),
                ),
            ),
            currentChapterId = 3L,
        )

        snapshot!!.sectionKey shouldBe "volume:Volume 2"
        snapshot.items.map { it.id } shouldBe listOf(3L, 4L)
    }

    @Test
    fun `paged novel keeps only the current loaded page`() {
        val snapshot = buildReaderChapterDrawerSnapshot(
            items = items(1L, 2L, 51L, 52L),
            structure = structure(
                layout = NovelLayout.PAGED,
                totalPages = 76,
                sections = listOf(
                    section("1", 1L, 2L),
                    section("2", 51L, 52L),
                ),
            ),
            currentChapterId = 51L,
        )

        snapshot!!.sectionKey shouldBe "page:2"
        snapshot.items.map { it.id } shouldBe listOf(51L, 52L)
    }

    @Test
    fun `single volume behaves like a flat novel`() {
        val snapshot = buildReaderChapterDrawerSnapshot(
            items = items(1L, 2L, 3L),
            structure = structure(
                layout = NovelLayout.VOLUME,
                sections = listOf(section("Only volume", 1L, 2L, 3L)),
            ),
            currentChapterId = 2L,
        )

        snapshot!!.sectionKey shouldBe "flat"
        snapshot.items.map { it.id } shouldBe listOf(1L, 2L, 3L)
    }

    @Test
    fun `single page behaves like a flat novel`() {
        val snapshot = buildReaderChapterDrawerSnapshot(
            items = items(1L, 2L, 3L),
            structure = structure(
                layout = NovelLayout.PAGED,
                totalPages = 1,
                sections = listOf(section("1", 1L, 2L, 3L)),
            ),
            currentChapterId = 2L,
        )

        snapshot!!.sectionKey shouldBe "flat"
        snapshot.items.map { it.id } shouldBe listOf(1L, 2L, 3L)
    }

    @Test
    fun `missing section membership exposes only the current chapter`() {
        val snapshot = buildReaderChapterDrawerSnapshot(
            items = items(1L, 2L, 3L),
            structure = structure(
                layout = NovelLayout.PAGED,
                totalPages = 2,
                sections = listOf(section("1", 1L, 2L)),
            ),
            currentChapterId = 3L,
        )

        snapshot!!.sectionKey shouldBe "paged:unknown:3"
        snapshot.items.map { it.id } shouldBe listOf(3L)
    }

    @Test
    fun `missing current chapter produces no snapshot`() {
        buildReaderChapterDrawerSnapshot(
            items = items(1L, 2L),
            structure = null,
            currentChapterId = 3L,
        ) shouldBe null
    }

    private fun items(vararg ids: Long) = ids.map { id ->
        ReaderChapterDrawerItem(
            id = id,
            name = "Chapter $id",
            dateUpload = 0L,
            read = false,
        )
    }

    private fun structure(
        layout: NovelLayout,
        sections: List<NovelSection>,
        totalPages: Long = 0,
    ) = NovelStructureSnapshot(layout, totalPages, sections)

    private fun section(name: String, vararg ids: Long) = NovelSection(
        name = name,
        pageNumber = name.toLongOrNull(),
        path = null,
        cover = null,
        chapterIds = ids.toList(),
    )
}
