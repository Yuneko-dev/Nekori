@file:Suppress("ktlint:standard:filename")

package eu.kanade.tachiyomi.ui.reader.loader

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import mihon.core.archive.ArchiveReader
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class ArchiveAssetStreamTest {
    @Test
    fun `copying an archive asset closes the native entry before returning its bytes`() = runBlocking {
        var closed = 0
        val input = object : ByteArrayInputStream("image".toByteArray()) {
            override fun close() {
                closed++
                super.close()
            }
        }
        val reader = mockk<ArchiveReader> { every { getInputStream("cover.jpg") } returns input }
        val result = ArchivePageLoader(reader).getPageDataStream("cover.jpg")!!
        result.use { assertArrayEquals("image".toByteArray(), it.readBytes()) }
        assertEquals(1, closed)
    }

    @Test
    fun `failed archive asset copy still closes the native entry`() {
        var closed = 0
        val input = object : InputStream() {
            override fun read(): Int = throw IOException("asset failed")
            override fun close() {
                closed++
            }
        }
        val reader = mockk<ArchiveReader> { every { getInputStream("cover.jpg") } returns input }
        assertThrows(IOException::class.java) {
            runBlocking { ArchivePageLoader(reader).getPageDataStream("cover.jpg") }
        }
        assertEquals(1, closed)
    }
}
