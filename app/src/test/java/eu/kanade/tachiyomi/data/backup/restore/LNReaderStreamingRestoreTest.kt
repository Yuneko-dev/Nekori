package eu.kanade.tachiyomi.data.backup.restore

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.restore.restorers.CategoriesRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.jsplugin.BackupPluginInstallResult
import eu.kanade.tachiyomi.jsplugin.JsPluginManager
import eu.kanade.tachiyomi.jsplugin.model.JsPlugin
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.library.service.LibraryPreferences
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FilterInputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LNReaderStreamingRestoreTest {
    @TempDir lateinit var directory: File
    private val context = mockk<Context>()
    private val resolver = mockk<ContentResolver>()
    private val uri = mockk<Uri>()
    private val plugins = mockk<JsPluginManager>(relaxed = true)
    private val categories = mockk<CategoriesRestorer>(relaxed = true)
    private val manga = mockk<MangaRestorer>()
    private val notifier = mockk<BackupNotifier>()
    private val plugin = JsPlugin("test", "Test", "https://example.org", version = "1")
    private var opens = 0
    private var restoreBytesRead = 0L

    @Test
    fun `v1 and v2 write categories plugins and chapters before reading the whole library`() = runBlocking {
        for (format in listOf(1, 2)) {
            opens = 0
            restoreBytesRead = 0
            val file = backup(format)
            val importer = importer(file)
            var restored = 0
            coEvery { manga.restore(any(), any()) } coAnswers {
                if (restored == 0) {
                    assertEquals(2, opens)
                    assertTrue(
                        restoreBytesRead < file.length() / 2,
                        "First novel must be saved before later novels are decoded",
                    )
                }
                val value = firstArg<BackupManga>()
                assertTrue(value.favorite)
                assertEquals(200, value.chapters.size)
                assertEquals(1, value.categories.size)
                restored++
                restored.toLong()
            }
            val result = importer.import(uri, LNReaderBackupImporter.ImportOptions(restoreLocalNovels = false))
            assertEquals(100, restored, result.logFile.readText())
            assertEquals(100, result.novelCount)
            assertEquals(1, result.categoryCount)
            assertEquals(1, result.installedPluginCount)
            assertEquals(0, result.errorCount)
            coVerify { categories.invoke(match { it.single().name == "Saved" }) }
            coVerify { plugins.installPluginFromBackup(any(), "plugin code", null, null, null) }
            assertTrue(
                directory.listFiles().orEmpty().none {
                    it.name.startsWith("lnreader-plugins-") ||
                        it.name.startsWith("lnreader-download-")
                },
            )
        }
    }

    @Test
    fun `cancellation while saving a novel stops restore`() {
        val importer = importer(backup(2))
        coEvery { manga.restore(any(), any()) } throws CancellationException("stop")
        assertThrows(CancellationException::class.java) { runBlocking { importer.import(uri) } }
        coVerify(exactly = 1) { manga.restore(any(), any()) }
        assertTrue(directory.listFiles().orEmpty().none { it.name.startsWith("lnreader-plugins-") })
    }

    @Test
    fun `invalid manifest prevents writes and cleans extracted archives`() {
        val importer = importer(backup(3))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { importer.import(uri) } }
        coVerify(exactly = 0) { categories.invoke(any()) }
        coVerify(exactly = 0) { manga.restore(any(), any()) }
        assertTrue(directory.listFiles().orEmpty().none { it.name.startsWith("lnreader-plugins-") })
    }

    @Test
    fun failedWritesAreReportedRatherThanCountedAsRestored() = runBlocking {
        val importer = importer(backup(2))
        coEvery { manga.restore(any(), any()) } coAnswers {
            if (firstArg<BackupManga>().url == "/0") error("Database write failed")
            1L
        }
        val result = importer.import(uri)
        assertEquals(99, result.novelCount)
        assertEquals(1, result.errorCount)
        assertTrue(result.logFile.readText().contains("Database write failed"))
    }

    private fun importer(file: File): LNReaderBackupImporter {
        every { notifier.showRestoreProgress(any(), any(), any(), any(), any()) } answers {
            val title = firstArg<String>()
            if (title.startsWith("Reading backup metadata")) assertTrue(arg<Boolean>(4))
            if (title.startsWith("Restoring novels")) {
                assertEquals(100, arg<Int>(2))
                assertTrue(arg<Int>(1) in 0..100)
            }
            mockk(relaxed = true)
        }
        every { context.contentResolver } returns resolver
        every { context.cacheDir } returns directory
        every { resolver.openFileDescriptor(uri, "r") } throws FileNotFoundException("Stream provider")
        every { resolver.openInputStream(uri) } answers {
            opens++
            if (opens == 1) {
                file.inputStream()
            } else {
                object : FilterInputStream(file.inputStream()) {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                        super.read(buffer, offset, length).also { if (it > 0) restoreBytesRead += it }
                }
            }
        }
        every { plugins.availablePlugins } returns MutableStateFlow(emptyList())
        every { plugins.installedPlugins } returns MutableStateFlow(emptyList())
        coEvery { plugins.installPluginFromBackup(any(), any(), any(), any(), any()) } returns
            BackupPluginInstallResult(plugin, installed = true)
        return LNReaderBackupImporter(
            context = context, notifier = notifier, jsPluginManager = plugins, categoriesRestorer = categories,
            mangaRestorer = manga, getMangaByUrlAndSourceId = mockk(relaxed = true),
            stubSourceRepository = mockk(relaxed = true), sourceManager = mockk(relaxed = true),
            downloadProvider = mockk(relaxed = true), coverCache = mockk(relaxed = true),
            downloadCache = mockk(relaxed = true), getLibraryManga = mockk(relaxed = true),
            libraryPreferences = LibraryPreferences(
                InMemoryPreferenceStore(),
            ),
            localNovelFileSystem = mockk(relaxed = true),
            settingsRestorer = mockk(relaxed = true),
        )
    }

    private fun backup(format: Int): File {
        val nested = ByteArrayOutputStream().apply {
            ZipOutputStream(this).use { zip ->
                zip.putNextEntry(ZipEntry(if (format == 1) "Plugins/test/index.js" else "test/index.js"))
                zip.write("plugin code".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        val file = File(directory, "backup-$format.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.setLevel(Deflater.NO_COMPRESSION)
            fun entry(name: String, value: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(value.toByteArray())
                zip.closeEntry()
            }
            repeat(100) { id ->
                val chapters = (1..200).joinToString(",") { chapter ->
                    """{"id":$chapter,"name":"Chapter $chapter","path":"/$id/$chapter","unread":true}"""
                }
                entry(
                    "NovelAndChapters/$id.json",
                    """{"id":$id,"name":"Novel $id","path":"/$id","pluginId":"test","inLibrary":true,"chapters":[$chapters]}""",
                )
            }
            entry("Category.json", """[{"name":"Saved","novelIds":[${(0..99).joinToString(",")}]}]""")
            entry("Plugins.json", """[{"id":"test","name":"Test","site":"https://example.org","version":"1"}]""")
            zip.putNextEntry(ZipEntry(if (format == 1) "download.zip" else "plugins.zip"))
            zip.write(nested)
            zip.closeEntry()
            entry(
                "Version.json",
                """{"appVersion":"2.1.3","formatVersion":$format,"sections":{"downloadedFiles":false}}""",
            )
        }
        return file
    }
}
