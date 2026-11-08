package tachiyomi.source.local.metadata

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EpubReaderExtensionsTest {

    @Test
    fun `missing EPUB cover uses LNReader placeholder`() {
        assertEquals(DEFAULT_EPUB_COVER_URL, null.orDefaultEpubCover())
        assertEquals(DEFAULT_EPUB_COVER_URL, "".orDefaultEpubCover())
        assertEquals("EPUB/cover.webp", "EPUB/cover.webp".orDefaultEpubCover())
    }
}
