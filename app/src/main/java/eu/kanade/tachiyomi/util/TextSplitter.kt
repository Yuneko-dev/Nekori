package eu.kanade.tachiyomi.util

/**
 * Utility for automatically splitting text after a certain number of words,
 * continuing until a sentence-ending punctuation mark is found.
 */
object TextSplitter {

    private val sentenceEndingPunctuation = setOf('.', '!', '?', '。', '！', '？', '…')

    /**
     * Splits text by inserting paragraph breaks after approximately [wordCount] words,
     * but always continuing until a sentence-ending punctuation mark is found.
     *
     * @param text The input text (can be HTML or plain text)
     * @param wordCount Target number of words before looking for punctuation
     * @param isHtml Whether [text] is HTML markup, per the caller's own classification
     * @return Text with additional paragraph breaks inserted
     */
    fun splitText(text: String, wordCount: Int, isHtml: Boolean): String {
        if (wordCount <= 0) return text
        val effectiveWordCount = wordCount.coerceAtLeast(20)

        return if (isHtml) {
            splitHtmlText(text, effectiveWordCount)
        } else {
            splitPlainText(text, effectiveWordCount)
        }
    }

    private fun splitPlainText(text: String, targetWordCount: Int): String {
        val result = StringBuilder()
        appendTextSegment(result, text, targetWordCount, 0, "\n\n")
        return result.toString()
    }

    private fun splitHtmlText(html: String, targetWordCount: Int): String {
        // For HTML, we process the text content within tags
        // We'll extract text segments, split them, and rebuild
        val result = StringBuilder()
        var wordsSincePunctuation = 0

        // Process the HTML by finding text segments
        var i = 0
        while (i < html.length) {
            if (html[i] == '<') {
                // Found a tag - copy it as-is
                val tagEnd = html.indexOf('>', i)
                if (tagEnd == -1) {
                    result.append(html.substring(i))
                    break
                }
                val tag = html.substring(i, tagEnd + 1)
                result.append(tag)

                val rawTextTag = RAW_TEXT_TAG.matchEntire(tag)?.groupValues?.get(1)
                if (rawTextTag != null) {
                    val closeStart = html.indexOf("</$rawTextTag", tagEnd + 1, ignoreCase = true)
                    val closeEnd = if (closeStart == -1) -1 else html.indexOf('>', closeStart)
                    if (closeEnd == -1) {
                        result.append(html, tagEnd + 1, html.length)
                        break
                    }
                    result.append(html, tagEnd + 1, closeEnd + 1)
                    i = closeEnd + 1
                    continue
                }

                // Check if it's a paragraph or break tag - reset counter
                if (tag.lowercase().startsWith("<p>") ||
                    tag.lowercase().startsWith("<br") ||
                    tag.lowercase().startsWith("</p>") ||
                    tag.lowercase().startsWith("<div") ||
                    tag.lowercase().startsWith("</div") ||
                    tag.lowercase().startsWith("<body") ||
                    tag.lowercase().startsWith("</body")
                ) {
                    wordsSincePunctuation = 0
                }
                i = tagEnd + 1
            } else {
                // Text content - process word by word
                val nextTag = html.indexOf('<', i)
                val textEnd = if (nextTag == -1) html.length else nextTag
                val text = html.substring(i, textEnd)
                wordsSincePunctuation = appendTextSegment(
                    result,
                    text,
                    targetWordCount,
                    wordsSincePunctuation,
                    "<br><br>",
                )
                i = textEnd
            }
        }

        return result.toString()
    }

    private fun appendTextSegment(
        result: StringBuilder,
        text: String,
        targetWordCount: Int,
        initialWordCount: Int,
        paragraphBreak: String,
    ): Int {
        var wordsSincePunctuation = initialWordCount
        var index = 0
        while (index < text.length) {
            val whitespaceStart = index
            while (index < text.length && text[index].isWhitespace()) index++
            if (index > whitespaceStart) result.append(text, whitespaceStart, index)
            if (index >= text.length) break

            val wordStart = index
            while (index < text.length && !text[index].isWhitespace()) index++
            val word = text.substring(wordStart, index)
            result.append(word)
            wordsSincePunctuation++

            if (word.lastOrNull() in sentenceEndingPunctuation && wordsSincePunctuation >= targetWordCount) {
                result.append(paragraphBreak)
                wordsSincePunctuation = 0
            }
        }
        return wordsSincePunctuation
    }

    private val RAW_TEXT_TAG = Regex("<(script|style)(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE)
}
