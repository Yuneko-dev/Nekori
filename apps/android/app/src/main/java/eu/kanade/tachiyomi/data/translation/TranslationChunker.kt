package eu.kanade.tachiyomi.data.translation

import tachiyomi.core.common.util.lang.countReadableWords
import tachiyomi.domain.translation.model.TranslationEngineId

internal enum class TranslationChunkMode(val key: String) {
    WORDS("words"),
    PARAGRAPHS("paragraphs"),
    ;

    companion object {
        fun fromKey(key: String) = entries.find { it.key == key } ?: WORDS
    }
}

internal object TranslationChunker {

    fun chunk(
        texts: List<String>,
        engineId: TranslationEngineId,
        splitLargeChapters: Boolean,
        mode: TranslationChunkMode,
        paragraphLimit: Int,
        wordLimit: Int,
    ): List<List<String>> {
        if (texts.isEmpty()) return emptyList()
        if (engineId != TranslationEngineId.LLM) return texts.chunked(paragraphLimit.coerceAtLeast(1))
        if (!splitLargeChapters) return listOf(texts)

        return when (mode) {
            TranslationChunkMode.WORDS -> chunkByWordCount(texts, wordLimit.coerceAtLeast(1))
            TranslationChunkMode.PARAGRAPHS -> texts.chunked(paragraphLimit.coerceAtLeast(1))
        }
    }

    internal fun countWords(text: String): Int = countReadableWords(text)

    private fun chunkByWordCount(texts: List<String>, wordLimit: Int): List<List<String>> {
        val chunks = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        var currentWords = 0

        texts.forEach { text ->
            val words = countWords(text)
            if (current.isNotEmpty() && currentWords + words > wordLimit) {
                chunks += current
                current = mutableListOf()
                currentWords = 0
            }
            current += text
            currentWords += words
        }
        if (current.isNotEmpty()) chunks += current
        return chunks
    }
}
