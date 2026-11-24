package eu.kanade.tachiyomi.source.model

/**
 * One autocomplete entry for a filter input.
 *
 * [label] is what the user sees; [value] is what the source receives.
 */
data class FilterSuggestion(
    val label: String,
    val value: String,
)
