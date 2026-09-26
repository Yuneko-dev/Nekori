package eu.kanade.tachiyomi.data.backup.restore

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class LNReaderPreflightFormatsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory: File

    @Before
    fun setUp() {
        directory = Files.createTempDirectory(context.cacheDir.toPath(), "lnreader-formats-").toFile()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun supportsImplicitV1ExplicitV1AndV2() = runBlocking {
        for (version in listOf(0, 1, 2)) {
            val summary = preflight(backup(version))
            assertEquals(if (version == 0) 1 else version, summary.formatVersion)
            assertEquals(1, summary.novelCount)
            assertEquals(2, summary.chapterCount)
            assertEquals(1, summary.downloadedChapterCount)
            assertEquals(1, summary.categoryCount)
            assertTrue(summary.hasLibrary)
            assertTrue(summary.hasSettings)
            assertTrue(summary.hasPlugins)
            assertTrue(summary.hasDownloadedFiles)
        }
    }

    @Test
    fun rejectsMissingDeclaredV2Sections() {
        for (missing in listOf("Plugins.json", "plugins.zip", "novel-files.zip")) {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { preflight(backup(2).apply { remove(missing) }) }
            }
        }
    }

    @Test
    fun permitsDisabledV2Sections() = runBlocking {
        val summary = preflight(
            backup(2).apply {
                remove("plugins.zip")
                remove("Plugins.json")
                remove("novel-files.zip")
                put(
                    "Version.json",
                    """{"appVersion":"2.1.3","formatVersion":2,"sections":{"plugins":false,"downloadedFiles":false}}""",
                )
            },
        )
        assertEquals(false, summary.hasPlugins)
        assertEquals(false, summary.hasDownloadedFiles)
        assertEquals(2, summary.chapterCount)
    }

    @Test
    fun permitsLibraryOnlyV1() = runBlocking {
        val summary = preflight(backup(0).apply { remove("download.zip") })
        assertEquals(false, summary.hasPlugins)
        assertEquals(false, summary.hasDownloadedFiles)
        assertEquals(1, summary.novelCount)
    }

    @Test
    fun rejectsMalformedLibraryInsteadOfReturningPartialCounts() {
        for (name in listOf("Category.json", "NovelAndChapters/1.json")) {
            assertThrows(Exception::class.java) {
                runBlocking { preflight(backup(2).apply { put(name, "{") }) }
            }
        }
    }

    @Test
    fun countsMetadataWithoutRetainingItsValues() = runBlocking {
        val summary = preflight(
            backup(2).apply {
                put("Plugins.json", """[{"id":"one"},{"id":"two"}]""")
                put("Setting.json", """{"nested":{"items":[1,2,3]},"text":"ignored"}""")
                put("ApiKeys.json", """{"provider":"test-value"}""")
            },
        )
        assertEquals(2, summary.pluginCount)
        assertTrue(summary.hasSettings)
        assertTrue(summary.hasApiKeys)
    }
    private fun backup(version: Int): MutableMap<String, String> = linkedMapOf(
        "Category.json" to """[{"id":1,"name":"Category","novelIds":[1]}]""",
        "NovelAndChapters/1.json" to if (version < 2) {
            """{"name":"Novel","chapters":[{"isDownloaded":1},{"isDownloaded":0}]}"""
        } else {
            """{"name":"Novel","chapters":[{"isDownloaded":true},{"isDownloaded":false}]}"""
        },
        "Setting.json" to "{}",
    ).apply {
        if (version < 2) {
            put("download.zip", "")
        } else {
            put("Plugins.json", "[]")
            put("plugins.zip", "")
            put("novel-files.zip", "")
        }
        // The manifest may be last; traversal must not depend on ZIP entry order.
        put(
            "Version.json",
            if (version == 0) """{"version":"2.0.2"}""" else """{"appVersion":"2.1.3","formatVersion":$version}""",
        )
    }

    private suspend fun preflight(entries: Map<String, String>): LNReaderBackupImporter.PreflightSummary {
        val file = File(directory, "backup.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, value) ->
                zip.putNextEntry(ZipEntry(name))
                val bytes = if (name.endsWith(".zip")) {
                    ByteArrayOutputStream().apply { ZipOutputStream(this).use { } }.toByteArray()
                } else {
                    value.toByteArray()
                }
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        return LNReaderBackupImporter(context).preflight(uri)
    }
}
