package eu.kanade.tachiyomi.source

import eu.kanade.domain.source.service.SourcePreferences
import tachiyomi.domain.source.model.JS_SOURCE_MARKER
import tachiyomi.domain.source.model.StubSource
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

fun Source.getNameForMangaInfo(): String {
    val preferences = Injekt.get<SourcePreferences>()
    val enabledLanguages = preferences.enabledLanguages.get()
        .filterNot { it in listOf("all", "other") }
    val hasOneActiveLanguages = enabledLanguages.size == 1
    val isInEnabledLanguages = lang in enabledLanguages
    return when {
        // For edge cases where user disables a source they got manga of in their library.
        hasOneActiveLanguages && !isInEnabledLanguages -> toString().removeSuffix(JS_SOURCE_MARKER)
        // Hide the language tag when only one language is used.
        hasOneActiveLanguages && isInEnabledLanguages -> name
        else -> toString().removeSuffix(JS_SOURCE_MARKER)
    }
}

fun Source.isLocalOrStub(): Boolean = isLocal() || this is StubSource

// Local sources have no language toggle of their own (local reports "other"), so they are never
// filtered out.
fun <T : Source> List<T>.filterEnabledLanguages(preferences: SourcePreferences = Injekt.get()): List<T> {
    val enabledLanguages = preferences.enabledLanguages.get()
    return filter { it.isLocal() || it.lang in enabledLanguages }
}

fun <T : Source> List<T>.filterUserEnabled(preferences: SourcePreferences = Injekt.get()): List<T> {
    val disabledSources = preferences.disabledSources.get()
    return filterEnabledLanguages(preferences).filter { it.isLocal() || "${it.id}" !in disabledSources }
}
