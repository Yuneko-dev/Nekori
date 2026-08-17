package eu.kanade.tachiyomi.data.translation

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.translation.model.TranslationEngineId
import tachiyomi.domain.translation.service.TranslationPreferences

class TranslationChunkerTest {

    @Test
    fun `LLM chapter splitting defaults to two thousand words`() {
        val preferences = TranslationPreferences(InMemoryPreferenceStore())

        preferences.splitLargeChapters().get() shouldBe true
        preferences.translationChunkMode().get() shouldBe TranslationChunkMode.WORDS.key
        preferences.translationChunkWordLimit().get() shouldBe 2_000
    }

    @Test
    fun `word count matches LNReader for Latin CJK and supplementary characters`() {
        TranslationChunker.countWords("Hello, world! 123") shouldBe 3
        TranslationChunker.countWords("Hello 世界") shouldBe 3
        TranslationChunker.countWords("こんにちは カタカナ 안녕") shouldBe 11
        TranslationChunker.countWords("𠀀") shouldBe 1
        TranslationChunker.countWords("Hello <strong>world</strong>") shouldBe 4
    }

    @Test
    fun `word chunks keep paragraphs whole and ordered`() {
        val texts = listOf("one two three", "four five", "six seven eight nine ten")

        TranslationChunker.chunk(
            texts = texts,
            engineId = TranslationEngineId.LLM,
            splitLargeChapters = true,
            mode = TranslationChunkMode.WORDS,
            paragraphLimit = 50,
            wordLimit = 4,
        ) shouldContainExactly listOf(
            listOf("one two three"),
            listOf("four five"),
            listOf("six seven eight nine ten"),
        )
    }

    @Test
    fun `LLM toggle and mode do not change non LLM batching`() {
        val texts = listOf("one", "two", "three")

        TranslationChunker.chunk(
            texts,
            TranslationEngineId.LLM,
            splitLargeChapters = false,
            mode = TranslationChunkMode.WORDS,
            paragraphLimit = 1,
            wordLimit = 1,
        ) shouldContainExactly listOf(texts)

        TranslationChunker.chunk(
            texts,
            TranslationEngineId.LLM,
            splitLargeChapters = true,
            mode = TranslationChunkMode.PARAGRAPHS,
            paragraphLimit = 2,
            wordLimit = 1,
        ) shouldContainExactly listOf(listOf("one", "two"), listOf("three"))

        TranslationChunker.chunk(
            texts,
            TranslationEngineId.DEEPL,
            splitLargeChapters = false,
            mode = TranslationChunkMode.WORDS,
            paragraphLimit = 2,
            wordLimit = 1,
        ) shouldContainExactly listOf(listOf("one", "two"), listOf("three"))
    }
}
