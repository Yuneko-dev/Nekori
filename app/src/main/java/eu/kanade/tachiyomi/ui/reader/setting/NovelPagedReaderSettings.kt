package eu.kanade.tachiyomi.ui.reader.setting

enum class NovelReadingLayout {
    SCROLL,
    PAGED,
}

enum class NovelPageSpread {
    AUTO,
    SINGLE,
    DOUBLE,
}

enum class NovelPageEffect {
    NONE,
    HORIZONTAL,
    SLIDE,
    CURL,
}

/** Current paged-reader coordinates. Page numbers are one-based; unit indices are zero-based. */
data class NovelPagePosition(
    val chapterId: Long,
    val firstPage: Int,
    val lastPage: Int,
    val totalPages: Int,
    val unitIndex: Int,
    val unitCount: Int,
)
