@file:Suppress("ktlint:standard:filename")

package eu.kanade.tachiyomi.ui.reader.setting

import kotlinx.serialization.json.Json
import tachiyomi.core.common.preference.ContentPreferencesLock
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.translation.model.NovelGuidelinesAssignments
import tachiyomi.domain.translation.model.UserGuidelines

/** Validation at import boundaries; each collection remains one ordinary string preference. */
object NovelContentPreferences {
    const val CSS_KEY = CodeSnippet.CSS_KEY
    const val JS_KEY = CodeSnippet.JS_KEY
    const val GUIDELINES_KEY = UserGuidelines.PREFERENCE_KEY

    val collectionKeys = setOf(
        RegexReplacement.PREFERENCE_KEY,
        CSS_KEY,
        JS_KEY,
        GUIDELINES_KEY,
        NovelGuidelinesAssignments.PREFERENCE_KEY,
    )

    fun validatedRestoreValue(key: String, raw: String): String = when (key) {
        RegexReplacement.PREFERENCE_KEY -> raw.also { RegexReplacement.decode(it) }
        CSS_KEY, JS_KEY -> CodeSnippet.encode(CodeSnippet.repairIds(CodeSnippet.decode(raw), raw))
        GUIDELINES_KEY -> Json.encodeToString(UserGuidelines.decode(raw))
        NovelGuidelinesAssignments.PREFERENCE_KEY ->
            NovelGuidelinesAssignments.encode(NovelGuidelinesAssignments.decode(raw))
        else -> raw
    }

    /** Capture before SQL work, so an edit made during deletion always wins over cleanup. */
    fun cleanupSnapshot(store: PreferenceStore): Map<String, String> = synchronized(ContentPreferencesLock) {
        (collectionKeys - GUIDELINES_KEY).associateWith { store.getString(it, "[]").get() }
    }

    /** Failed/changed collections retain their complete value; callers report partial cleanup. */
    fun cleanupDeletedTargets(
        store: PreferenceStore,
        original: Map<String, String>,
        deleted: Set<ReplacementTarget>,
    ): Set<String> = synchronized(ContentPreferencesLock) {
        if (deleted.isEmpty()) return@synchronized emptySet()
        original.mapNotNullTo(mutableSetOf()) { (key, raw) ->
            val preference = store.getString(key, "[]")
            if (preference.get() != raw) return@mapNotNullTo key
            runCatching {
                val updated = withoutTargets(key, raw, deleted)
                if (updated != raw) preference.set(updated)
            }.exceptionOrNull()?.let { key }
        }
    }

    internal fun withoutTargets(key: String, raw: String, deleted: Set<ReplacementTarget>): String = when (key) {
        RegexReplacement.PREFERENCE_KEY -> {
            val rules = RegexReplacement.decode(raw)
            val kept = rules.filterNot { it.scope == RegexReplacement.NOVEL && it.target in deleted }
            if (kept.size == rules.size) raw else Json.encodeToString(kept)
        }
        CSS_KEY, JS_KEY -> {
            val snippets = CodeSnippet.decode(raw)
            val kept = snippets.filterNot { it.scope == RegexReplacement.NOVEL && it.target in deleted }
            if (kept.size == snippets.size) {
                raw
            } else {
                // Repair before filtering: raw positions describe the complete collection.
                CodeSnippet.encode(
                    CodeSnippet.repairIds(snippets, raw)
                        .filterNot { it.scope == RegexReplacement.NOVEL && it.target in deleted },
                )
            }
        }
        NovelGuidelinesAssignments.PREFERENCE_KEY -> {
            val assignments = NovelGuidelinesAssignments.decode(raw)
            val kept = assignments.filterNot { it.target in deleted }
            if (kept.size == assignments.size) raw else NovelGuidelinesAssignments.encode(kept)
        }
        else -> raw
    }
}
