package tachiyomi.domain.storage.service

import com.hippo.unifile.UniFile
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

class StorageManagerTest {

    @Test
    fun `directory size includes nested files`() {
        val first = file(100)
        val second = file(50)
        val nested = directory(second)

        directory(first, nested).sizeInBytes() shouldBe 150L
    }

    private fun file(size: Long) = mockk<UniFile> {
        every { isDirectory } returns false
        every { length() } returns size
    }

    private fun directory(vararg children: UniFile) = mockk<UniFile> {
        every { isDirectory } returns true
        every { listFiles() } returns children
    }
}
