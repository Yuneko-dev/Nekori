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
    fun `volume novel labels all loaded volumes in reader order`() {
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

        snapshot!!.items.map { it.id } shouldBe listOf(1L, 2L, 3L, 4L)
        snapshot.items.map { it.sectionName } shouldBe listOf("Volume 1", null, "Volume 2", null)
        snapshot.currentIndex shouldBe 2
        snapshot.isPaged shouldBe false
    }

    @Test
    fun `paged novel labels only loaded pages`() {
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

        snapshot!!.items.map { it.id } shouldBe listOf(1L, 2L, 51L, 52L)
        snapshot.items.map { it.sectionName } shouldBe listOf("1", null, "2", null)
        snapshot.currentIndex shouldBe 2
        snapshot.isPaged shouldBe true
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
    fun `missing section membership preserves chapters without guessing a label`() {
        val snapshot = buildReaderChapterDrawerSnapshot(
            items = items(1L, 2L, 3L),
            structure = structure(
                layout = NovelLayout.PAGED,
                totalPages = 2,
                sections = listOf(section("1", 1L, 2L)),
            ),
            currentChapterId = 3L,
        )

        snapshot!!.items.map { it.id } shouldBe listOf(1L, 2L, 3L)
        snapshot.items.map { it.sectionName } shouldBe listOf("1", null, null)
    }

    @Test
    fun `missing current chapter produces no snapshot`() {
        buildReaderChapterDrawerSnapshot(
            items = items(1L, 2L),
            structure = null,
            currentChapterId = 3L,
        ) shouldBe null
    }

    @Test
    fun `filters and reader order determine section boundaries`() {
        val snapshot = buildReaderChapterDrawerSnapshot(
            items = items(4L, 2L, 5L),
            structure = structure(
                layout = NovelLayout.VOLUME,
                sections = listOf(
                    section("Volume 1", 1L, 2L),
                    section("Filtered volume", 3L),
                    section("Volume 2", 4L, 5L),
                ),
            ),
            currentChapterId = 2L,
        )!!

        snapshot.items.map { it.id } shouldBe listOf(4L, 2L, 5L)
        snapshot.items.map { it.sectionName } shouldBe listOf("Volume 2", "Volume 1", "Volume 2")
        snapshot.currentIndex shouldBe 1
        snapshot.copy(currentChapterId = 5L).currentIndex shouldBe 2
    }

    @Test
    fun `large volume list preserves headings and recalculates index when selection changes`() {
        val chapters = (1L..50_000L).map { id ->
            ReaderChapterDrawerItem(id, "Chapter $id", 0L, false)
        }
        val sections = chapters.chunked(500).mapIndexed { index, chaptersInVolume ->
            section("Volume $index", *chaptersInVolume.map { it.id }.toLongArray())
        }
        val snapshot = buildReaderChapterDrawerSnapshot(
            items = chapters,
            structure = structure(NovelLayout.VOLUME, sections),
            currentChapterId = 50_000L,
        )!!

        snapshot.items.size shouldBe 50_000
        snapshot.items.count { it.sectionName != null } shouldBe 100
        snapshot.currentIndex shouldBe 49_999
        snapshot.copy(currentChapterId = 25_001L).currentIndex shouldBe 25_000
        snapshot.copy(currentChapterId = 1L).currentIndex shouldBe 0
        snapshot.currentIndex shouldBe 49_999
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
