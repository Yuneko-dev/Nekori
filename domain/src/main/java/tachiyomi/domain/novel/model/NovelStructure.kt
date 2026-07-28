package tachiyomi.domain.novel.model

enum class NovelLayout(val value: Long) {
    FLAT(0),
    VOLUME(1),
    PAGED(2),
}

data class NovelStructure(
    val layout: NovelLayout,
    val totalPages: Long,
    val chapterPages: Map<String, String>,
)

data class NovelStructureSnapshot(
    val layout: NovelLayout,
    val totalPages: Long,
    val sections: List<NovelSection>,
) {
    val sectionNames: List<String>
        get() = when (layout) {
            NovelLayout.PAGED -> (1L..totalPages).map { it.toString() }
            NovelLayout.VOLUME -> sections.map { it.name }
            NovelLayout.FLAT -> emptyList()
        }

    val defaultSection: String?
        get() = when (layout) {
            NovelLayout.PAGED -> "1"
            NovelLayout.VOLUME -> sections.firstOrNull()?.name
            NovelLayout.FLAT -> null
        }

    fun chapterIds(section: String): Set<Long> =
        sections.firstOrNull { it.name == section }?.chapterIds.orEmpty().toSet()
}

data class NovelSection(
    val name: String,
    val pageNumber: Long?,
    val path: String?,
    val cover: String?,
    val chapterIds: List<Long>,
)
