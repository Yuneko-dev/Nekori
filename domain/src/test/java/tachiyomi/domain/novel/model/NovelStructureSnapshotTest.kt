package tachiyomi.domain.novel.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class NovelStructureSnapshotTest {

    @Test
    fun `flat structure has no section navigation`() {
        val structure = snapshot(
            layout = NovelLayout.FLAT,
            sections = listOf(section("Default", 1L, 2L)),
        )

        structure.sectionNames shouldBe emptyList()
        structure.defaultSection shouldBe null
    }

    @Test
    fun `volume structure keeps database order and default group`() {
        val structure = snapshot(
            layout = NovelLayout.VOLUME,
            sections = listOf(
                section("Default", 1L),
                section("Volume 1", 2L, 3L),
            ),
        )

        structure.sectionNames shouldBe listOf("Default", "Volume 1")
        structure.defaultSection shouldBe "Default"
        structure.chapterIds("Volume 1") shouldBe setOf(2L, 3L)
    }

    @Test
    fun `paged structure exposes unloaded pages without inventing chapters`() {
        val structure = snapshot(
            layout = NovelLayout.PAGED,
            totalPages = 3,
            sections = listOf(section("1", 10L), section("3")),
        )

        structure.sectionNames shouldBe listOf("1", "2", "3")
        structure.defaultSection shouldBe "1"
        structure.chapterIds("2") shouldBe emptySet()
        structure.chapterIds("3") shouldBe emptySet()
    }

    private fun snapshot(
        layout: NovelLayout,
        totalPages: Long = 0,
        sections: List<NovelSection>,
    ) = NovelStructureSnapshot(layout, totalPages, sections)

    private fun section(name: String, vararg chapterIds: Long) =
        NovelSection(
            name = name,
            pageNumber = name.toLongOrNull(),
            path = null,
            cover = null,
            chapterIds = chapterIds.toList(),
        )
}
