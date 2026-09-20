@file:Suppress("ktlint:standard:filename")

package eu.kanade.tachiyomi.ui.reader.setting

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Portable identity: database IDs change on restore; source paths must remain verbatim. */
@Serializable
data class ReplacementTarget(val source: Long, val path: String)

@Serializable
data class RegexReplacement(
    val title: String,
    val pattern: String,
    val replacement: String,
    val enabled: Boolean = true,
    val isRegex: Boolean = true,
    val matchWholeWord: Boolean = false,
    val caseSensitive: Boolean = false,
    val id: String = java.util.UUID.randomUUID().toString(),
    // Missing scope in older backups retains the original global behavior.
    val scope: String = GLOBAL,
    val target: ReplacementTarget? = null,
) {
    fun appliesTo(novel: ReplacementTarget?): Boolean = when (scope) {
        GLOBAL -> target == null
        NOVEL -> target != null && target.path.isNotBlank() && target == novel
        else -> false // Unknown or incomplete scope must never broaden to global.
    }

    fun compile(): Regex {
        require(pattern.isNotBlank())
        val escaped = Regex.escape(pattern)
        val expression = when {
            isRegex -> pattern
            matchWholeWord -> "(?<![\\p{L}\\p{N}_])(?:$escaped)(?![\\p{L}\\p{N}_])"
            else -> escaped
        }
        return Regex(expression, if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
    }

    fun replace(text: String, regex: Regex = compile()): String =
        if (isRegex) regex.replace(text, replacement) else regex.replace(text) { replacement }

    companion object {
        const val GLOBAL = "GLOBAL"
        const val NOVEL = "NOVEL"
        const val PREFERENCE_KEY = "pref_novel_regex_replacements"

        // Strict decoding also protects newer/unknown data from being silently rewritten by this UI.
        fun decode(value: String): List<RegexReplacement> {
            val rules = if (value.isBlank()) emptyList() else Json.decodeFromString<List<RegexReplacement>>(value)
            require(rules.all { it.id.isNotBlank() } && rules.map { it.id }.distinct().size == rules.size)
            return rules
        }
    }
}
