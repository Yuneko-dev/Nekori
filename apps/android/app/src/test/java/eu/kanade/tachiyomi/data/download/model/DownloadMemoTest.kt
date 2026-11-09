package eu.kanade.tachiyomi.data.download.model

import eu.kanade.tachiyomi.source.CatalogueSource
import io.mockk.mockk
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

class DownloadMemoTest {

    @Test
    fun `download round trip preserves chapter memo`() {
        val memo = buildJsonObject { put("pluginData", "keep-me") }
        val chapter = Chapter.create().copy(id = 42L, mangaId = 7L, memo = memo)
        val manga = Manga.create().copy(id = 7L, title = "Novel")

        val restored = Download.from(manga, chapter, mockk<CatalogueSource>()).toDomainChapter()

        assertEquals(memo, restored.memo)
    }
}
