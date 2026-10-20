@file:Suppress("ktlint:standard:filename")

package eu.kanade.tachiyomi.ui.reader.setting

import androidx.compose.runtime.mutableStateOf
import tachiyomi.core.common.preference.ContentPreferencesLock
import tachiyomi.core.common.preference.Preference

internal class CodeSnippetsDraft(val original: String, decoded: List<CodeSnippet>) {
    val initial = decoded
    val snippets = mutableStateOf(CodeSnippet.repairIds(decoded, original))

    fun save(preference: Preference<String>): Boolean = synchronized(ContentPreferencesLock) {
        if (preference.get() != original) return@synchronized false
        if (snippets.value != initial) preference.set(CodeSnippet.encode(snippets.value))
        true
    }

    fun update(updated: CodeSnippet) {
        val current = snippets.value
        val previous = current.firstOrNull { it.id == updated.id }
        snippets.value = when {
            previous == null -> current + updated
            previous.scope != updated.scope || previous.target != updated.target -> current.filterNot {
                it.id ==
                    updated.id
            } +
                updated
            else -> current.map { if (it.id == updated.id) updated else it }
        }
    }
}
