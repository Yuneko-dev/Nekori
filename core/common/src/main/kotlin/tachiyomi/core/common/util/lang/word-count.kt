@file:Suppress("ktlint:standard:filename")

package tachiyomi.core.common.util.lang

import java.text.BreakIterator
import java.util.Locale

/** Reading units: CJK/Kana/Hangul code points, other scripts' word boundaries, excluding symbols. */
fun countReadableWords(text: String, locale: Locale = Locale.ROOT): Int {
    val iterator = BreakIterator.getWordInstance(locale)
    iterator.setText(text)
    var count = 0
    var start = iterator.first()
    var end = iterator.next()
    while (end != BreakIterator.DONE) {
        var offset = start
        var inWord = false
        while (offset < end) {
            val codePoint = text.codePointAt(offset)
            if (Character.isLetterOrDigit(codePoint)) {
                when (Character.UnicodeScript.of(codePoint)) {
                    Character.UnicodeScript.HAN,
                    Character.UnicodeScript.HIRAGANA,
                    Character.UnicodeScript.KATAKANA,
                    Character.UnicodeScript.HANGUL,
                    -> {
                        count++
                        inWord = false
                    }
                    else -> if (!inWord) {
                        count++
                        inWord = true
                    }
                }
            }
            offset += Character.charCount(codePoint)
        }
        start = end
        end = iterator.next()
    }
    return count
}
