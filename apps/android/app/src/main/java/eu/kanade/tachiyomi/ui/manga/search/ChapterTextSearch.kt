package eu.kanade.tachiyomi.ui.manga.search

import androidx.compose.runtime.Immutable
import com.google.re2j.Pattern
import com.google.re2j.PatternSyntaxException
import org.jsoup.Jsoup
import kotlin.coroutines.cancellation.CancellationException

private const val SNIPPET_CONTEXT = 60
private const val MAX_SNIPPET_MATCH_LENGTH = 200

@Immutable
data class ChapterSearchOptions(
    val isRegex: Boolean = false,
    val caseSensitive: Boolean = false,
    val wholeWord: Boolean = false,
)

/**
 * One match stored as offsets into shared [chapterText]; its excerpt is built only when [text] is read.
 * [matchStart]/[matchEnd] index into that excerpt.
 * [position] is where the match sits in the chapter, from 0 to 1.
 */
@Immutable
data class ChapterSearchSnippet(
    val chapterText: String,
    val start: Int,
    val end: Int,
) {
    private val shownEnd: Int get() = minOf(end, start + MAX_SNIPPET_MATCH_LENGTH)
    private val from: Int get() = (start - SNIPPET_CONTEXT).coerceAtLeast(0)
    private val to: Int get() = (shownEnd + SNIPPET_CONTEXT).coerceAtMost(chapterText.length)
    private val prefix: String get() = if (from > 0) "…" else ""

    // All matches share the chapter text; only visible rows allocate an excerpt.
    val text: String get() = prefix + chapterText.substring(from, to) + if (to < chapterText.length) "…" else ""
    val matchStart: Int get() = prefix.length + start - from
    val matchEnd: Int get() = prefix.length + shownEnd - from
    val position: Float get() = start.toFloat() / chapterText.length
}

@Immutable
data class ChapterTextMatches(
    val snippets: List<ChapterSearchSnippet>,
) {
    val count: Int get() = snippets.size
}

/**
 * Text search over chapter content, used by the entry-level chapter search.
 */
object ChapterTextSearch {

    private const val WORD_SEPARATOR = "[^\\p{L}\\p{M}\\p{N}_]"

    /**
     * Builds the regex for [query]. Plain queries are matched literally.
     *
     * @throws PatternSyntaxException if [ChapterSearchOptions.isRegex] is set and [query] is not a valid pattern.
     */
    fun buildRegex(query: String, options: ChapterSearchOptions): Pattern {
        val pattern = if (options.isRegex) query else Pattern.quote(query)
        // Group 1 is always the user's match. Separators stay outside its highlighted range.
        val bounded = if (options.wholeWord) "(?:^|$WORD_SEPARATOR)($pattern)(?:$|$WORD_SEPARATOR)" else "($pattern)"
        val flags = if (options.caseSensitive) 0 else Pattern.CASE_INSENSITIVE
        return Pattern.compile(bounded, flags)
    }

    /**
     * Reduces chapter HTML (or plain text) to whitespace-normalized visible text.
     */
    fun toPlainText(content: String): String = Jsoup.parse(content).body().text()

    /**
     * Keeps every non-empty match as offsets into one shared chapter string.
     *
     * When [isActive] turns false the scan aborts with a [CancellationException], even in the
     * middle of a slow regex evaluation.
     */
    fun findMatches(
        text: String,
        regex: Pattern,
        isActive: () -> Boolean = { true },
    ): ChapterTextMatches {
        if (!isActive()) throw CancellationException("Chapter search cancelled")
        if (text.isEmpty()) return ChapterTextMatches(emptyList())
        val input = CancellableCharSequence(text, isActive)
        val matcher = regex.matcher(input)
        val snippets = mutableListOf<ChapterSearchSnippet>()
        var nextStart = 0
        while (nextStart <= text.length && matcher.find(nextStart)) {
            if (!isActive()) throw CancellationException("Chapter search cancelled")
            val start = matcher.start(1)
            val end = matcher.end(1)
            if (start < end) snippets += ChapterSearchSnippet(text, start, end)
            // Reuse the trailing separator for adjacent whole-word matches; always advance for empty matches.
            nextStart =
                if (end > start) end else end + if (end < text.length) Character.charCount(text.codePointAt(end)) else 1
        }
        return ChapterTextMatches(snippets)
    }

    /**
     * RE2/J reads this sequence directly on both Android and JVM. Android's java.util.regex
     * copies it to a String, bypassing cancellation checks during matching.
     */
    private class CancellableCharSequence(
        private val delegate: CharSequence,
        private val isActive: () -> Boolean,
    ) : CharSequence {
        private var reads = 0

        override val length: Int get() = delegate.length

        override fun get(index: Int): Char {
            if ((++reads and 0xFFF) == 0 && !isActive()) throw CancellationException("Chapter search cancelled")
            return delegate[index]
        }

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            CancellableCharSequence(delegate.subSequence(startIndex, endIndex), isActive)

        override fun toString(): String = delegate.toString()
    }
}
