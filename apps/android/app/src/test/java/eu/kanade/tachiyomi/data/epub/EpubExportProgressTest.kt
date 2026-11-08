package eu.kanade.tachiyomi.data.epub

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class EpubExportProgressTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `throttles intermediate updates but keeps first boundary and forced updates`() {
        assertTrue(EpubExportJob.shouldNotifyEpubProgress(now = 1, lastNotifyAt = 0, force = false))
        assertFalse(EpubExportJob.shouldNotifyEpubProgress(now = 1_499, lastNotifyAt = 1_000, force = false))
        assertTrue(EpubExportJob.shouldNotifyEpubProgress(now = 1_500, lastNotifyAt = 1_000, force = false))
        assertTrue(EpubExportJob.shouldNotifyEpubProgress(now = 1_001, lastNotifyAt = 1_000, force = true))
    }

    @Test
    fun `writes a readable stored outer zip entry with real size and crc`() = runTest {
        val payload = "EPUB payload".toByteArray()
        val source = tempDir.resolve("source.epub").toFile().apply { writeBytes(payload) }
        val expectedCrc = CRC32().apply { update(payload) }.value
        val output = ByteArrayOutputStream()

        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(
                EpubExportJob.createEpubBundleEntry(
                    entryName = "Tên truyện.epub",
                    fileSize = source.length(),
                    crc = EpubExportJob.calculateEpubBundleCrc(source),
                    compressionLevel = 0,
                ),
            )
            source.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }

        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            val entry = zip.nextEntry
            assertEquals("Tên truyện.epub", entry.name)
            assertEquals(ZipEntry.STORED, entry.method)
            assertEquals(payload.size.toLong(), entry.size)
            assertEquals(expectedCrc, entry.crc)
            assertArrayEquals(payload, zip.readBytes())
        }
    }
}
