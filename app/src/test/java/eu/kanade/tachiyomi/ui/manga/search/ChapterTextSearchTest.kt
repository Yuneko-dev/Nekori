package eu.kanade.tachiyomi.ui.manga.search

import com.google.re2j.PatternSyntaxException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.coroutines.cancellation.CancellationException

class ChapterTextSearchTest {

    @Test
    fun `cancelled short scans abort before reading text`() {
        assertThrows<CancellationException> {
            ChapterTextSearch.findMatches("abc", ChapterTextSearch.buildRegex("a", ChapterSearchOptions())) { false }
        }
    }

    private fun count(text: String, query: String, options: ChapterSearchOptions = ChapterSearchOptions()) =
        ChapterTextSearch.findMatches(text, ChapterTextSearch.buildRegex(query, options)).count

    @Test
    fun `plain query matches literally and ignores case by default`() {
        assertEquals(2, count("Rogue said: rogue? (a.b)", "rogue"))
        assertEquals(1, count("a.b axb", "a.b"))
        assertEquals(0, count("Rogue", "rogue", ChapterSearchOptions(caseSensitive = true)))
    }

    @Test
    fun `case insensitive matching handles non-ascii letters`() {
        assertEquals(1, count("ÉLAN", "élan"))
    }

    @Test
    fun `regex option enables patterns`() {
        val options = ChapterSearchOptions(isRegex = true)
        assertEquals(3, count("Player 1, Player 22, Player 3", "Player \\d+", options))
        assertEquals(0, count("Player 1", "Player \\d+"))
    }

    @Test
    fun `whole word skips matches inside words`() {
        val options = ChapterSearchOptions(wholeWord = true)
        assertEquals(1, count("Play the player, play", "play", options.copy(caseSensitive = true)))
        assertEquals(2, count("Play the player, play", "play", options))
        assertEquals(1, count("cat|catalog", "cat|dog", options.copy(isRegex = true)))
    }

    @Test
    fun `whole word shares separators and keeps accents attached`() {
        val options = ChapterSearchOptions(wholeWord = true)
        assertEquals(3, count("cat cat cat", "cat", options))
        assertEquals(1, count("cafe\u0301 cafe", "cafe", options))
        assertEquals(2, count("cat catalog", "cat|catalog", options.copy(isRegex = true)))
        val matches = ChapterTextSearch.findMatches("a cat!", ChapterTextSearch.buildRegex("cat", options))
        assertEquals(2, matches.snippets.single().start)
        assertEquals(5, matches.snippets.single().end)
    }

    @Test
    fun `nested repetition completes without backtracking and unsupported constructs are rejected`() {
        assertEquals(0, count("a".repeat(100_000), "(a+)+b", ChapterSearchOptions(isRegex = true)))
        listOf("(?=a)", "(a)\\1").forEach { query ->
            assertThrows<PatternSyntaxException> {
                ChapterTextSearch.buildRegex(query, ChapterSearchOptions(isRegex = true))
            }
        }
    }

    @Test
    fun `invalid regex throws`() {
        assertThrows<PatternSyntaxException> {
            ChapterTextSearch.buildRegex("(unclosed", ChapterSearchOptions(isRegex = true))
        }
    }

    @Test
    fun `empty regex matches are not counted`() {
        assertEquals(1, count("aab", "b*", ChapterSearchOptions(isRegex = true)))
    }

    @Test
    fun `snippets carry match bounds and position`() {
        val text = "x".repeat(100) + "needle" + "y".repeat(100)
        val result = ChapterTextSearch.findMatches(text, ChapterTextSearch.buildRegex("needle", ChapterSearchOptions()))
        val snippet = result.snippets.single()
        assertEquals("needle", snippet.text.substring(snippet.matchStart, snippet.matchEnd))
        assertTrue(snippet.text.startsWith("…"))
        assertTrue(snippet.text.endsWith("…"))
        assertEquals(100f / text.length, snippet.position)
    }

    @Test
    fun `all matches retain shared chapter text and independently navigable positions`() {
        val text = "a ".repeat(10_000)
        val result = ChapterTextSearch.findMatches(
            text,
            ChapterTextSearch.buildRegex("a", ChapterSearchOptions()),
        )
        assertEquals(10_000, result.count)
        assertEquals(result.count, result.snippets.size)
        result.snippets.forEachIndexed { index, snippet ->
            assertSame(text, snippet.chapterText)
            assertEquals(index * 2, snippet.start)
            assertEquals(index * 2 + 1, snippet.end)
            assertEquals(index * 2f / text.length, snippet.position)
        }
        val last = result.snippets.last()
        assertEquals("a", last.text.substring(last.matchStart, last.matchEnd))
    }

    @Test
    fun `long regex matches keep full range but bound excerpt allocation`() {
        val text = "a".repeat(10_000)
        val result = ChapterTextSearch.findMatches(
            text,
            ChapterTextSearch.buildRegex("a+", ChapterSearchOptions(isRegex = true)),
        )
        val snippet = result.snippets.single()
        assertEquals(text.length, snippet.end)
        assertEquals(200, snippet.matchEnd - snippet.matchStart)
        assertTrue(snippet.text.length <= 321)
    }

    @Test
    fun `cancellation during a regex scan aborts on the production engine`() {
        var checks = 0
        assertThrows<CancellationException> {
            ChapterTextSearch.findMatches(
                "a".repeat(10_000),
                ChapterTextSearch.buildRegex("(a+)+b", ChapterSearchOptions(isRegex = true)),
                isActive = { ++checks < 3 },
            )
        }
    }

    @Test
    fun `html is reduced to visible text`() {
        val text = ChapterTextSearch.toPlainText(
            "<html><head><style>.rogue{}</style><script>var rogue;</script></head>" +
                "<body><h1>Title</h1><p>The <b>Rogue</b> ran.</p></body></html>",
        )
        assertEquals("Title The Rogue ran.", text)
        assertEquals(1, count(text, "rogue"))
    }

    @Test
    fun `cancelled scan aborts`() {
        assertThrows<CancellationException> {
            ChapterTextSearch.findMatches(
                "a".repeat(100_000),
                ChapterTextSearch.buildRegex("b", ChapterSearchOptions()),
                isActive = { false },
            )
        }
    }
}
