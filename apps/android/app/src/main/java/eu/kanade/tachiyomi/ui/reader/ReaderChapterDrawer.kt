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
    val sectionName: String? = null,
)

@Immutable
data class ReaderChapterDrawerSnapshot(
    val sectionKey: String,
    val items: ImmutableList<ReaderChapterDrawerItem>,
    val currentChapterId: Long,
    val isPaged: Boolean = false,
) {
    val currentIndex: Int = items.indexOfFirst { it.id == currentChapterId }
}

internal fun buildReaderChapterDrawerSnapshot(
    items: List<ReaderChapterDrawerItem>,
    structure: NovelStructureSnapshot?,
    currentChapterId: Long,
): ReaderChapterDrawerSnapshot? {
    if (items.none { it.id == currentChapterId }) return null
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

    val sectionNamesByChapter = buildMap {
        structure.sections.forEach { section ->
            section.chapterIds.forEach { put(it, section.name.takeIf(String::isNotBlank)) }
        }
    }
    var previousSection: String? = null
    val sectionItems = items.map { item ->
        val sectionName = sectionNamesByChapter[item.id]
        item.copy(sectionName = sectionName.takeIf { it != previousSection }).also {
            previousSection = sectionName
        }
    }

    return ReaderChapterDrawerSnapshot(
        sectionKey = structure.layout.name.lowercase(),
        items = sectionItems.toImmutableList(),
        currentChapterId = currentChapterId,
        isPaged = structure.layout == NovelLayout.PAGED,
    )
}
