package tachiyomi.core.common.storage

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class UniFileExtensionsTest {
    @Test
    fun `raw file creation preserves exact names and only cleans unexpected returned names`() {
        val context = mockk<Context>()
        val uri = mockk<Uri> { every { scheme } returns "file" }
        val image = mockk<UniFile> {
            every { name } returns "sky.png"
            every { delete() } returns true
        }
        val directory = mockk<UniFile> {
            every { this@mockk.uri } returns uri
            every { createFile("sky.png") } returns image
        }
        assertEquals(image, directory.createFileWithExactName(context, "sky.png"))
        verify(exactly = 0) { image.delete() }
        every { image.name } returns "sky.png.bin"
        assertNull(directory.createFileWithExactName(context, "sky.png"))
        verify(exactly = 1) { image.delete() }
    }
}
