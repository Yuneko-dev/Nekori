package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.RegexReplacement
import eu.kanade.tachiyomi.ui.reader.setting.ReplacementTarget
import logcat.LogPriority
import logcat.logcat

object RegexReplacementsProcessor {
    private data class CacheKey(val json: String, val target: ReplacementTarget?)
    private val cache = object : LinkedHashMap<CacheKey, List<Pair<RegexReplacement, Regex>>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, List<Pair<RegexReplacement, Regex>>>) =
            size > 16
    }

    fun apply(content: String, preferences: ReaderPreferences, target: ReplacementTarget? = null): String {
        val key = CacheKey(preferences.novelRegexReplacements.get(), target)
        val compiled = synchronized(cache) {
            cache.getOrPut(key) {
                try {
                    RegexReplacement.decode(key.json)
                        .filter { it.enabled && it.appliesTo(target) }
                        .sortedBy { if (it.scope == RegexReplacement.GLOBAL) 0 else 1 }
                        .mapNotNull { rule ->
                            runCatching { rule to rule.compile() }.getOrNull()
                        }
                } catch (_: Exception) {
                    logcat(LogPriority.WARN) { "Could not decode replacement rules; leaving content unchanged" }
                    emptyList()
                }
            }
        }
        return compiled.fold(content) { text, (rule, regex) ->
            try {
                rule.replace(text, regex)
            } catch (_: Exception) {
                logcat(LogPriority.WARN) { "Could not apply replacement rule" }
                text
            }
        }
    }
}
