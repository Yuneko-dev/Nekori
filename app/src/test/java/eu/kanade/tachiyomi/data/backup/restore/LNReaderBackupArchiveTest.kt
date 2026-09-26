package eu.kanade.tachiyomi.data.backup.restore

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileNotFoundException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LNReaderBackupArchiveTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `stream-only document providers retain the sequential fallback`() = runBlocking {
        val file = File(directory, "stream.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("Version.json"))
            zip.write("{}".toByteArray())
            zip.closeEntry()
        }
        val context = mockk<Context>()
        val resolver = mockk<ContentResolver>()
        val uri = mockk<Uri>()
        every { context.contentResolver } returns resolver
        every { resolver.openFileDescriptor(uri, "r") } throws FileNotFoundException("Stream only")
        every { resolver.openInputStream(uri) } answers { file.inputStream() }
        var visited = 0
        forEachLnReaderBackupEntry(context, uri) { name, _, input ->
            assertEquals("Version.json", name)
            assertEquals("{}", input.readBytes().decodeToString())
            visited++
        }
        assertEquals(1, visited)
    }
}
