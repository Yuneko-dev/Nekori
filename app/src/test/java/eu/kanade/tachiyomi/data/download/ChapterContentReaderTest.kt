package eu.kanade.tachiyomi.data.download

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import mihon.core.archive.ArchiveEntry
import mihon.core.archive.ArchiveReader
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class ChapterContentReaderTest {

    private val context = mockk<Context>()
    private val resolver = mockk<ContentResolver>()
    private val reader = ChapterContentReader(context, mockk())

    init {
        every { context.contentResolver } returns resolver
    }

    @Test
    fun `export reader lists a directory once and reads referenced images`() {
        val contentUri = mockk<Uri>()
        val imageUri = mockk<Uri>()
        val contentFile = file("001.html", contentUri)
        val imageFile = file("cover.jpg", imageUri)
        val directory = directory(contentFile, imageFile)
        every { resolver.openInputStream(contentUri) } returns ByteArrayInputStream(
            "<p>Text</p><img src=\"cover.jpg\">".encodeToByteArray(),
        )
        every { resolver.openInputStream(imageUri) } returns ByteArrayInputStream(byteArrayOf(1, 2, 3))

        val export = reader.readExportContent(directory)

        assertTrue(export!!.content.contains("tsundoku-novel-image://cover.jpg"))
        assertArrayEquals(byteArrayOf(1, 2, 3), export.images.getValue("cover.jpg"))
        verify(exactly = 1) { directory.listFiles() }
        verify(exactly = 1) { resolver.openInputStream(contentUri) }
        verify(exactly = 1) { resolver.openInputStream(imageUri) }
    }

    @Test
    fun `export reader does not open images when content has no image reference`() {
        val contentUri = mockk<Uri>()
        val imageUri = mockk<Uri>()
        val contentFile = file("001.html", contentUri)
        val imageFile = file("cover.jpg", imageUri)
        val directory = directory(contentFile, imageFile)
        every { resolver.openInputStream(contentUri) } returns ByteArrayInputStream("<p>Text</p>".encodeToByteArray())

        val export = reader.readExportContent(directory)

        assertEquals("<p>Text</p>", export!!.content)
        assertTrue(export.images.isEmpty())
        verify(exactly = 1) { directory.listFiles() }
        verify(exactly = 1) { resolver.openInputStream(contentUri) }
        verify(exactly = 0) { resolver.openInputStream(imageUri) }
    }

    @Test
    fun `export reader consumes a chapter archive in one pass`() {
        val archive = mockk<ArchiveReader>()
        every { archive.forEachEntry(any()) } answers {
            val visit = firstArg<(ArchiveEntry, InputStream) -> Unit>()
            visit(ArchiveEntry("002.html", true), stream("<p>Second</p>"))
            visit(ArchiveEntry("images/cover.jpg", true), ByteArrayInputStream(byteArrayOf(1, 2, 3)))
            visit(ArchiveEntry("001.html", true), stream("<p>First</p><img src=\"cover.jpg\">"))
        }

        val export = reader.readExportContentFromArchive(archive)

        assertTrue(export!!.content.startsWith("<p>First</p>"))
        assertTrue(export.content.endsWith("<p>Second</p>"))
        assertTrue(export.content.contains("tsundoku-novel-image://cover.jpg"))
        assertArrayEquals(byteArrayOf(1, 2, 3), export.images.getValue("cover.jpg"))
        verify(exactly = 1) { archive.forEachEntry(any()) }
        verify(exactly = 0) { archive.getInputStream(any()) }
    }

    private fun directory(vararg files: UniFile): UniFile = mockk {
        every { isFile } returns false
        every { listFiles() } returns arrayOf(*files)
    }

    private fun file(fileName: String, fileUri: Uri): UniFile = mockk {
        every { name } returns fileName
        every { isFile } returns true
        every { uri } returns fileUri
    }

    private fun stream(content: String) = ByteArrayInputStream(content.encodeToByteArray())
}
