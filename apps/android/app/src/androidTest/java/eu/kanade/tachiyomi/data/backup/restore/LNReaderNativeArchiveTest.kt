package eu.kanade.tachiyomi.data.backup.restore

import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import mihon.core.archive.forEachArchiveEntry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class LNReaderNativeArchiveTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var file: File

    @Before
    fun setUp() {
        file = File.createTempFile("lnreader-native-", ".zip", context.cacheDir)
    }

    @After
    fun tearDown() {
        file.delete()
    }

    @Test
    fun skipsUnreadCoverPayloadWithoutInflatingIt() = runBlocking {
        writeBackup("Covers/1.jpg" to "x".repeat(1_000_000), "Version.json" to "{}")
        // Reserved DEFLATE block type: any attempt to inflate the ignored cover must fail.
        RandomAccessFile(file, "rw").use {
            it.seek(26)
            val nameLength = java.lang.Short.reverseBytes(it.readShort()).toInt() and 0xffff
            val extraLength = java.lang.Short.reverseBytes(it.readShort()).toInt() and 0xffff
            it.seek(30L + nameLength + extraLength)
            it.write(7)
        }
        val visited = mutableListOf<String>()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        forEachLnReaderBackupEntry(context, uri) { name, _, input ->
            visited.add(name)
            if (name == "Version.json") assertEquals("{}", input.readBytes().decodeToString())
        }
        assertEquals(listOf("Covers/1.jpg", "Version.json"), visited)
    }

    @Test
    fun nativeReadsRespectBufferOffsetAndLength() = runBlocking {
        writeBackup("Version.json" to "abcdef")
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            forEachArchiveEntry(descriptor) { _, input ->
                val buffer = ByteArray(8) { '_'.code.toByte() }
                assertEquals(0, input.read(buffer, 2, 0))
                assertEquals(3, input.read(buffer, 2, 3))
                assertEquals("__abc___", buffer.decodeToString())
                assertEquals("def", input.readBytes().decodeToString())
            }
        }
    }

    @Test
    fun nativeReaderSupportsNonSeekableProviderDescriptors() = runBlocking {
        writeBackup("Version.json" to "{}")
        val pipe = ParcelFileDescriptor.createPipe()
        pipe[0].use { descriptor ->
            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(file.readBytes()) }
            var visited = 0
            forEachArchiveEntry(descriptor) { entry, input ->
                assertEquals("Version.json", entry.name)
                assertEquals("{}", input.readBytes().decodeToString())
                visited++
            }
            assertEquals(1, visited)
        }
    }

    @Test
    fun cancellationStopsTraversal() {
        writeBackup("1.json" to "{}", "2.json" to "{}")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        var visited = 0
        assertThrows(CancellationException::class.java) {
            runBlocking {
                forEachLnReaderBackupEntry(context, uri) { _, _, _ ->
                    visited++
                    throw CancellationException("Cancelled")
                }
            }
        }
        assertEquals(1, visited)
    }

    private fun writeBackup(vararg entries: Pair<String, String>) {
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, value) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(value.toByteArray())
                zip.closeEntry()
            }
        }
    }
}
