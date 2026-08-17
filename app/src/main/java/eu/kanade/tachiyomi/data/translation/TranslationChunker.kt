package eu.kanade.tachiyomi.data.translation

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

    /** Matches LNReader: Latin/number runs are words; CJK, Kana and Hangul count per code point. */
    internal fun countWords(text: String): Int {
        var count = 0
        var inWord = false
        var offset = 0
        while (offset < text.length) {
            val codePoint = text.codePointAt(offset)
            if (Character.isLetterOrDigit(codePoint)) {
                if (isCjk(codePoint)) {
                    count++
                    inWord = false
                } else if (!inWord) {
                    count++
                    inWord = true
                }
            } else {
                inWord = false
            }
            offset += Character.charCount(codePoint)
        }
        return count
    }

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

    private fun isCjk(codePoint: Int): Boolean =
        codePoint in 0x4E00..0x9FFF ||
            codePoint in 0x3400..0x4DBF ||
            codePoint in 0x20000..0x2A6DF ||
            codePoint in 0xF900..0xFAFF ||
            codePoint in 0x3040..0x309F ||
            codePoint in 0x30A0..0x30FF ||
            codePoint in 0xAC00..0xD7AF
}
