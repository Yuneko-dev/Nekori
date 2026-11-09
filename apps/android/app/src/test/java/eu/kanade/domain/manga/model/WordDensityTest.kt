package eu.kanade.domain.manga.model

import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class WordDensityTest {
    @Test
    fun `optional async count hides failures but preserves cancellation`() {
        mockkObject(WordDensity.Companion)
        try {
            every { WordDensity.countWords(any()) } throws IllegalArgumentException("count failed")
            assertNull(runBlocking { WordDensity.countWordsAsync("chapter") })
            every { WordDensity.countWords(any()) } throws CancellationException("cancelled")
            assertThrows<CancellationException> { runBlocking { WordDensity.countWordsAsync("chapter") } }
        } finally {
            unmockkObject(WordDensity.Companion)
        }
    }

    @Test
    fun `async counter shares HTML and Unicode counting`() = runBlocking {
        assertEquals(5, WordDensity.countWordsAsync("<p>Xin chào bạn 世界</p><script>ignored</script>"))
        assertEquals(0, WordDensity.countWordsAsync("<p>!!!</p>"))
    }

    @Test
    fun `countWords ignores markup and decodes entities`() {
        val html = """
            <html><head><title>Ignored title</title><style>p { color: red; }</style></head>
            <body><h1>Chapter&nbsp;1</h1><p>Tom &amp; Jerry said &lt;hi&gt;.</p>
            <script>var notWords = 1;</script><p>Last<br/>line</p></body></html>
        """.trimIndent()

        // Punctuation and markup are not words.
        assertEquals(8, WordDensity.countWords(html))
    }

    @Test
    fun `countWords handles plain text and blank content`() {
        assertEquals(4, WordDensity.countWords("one two\nthree\t four"))
        assertEquals(0, WordDensity.countWords("   \n "))
        assertEquals(0, WordDensity.countWords("<p> </p>"))
    }

    @Test
    fun `averageWords divides by counted chapters`() {
        assertEquals(450, WordDensity(totalWords = 900, countedChapters = 2, totalChapters = 5).averageWords)
        assertEquals(0, WordDensity(totalWords = 0, countedChapters = 0, totalChapters = 5).averageWords)
    }

    @Test
    fun `unreadable chapters are kept apart from chapters that are not downloaded`() {
        val density = WordDensity(totalWords = 1500, countedChapters = 3, totalChapters = 10, unreadableChapters = 2)

        assertEquals(500, density.averageWords)
        assertEquals(5, density.notDownloadedChapters)
        val allAvailable = WordDensity(totalWords = 900, countedChapters = 3, totalChapters = 5, unreadableChapters = 2)
        assertEquals(0, allAvailable.notDownloadedChapters)
    }

    @Test
    fun `every requested tier boundary is inclusive and the next word starts the next tier`() {
        val limits = listOf(400L, 600L, 900L, 1300L, 1800L, 2500L, 3500L, 5000L, 7000L)
        assertEquals(1, WordDensity.densityTier(0))
        limits.forEachIndexed { index, limit ->
            assertEquals(index + 1, WordDensity.densityTier(limit))
            assertEquals(index + 2, WordDensity.densityTier(limit + 1))
        }
        assertEquals(10, WordDensity.densityTier(Long.MAX_VALUE))
    }

    @Test
    fun `punctuation emoji and decomposed accents do not create extra words`() {
        assertEquals(0, WordDensity.countWords("— … !!! 😊"))
        assertEquals(4, WordDensity.countWords("café cafe\u0301 don't 12.5"))
        assertEquals(2, WordDensity.countWords("hello,world"))
        assertEquals(3, WordDensity.countWords("Xin chào bạn!"))
    }

    @Test
    fun `CJK reading units include supplementary Han characters without spaces`() {
        assertEquals(4, WordDensity.countWords("你好世界"))
        assertEquals(11, WordDensity.countWords("こんにちは カタカナ 안녕"))
        assertEquals(3, WordDensity.countWords("Hello 世界"))
        assertEquals(2, WordDensity.countWords("𠀀𪜀"))
        assertEquals(0, WordDensity.countWords("、。！？"))
    }

    @Test
    fun `tier bounds match densityTier`() {
        assertEquals(0, WordDensity.tierMinWords(1))
        assertEquals(400, WordDensity.tierMaxWords(1))
        assertEquals(401, WordDensity.tierMinWords(2))
        assertEquals(600, WordDensity.tierMaxWords(2))
        assertEquals(7001, WordDensity.tierMinWords(WordDensity.MAX_TIER))
        assertNull(WordDensity.tierMaxWords(WordDensity.MAX_TIER))

        for (tier in 1..WordDensity.MAX_TIER) {
            assertEquals(tier, WordDensity.densityTier(WordDensity.tierMinWords(tier)))
            WordDensity.tierMaxWords(tier)?.let { assertEquals(tier, WordDensity.densityTier(it)) }
        }
    }
}
