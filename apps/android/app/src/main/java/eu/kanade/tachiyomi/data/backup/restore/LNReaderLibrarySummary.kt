package eu.kanade.tachiyomi.data.backup.restore

import android.util.JsonReader
import android.util.JsonToken
import java.io.InputStream

internal class LNReaderLibrarySummary {
    var novelCount = 0
        private set
    var localNovelCount = 0
        private set
    var chapterCount = 0
        private set
    var downloadedChapterCount = 0
        private set

    var categoryCount = 0
    var pluginCount = 0
    var hasSettings = false
    var hasApiKeys = false

    fun add(novel: LNReaderNovelSummary) {
        if (novel.name.isBlank()) return
        if (novel.isLocal) {
            localNovelCount++
        } else {
            novelCount++
            chapterCount += novel.chapters.total
            downloadedChapterCount += novel.chapters.downloaded
        }
    }
}

internal data class LNReaderNovelSummary(
    val name: String,
    val isLocal: Boolean,
    val chapters: LNReaderChapterCounts,
)

internal data class LNReaderChapterCounts(val total: Int = 0, val downloaded: Int = 0)

/** Skip unused JSON values directly, without allocating chapter objects or JSON primitives. */
internal fun readLnReaderNovelSummary(input: InputStream): LNReaderNovelSummary {
    // The archive owns the input; closing this reader would close the current archive stream.
    val reader = JsonReader(input.reader(Charsets.UTF_8)).apply { isLenient = true }
    var name = ""
    var isLocal = false
    var chapters = LNReaderChapterCounts()
    reader.beginObject()
    while (reader.hasNext()) {
        when (reader.nextName()) {
            "name" -> if (reader.peek() == JsonToken.NULL) reader.nextNull() else name = reader.nextString()
            "isLocal" -> isLocal = reader.readLnReaderBoolean()
            "chapters" -> {
                chapters = if (reader.peek() == JsonToken.NULL) {
                    reader.nextNull()
                    LNReaderChapterCounts()
                } else {
                    reader.readChapterCounts()
                }
            }
            else -> reader.skipValue()
        }
    }
    reader.endObject()
    require(reader.peek() == JsonToken.END_DOCUMENT) { "Unexpected data after novel JSON" }
    return LNReaderNovelSummary(name, isLocal, chapters)
}

private fun JsonReader.readChapterCounts(): LNReaderChapterCounts {
    var total = 0
    var downloaded = 0
    beginArray()
    while (hasNext()) {
        var isDownloaded = false
        beginObject()
        while (hasNext()) {
            if (nextName() == "isDownloaded") isDownloaded = readLnReaderBoolean() else skipValue()
        }
        endObject()
        total++
        if (isDownloaded) downloaded++
    }
    endArray()
    return LNReaderChapterCounts(total, downloaded)
}

/** Same boolean/integer/string semantics as FlexibleBooleanSerializer used during restore. */
private fun JsonReader.readLnReaderBoolean(): Boolean = when (peek()) {
    JsonToken.BOOLEAN -> nextBoolean()
    JsonToken.NULL -> {
        nextNull()
        false
    }
    else -> nextString().let { it.toBooleanStrictOrNull() ?: it.toIntOrNull()?.let { number -> number != 0 } ?: false }
}

/** Count entries without retaining settings, credentials or category membership lists. */
internal fun readLnReaderMetadataCount(input: InputStream, objectRoot: Boolean = false): Int {
    val reader = JsonReader(input.reader(Charsets.UTF_8)).apply { isLenient = true }
    if (objectRoot) reader.beginObject() else reader.beginArray()
    var count = 0
    while (reader.hasNext()) {
        if (objectRoot) reader.nextName()
        reader.skipValue()
        count++
    }
    if (objectRoot) reader.endObject() else reader.endArray()
    require(reader.peek() == JsonToken.END_DOCUMENT) { "Unexpected data after metadata JSON" }
    return count
}
