package eu.kanade.tachiyomi.data.coil

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.MangaCover

class CoverThemeCacheTest {
    @Test
    fun `known unavailable cover is recognized by path without matching other covers`() {
        listOf(
            "https://github.com/Yuneko-dev/Nekori-plugins/blob/master/public/static/coverNotAvailable.webp?raw=true",
            "https://example.org/static/coverNotAvailable.webp#cover",
            "https://example.org/static/coverNotAvailable.webp",
            "https://example.org/static/coverNotAvailable.webp?v=2#cover",
        ).forEach { assertTrue(isUnavailableCover(it), it) }
        listOf(
            "static/coverNotAvailable.webp",
            "/static/coverNotAvailable.webp",
            "https://github.com/Yuneko-dev/Nekori-plugins/blob/master/public?test=static/coverNotAvailable.webp&raw=true",
            "https://example.org/public#static/coverNotAvailable.webp",
            null,
            "",
            "https://example.org/cover.webp?fallback=/static/coverNotAvailable.webp",
            "https://example.org/notstatic/coverNotAvailable.webp",
            "https://example.org/static/coverNotAvailable.webp.backup",
        ).forEach { assertFalse(isUnavailableCover(it), it) }
    }

    @Test
    fun `library changes reuse the seed but changed covers do not`() {
        val cover = MangaCover(101, 1, false, "cover.jpg", 1)
        CoverThemeCache.put(cover, 0xff123456.toInt())
        assertEquals(0xff123456.toInt(), CoverThemeCache[cover.copy(isMangaFavorite = true)])
        assertFalse(CoverThemeCache.contains(cover.copy(lastModified = 2)))
        assertFalse(CoverThemeCache.contains(cover.copy(url = "replacement.jpg")))
        assertFalse(CoverThemeCache.contains(cover.copy(sourceId = 2)))
    }

    @Test
    fun `covers without a suitable color are remembered`() {
        val cover = MangaCover(102, 1, false, "blank.jpg", 1)
        CoverThemeCache.put(cover, null)
        assertTrue(CoverThemeCache.contains(cover))
        assertNull(CoverThemeCache[cover])
    }

    @Test
    fun `background color completion reaches an already open screen`() = runBlocking {
        val cover = MangaCover(104, 1, false, "async.jpg", 1)
        val update = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(1000) { CoverThemeCache.observe(cover).first { it == 42 } }
        }
        assertTrue(CoverThemeCache.begin(cover))
        assertFalse(CoverThemeCache.begin(cover.copy(isMangaFavorite = true)))
        CoverThemeCache.put(cover, 42)
        CoverThemeCache.finish(cover)
        assertEquals(42, update.await())
        assertFalse(CoverThemeCache.begin(cover))
    }

    @Test
    fun `failed background extraction can be retried`() {
        val cover = MangaCover(105, 1, false, "retry.jpg", 1)
        assertTrue(CoverThemeCache.begin(cover))
        CoverThemeCache.finish(cover)
        assertTrue(CoverThemeCache.begin(cover))
        CoverThemeCache.finish(cover)
    }

    @Test
    fun `old seeds are evicted to bound memory`() {
        val cover = MangaCover(103, 1, false, "old.jpg", 1)
        CoverThemeCache.put(cover, 1)
        repeat(256) { index ->
            CoverThemeCache.put(cover.copy(mangaId = 1000L + index), index)
        }
        assertFalse(CoverThemeCache.contains(cover))
        assertEquals(255, CoverThemeCache[cover.copy(mangaId = 1255)])
    }
}
