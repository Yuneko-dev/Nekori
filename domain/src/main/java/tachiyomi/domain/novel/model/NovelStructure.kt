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
