package eu.kanade.tachiyomi.ui.reader

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import tachiyomi.domain.novel.model.NovelLayout
import tachiyomi.domain.novel.model.NovelStructureSnapshot

@Immutable
data class ReaderChapterDrawerItem(
    val id: Long,
    val name: String,
    val dateUpload: Long,
    val read: Boolean,
)

@Immutable
data class ReaderChapterDrawerSnapshot(
    val sectionKey: String,
    val items: ImmutableList<ReaderChapterDrawerItem>,
    val currentChapterId: Long,
) {
    val currentIndex: Int
        get() = items.indexOfFirst { it.id == currentChapterId }
}

internal fun buildReaderChapterDrawerSnapshot(
    items: List<ReaderChapterDrawerItem>,
    structure: NovelStructureSnapshot?,
    currentChapterId: Long,
): ReaderChapterDrawerSnapshot? {
    val currentItem = items.firstOrNull { it.id == currentChapterId } ?: return null
    val isFlat = structure == null ||
        structure.layout == NovelLayout.FLAT ||
        (structure.layout == NovelLayout.VOLUME && structure.sections.size <= 1) ||
        (structure.layout == NovelLayout.PAGED && structure.totalPages <= 1)

    if (isFlat) {
        return ReaderChapterDrawerSnapshot(
            sectionKey = "flat",
            items = items.toImmutableList(),
            currentChapterId = currentChapterId,
        )
    }

    val section = structure.sections.firstOrNull { currentChapterId in it.chapterIds }
        ?: return ReaderChapterDrawerSnapshot(
            sectionKey = "${structure.layout.name.lowercase()}:unknown:$currentChapterId",
            items = listOf(currentItem).toImmutableList(),
            currentChapterId = currentChapterId,
        )
    val chapterIds = section.chapterIds.toHashSet()
    val sectionItems = items.filter { it.id in chapterIds }.toImmutableList()
    val sectionPrefix = if (structure.layout == NovelLayout.PAGED) "page" else "volume"

    return ReaderChapterDrawerSnapshot(
        sectionKey = "$sectionPrefix:${section.name}",
        items = sectionItems,
        currentChapterId = currentChapterId,
    )
}
