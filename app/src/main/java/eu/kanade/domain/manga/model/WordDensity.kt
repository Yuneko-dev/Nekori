package eu.kanade.domain.manga.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import tachiyomi.core.common.util.lang.countReadableWords

/**
 * Word statistics for the downloaded chapters of a novel.
 *
 * @property totalWords words across every counted chapter.
 * @property countedChapters chapters whose content could be read and counted.
 * @property totalChapters every chapter of the entry, downloaded or not.
 * @property unreadableChapters chapters that were available but whose content could not be read.
 */
data class WordDensity(
    val totalWords: Long,
    val countedChapters: Int,
    val totalChapters: Int,
    val unreadableChapters: Int = 0,
) {
    /** Chapters left out because they are not downloaded. */
    val notDownloadedChapters: Int
        get() = (totalChapters - countedChapters - unreadableChapters).coerceAtLeast(0)

    /** Average words per counted chapter, the novel's word density. */
    val averageWords: Long
        get() = if (countedChapters == 0) 0 else totalWords / countedChapters

    val tier: Int
        get() = densityTier(averageWords)

    companion object {
        /** Optional reader statistic: a counting failure must not prevent opening the chapter. */
        suspend fun countWordsAsync(content: String): Int? = withContext(Dispatchers.Default) {
            try {
                countWords(content)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }

        const val MAX_TIER = 10
        private val tierUpperBounds = longArrayOf(400, 600, 900, 1300, 1800, 2500, 3500, 5000, 7000)

        /**
         * Maps an average chapter length onto a small 1..[MAX_TIER] indicator:
         * Bounds also supply the range labels, so counting and presentation cannot drift apart.
         */
        fun densityTier(averageWords: Long): Int {
            return tierUpperBounds.indexOfFirst { averageWords <= it }.let { if (it < 0) MAX_TIER else it + 1 }
        }

        /** Lowest average word count that lands in [tier]. */
        fun tierMinWords(tier: Int): Long =
            if (tier <= 1) 0 else tierUpperBounds[tier.coerceAtMost(MAX_TIER) - 2] + 1

        /** Highest average word count that lands in [tier], or null for the open-ended top tier. */
        fun tierMaxWords(tier: Int): Long? =
            if (tier >= MAX_TIER) null else tierUpperBounds[tier.coerceAtLeast(1) - 1]

        /**
         * Shares reading-unit counting with translation: CJK code points and other scripts' words.
         * Markup, scripts, styles, punctuation and emoji are not counted.
         */
        fun countWords(content: String): Int {
            if (content.isBlank()) return 0
            val text = Jsoup.parse(content).body().text()
            return countReadableWords(text)
        }
    }
}
