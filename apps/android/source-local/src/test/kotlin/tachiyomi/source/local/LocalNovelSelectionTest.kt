package tachiyomi.source.local

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga

class LocalNovelSelectionTest {

    @Test
    fun `all local novels requires a non-empty all-local selection`() {
        val localNovel = Manga.create().copy(source = LocalNovelSource.ID)
        val remoteNovel = Manga.create().copy(source = 2L)

        assertFalse(emptyList<Manga>().allLocalNovels())
        assertTrue(listOf(localNovel, localNovel).allLocalNovels())
        assertFalse(listOf(localNovel, remoteNovel).allLocalNovels())
    }
}
