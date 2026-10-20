@file:Suppress("ktlint:standard:filename")

package eu.kanade.tachiyomi.ui.reader.setting

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.util.UUID

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class CodeSnippet(
    val title: String,
    val code: String,
    val enabled: Boolean = true,
    val runOnAppend: Boolean = false,
    @EncodeDefault val id: String = "legacy-${java.util.Objects.hash(title, code)}",
    val scope: String = RegexReplacement.GLOBAL,
    val target: ReplacementTarget? = null,
) {
    fun appliesTo(novel: ReplacementTarget?): Boolean = when (scope) {
        RegexReplacement.GLOBAL -> target == null
        RegexReplacement.NOVEL -> target != null && target.path.isNotBlank() && target == novel
        else -> false
    }

    companion object {
        const val CSS_KEY = "pref_novel_css_snippets"
        const val JS_KEY = "pref_novel_js_snippets"

        fun decode(value: String): List<CodeSnippet> = Json.decodeFromString<List<CodeSnippet>>(value).also {
            validateTargets(it)
        }

        fun encode(snippets: List<CodeSnippet>): String {
            validateTargets(snippets)
            require(snippets.all { it.id.isNotBlank() } && snippets.map { it.id }.distinct().size == snippets.size)
            return Json.encodeToString(snippets)
        }

        // Repair only a draft/restore, never runtime reads: Cancel leaves saved bytes untouched.
        fun repairIds(snippets: List<CodeSnippet>, raw: String? = null): List<CodeSnippet> {
            val missingIds = raw?.let { value ->
                Json.parseToJsonElement(value).jsonArray.mapIndexedNotNull { index, element ->
                    index.takeIf { "id" !in element.jsonObject }
                }.toSet()
            }.orEmpty()
            val used = mutableSetOf<String>()
            return snippets.mapIndexed { index, snippet ->
                if (index !in missingIds && snippet.id.isNotBlank() && used.add(snippet.id)) {
                    snippet
                } else {
                    var id: String
                    do {
                        id = UUID.randomUUID().toString()
                    } while (!used.add(id))
                    snippet.copy(id = id)
                }
            }
        }

        fun effective(
            snippets: List<CodeSnippet>,
            target: ReplacementTarget?,
            isAppend: Boolean = false,
        ): List<CodeSnippet> = snippets.filter { it.enabled && it.appliesTo(target) && (!isAppend || it.runOnAppend) }
            .let { active ->
                active.filter { it.scope == RegexReplacement.GLOBAL } +
                    active.filter { it.scope == RegexReplacement.NOVEL }
            }

        private fun validateTargets(snippets: List<CodeSnippet>) {
            require(
                snippets.all {
                    when (it.scope) {
                        RegexReplacement.GLOBAL -> it.target == null
                        RegexReplacement.NOVEL -> it.target != null && it.target.path.isNotBlank()
                        else -> false
                    }
                },
            )
        }
    }
}

/** Reorder only occupied slots of a scope, leaving every other scope at its original index. */
internal fun <T> reorderScope(items: List<T>, scoped: List<T>, reordered: List<T>): List<T> {
    val scopedSet = scoped.toSet()
    require(scoped.size == reordered.size && scopedSet.size == scoped.size && scopedSet == reordered.toSet())
    val positions = items.indices.filter { items[it] in scopedSet }
    require(positions.size == scoped.size)
    return items.toMutableList().apply {
        positions.forEachIndexed { index, position -> this[position] = reordered[index] }
    }
}
