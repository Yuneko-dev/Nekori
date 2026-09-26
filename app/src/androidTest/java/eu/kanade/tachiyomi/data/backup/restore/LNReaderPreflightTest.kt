package eu.kanade.tachiyomi.data.backup.restore

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.util.Enumeration

@RunWith(AndroidJUnit4::class)
class LNReaderPreflightTest {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    @Test
    fun summaryMatchesRestoreCounts() {
        val summary = LNReaderLibrarySummary()
        val novels = listOf(
            """{"name":"Remote","chapters":[{"isDownloaded":true},{"isDownloaded":1},{"isDownloaded":0},{}]}""",
            """{"chapters":[{"isDownloaded":true}],"name":"Local","isLocal":1}""",
            """{"name":" ","chapters":[{"isDownloaded":true}]}""",
            """{"name":"Empty","chapters":null,"isLocal":null}""",
        )
        novels.forEach { value ->
            value.byteInputStream().use { summary.add(readLnReaderNovelSummary(it)) }
        }
        val restored = novels.map { json.decodeFromString<LNReaderBackupImporter.LNNovel>(it) }
            .filter { it.name.isNotBlank() }
        assertEquals(restored.count { !it.isLocal }, summary.novelCount)
        assertEquals(restored.count { it.isLocal }, summary.localNovelCount)
        assertEquals(restored.filterNot { it.isLocal }.sumOf { it.chapters.size }, summary.chapterCount)
        assertEquals(2, summary.downloadedChapterCount)
    }

    @Test
    fun countsLargeChapterArrayFromStream() {
        val chapter = """{"name":"ignored","path":"ignored","isDownloaded":1,"extra":{"items":[1,2,3]}}"""
        val chunks = sequence {
            yield("""{"name":"Large","chapters":[""")
            repeat(500_000) { yield(if (it == 0) chapter else ",$chapter") }
            yield("]}")
        }.map { ByteArrayInputStream(it.toByteArray()) }
        val iterator = chunks.iterator()
        val entries = object : Enumeration<InputStream> {
            override fun hasMoreElements() = iterator.hasNext()
            override fun nextElement(): InputStream = iterator.next()
        }
        SequenceInputStream(entries).use { input ->
            val novel = readLnReaderNovelSummary(input)
            assertEquals(500_000, novel.chapters.total)
            assertEquals(500_000, novel.chapters.downloaded)
        }
    }

    @Test
    fun rejectsTruncatedChaptersWithoutPartialCounts() {
        val summary = LNReaderLibrarySummary()
        assertThrows(Exception::class.java) {
            """{"name":"Broken","chapters":[{"isDownloaded":true},""".byteInputStream().use {
                summary.add(readLnReaderNovelSummary(it))
            }
        }
        assertEquals(0, summary.novelCount)
        assertEquals(0, summary.chapterCount)
    }
}
