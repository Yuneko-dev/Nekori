package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Pure progress-to-percent and chapter-marking helpers shared by the novel viewers.
 * Kept renderer-agnostic so the WebView and TextView paths persist progress identically.
 */
object NovelProgress {

    /**
     * Scroll ratio at/above which a chapter counts as fully read. Shared by every renderer and by
     * the scroll-tracking JS so a chapter marks read at the same point regardless of viewer.
     */
    const val DONE_THRESHOLD = 0.99f

    /** Snap a near-complete ratio to a clean 1f so the stored percent lands on 100. */
    fun snapProgress(progress: Float): Float = if (progress >= DONE_THRESHOLD) 1f else progress

    /**
     * Convert a 0f..1f scroll ratio to a stored percent. Rounds (not truncates) so a position
     * matches between renderers; the TextView path already rounds.
     */
    fun progressToPercent(progress: Float): Int = (progress * 100f).roundToInt().coerceIn(0, 100)

    /** True when [newProgress] rounds to a different percent than [lastProgress] (dedup guard). */
    fun percentChanged(newProgress: Float, lastProgress: Float): Boolean =
        progressToPercent(newProgress) != progressToPercent(lastProgress)

    /**
     * Allow one deliberate backward page turn when paged progress is persisted. A paged unit's
     * progress is indexed from zero, so each step is one of (count - 1) intervals.
     * A null count denotes vertical scrolling: one settled fling can span the entire chapter.
     * The viewer's restore guard, not distance travelled, filters loading/restore samples.
     */
    fun backwardJumpAllowancePercent(pagedUnitCount: Int?): Int =
        if (pagedUnitCount == null) {
            100
        } else {
            pagedUnitCount
                ?.takeIf { it > 1 }
                ?.let { ceil(100.0 / (it - 1)).toInt().coerceIn(10, 100) }
                ?: 10
        }

    /**
     * Chapters to mark 100% read when the visible chapter moves forward from [oldIndex] to
     * [newIndex]. All chapters left behind ([oldIndex] until [newIndex]): the slider now seeks only
     * within the current chapter, so a multi-step jump means a fling scrolled the whole document
     * past those chapters, they were shown and must count as read. Empty when not moving forward.
     */
    fun forwardChaptersToMarkRead(oldIndex: Int, newIndex: Int, size: Int): List<Int> {
        if (newIndex <= oldIndex) return emptyList()
        if (oldIndex < 0 || oldIndex >= size) return emptyList()
        val to = newIndex.coerceAtMost(size)
        return (oldIndex until to).toList()
    }
}
