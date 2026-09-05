package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

object TtsTextUtils {

    private val edgeQuotes = Regex("^[\"'“”‘’]+|[\"'“”‘’]+$")
    private val whitespace = Regex("\\s+")
    private val punctuationSpacing = Regex("\\s*([.,!?;:])\\s*")

    /** Matches the text normalization used by LNReader before each native TTS request. */
    fun normalizeText(text: String): String = text
        .replace(edgeQuotes, "")
        .replace(whitespace, " ")
        .replace(punctuationSpacing, "\$1 ")
        .trim()

    fun splitTextForTts(text: String, maxLength: Int): List<String> {
        val chunks = mutableListOf<String>()
        val hasSpaces = ' ' in text
        var remaining = text

        while (remaining.isNotEmpty()) {
            if (remaining.length <= maxLength) {
                chunks.add(remaining)
                break
            }

            var breakPoint = maxLength

            val slice = remaining.substring(0, maxLength)
            val sentenceEnd = slice.lastIndexOfAny(charArrayOf('.', '!', '?', '\n'))
            if (sentenceEnd > maxLength / 2) {
                breakPoint = sentenceEnd + 1
            } else {
                val lastSpace = slice.lastIndexOf(' ')
                if (lastSpace > maxLength / 2) {
                    breakPoint = lastSpace + 1
                } else if (hasSpaces) {
                    val nextSpace = remaining.indexOf(' ')
                    if (nextSpace > 0) {
                        breakPoint = nextSpace + 1
                    } else {
                        chunks.add(remaining.trim())
                        break
                    }
                }
            }

            chunks.add(remaining.substring(0, breakPoint).trim())
            remaining = remaining.substring(breakPoint).trim()
        }

        return chunks
    }

    fun getChunkIndexFromOffset(charOffset: Int, ttsChunks: List<String>): Int {
        var currentOffset = 0
        for ((index, chunk) in ttsChunks.withIndex()) {
            if (currentOffset + chunk.length > charOffset) return index
            currentOffset += chunk.length
        }
        return (ttsChunks.size - 1).coerceAtLeast(0)
    }

    fun getParagraphStartChunk(paragraphIndex: Int, paragraphIndexes: List<Int>): Int {
        val target = paragraphIndex.coerceIn(0, paragraphIndexes.lastOrNull() ?: 0)
        return paragraphIndexes.indexOfFirst { it >= target }.coerceAtLeast(0)
    }

    fun getParagraphProgress(chunkIndex: Int, paragraphIndexes: List<Int>): Pair<Int, Int> {
        if (paragraphIndexes.isEmpty()) return 0 to 0
        return paragraphIndexes[chunkIndex.coerceIn(0, paragraphIndexes.lastIndex)] to (paragraphIndexes.last() + 1)
    }

    fun computeTtsStepTargetChunk(
        delta: Int,
        ttsPaused: Boolean,
        ttsResumeChunkIndex: Int,
        ttsCurrentChunkIndex: Int,
        ttsChunks: List<String>,
        ttsChunkParagraphIndexes: List<Int>,
    ): Int {
        val currentChunk = (if (ttsPaused) ttsResumeChunkIndex else ttsCurrentChunkIndex)
            .coerceIn(0, (ttsChunks.size - 1).coerceAtLeast(0))
        val currentParagraph = ttsChunkParagraphIndexes.getOrElse(currentChunk) { currentChunk }
        val maxParagraph = (ttsChunkParagraphIndexes.maxOrNull() ?: currentParagraph).coerceAtLeast(0)
        val targetParagraph = (currentParagraph + delta).coerceIn(0, maxParagraph)
        return ttsChunkParagraphIndexes.indexOfFirst { it >= targetParagraph }
            .takeIf { it >= 0 }
            ?: currentChunk
    }
}
