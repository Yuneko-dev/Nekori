package eu.kanade.presentation.reader

import kotlinx.serialization.json.Json

enum class StatusBarItem(val id: String) {
    TIME("time"),
    CHAPTER("chapter"),
    PROGRESS("progress"),
    BATTERY("battery"),
    WORD_COUNT("word_count"),
}

val DefaultStatusBarOrder = listOf(
    StatusBarItem.TIME,
    StatusBarItem.CHAPTER,
    StatusBarItem.PROGRESS,
    StatusBarItem.BATTERY,
    StatusBarItem.WORD_COUNT,
)

fun List<StatusBarItem>.serializeStatusBarOrder(): String = Json.encodeToString(map { it.id })

fun String.deserializeStatusBarOrder(): List<StatusBarItem> {
    // Merge with DefaultStatusBarOrder so an item added in a future app update isn't
    // silently dropped for existing users — it gets appended at the end.
    // Falls back to defaults on malformed input, e.g. a value saved in the pre-JSON
    // comma-separated format by an older build.
    val savedIds = runCatching { Json.decodeFromString<List<String>>(this) }.getOrDefault(emptyList())
    val saved = savedIds.mapNotNull { id -> StatusBarItem.entries.find { it.id == id } }
    val missing = DefaultStatusBarOrder.filter { it !in saved }
    return (saved + missing).distinct()
}
