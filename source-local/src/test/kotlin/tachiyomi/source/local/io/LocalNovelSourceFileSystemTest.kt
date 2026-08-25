package tachiyomi.source.local.io

import com.hippo.unifile.UniFile
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.storage.service.StorageManager

class LocalNovelSourceFileSystemTest {

    @Test
    fun `delete novel accepts a single file entry`() {
        val novel = mockk<UniFile> {
            every { isDirectory } returns false
            every { delete() } returns true
        }
        val base = mockk<UniFile> {
            every { findFile("book.epub") } returns novel
        }
        val fileSystem = LocalNovelSourceFileSystem(
            mockk<StorageManager> {
                every { getLocalNovelSourceDirectory() } returns base
            },
        )

        assertTrue(fileSystem.deleteNovel("book.epub"))
        verify(exactly = 1) { novel.delete() }
    }
}
