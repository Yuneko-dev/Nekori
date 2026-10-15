package eu.kanade.tachiyomi.data.coil

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.domain.manga.model.MangaCover

internal fun isUnavailableCover(url: String?): Boolean {
    val path = url?.toHttpUrlOrNull()?.encodedPath ?: return false
    return path.endsWith("/static/coverNotAvailable.webp")
}

/** Process-local seeds, keyed by cover revision so replacing a cover cannot reuse its old color. */
internal object CoverThemeCache {
    private val changes = MutableStateFlow(0L)
    private val pending = mutableSetOf<MangaCover>()

    fun observe(cover: MangaCover) = changes.map { get(cover) }.distinctUntilChanged()

    @Synchronized
    fun begin(cover: MangaCover): Boolean = !contains(cover) && pending.add(cover.copy(isMangaFavorite = false))

    @Synchronized
    fun finish(cover: MangaCover) {
        pending.remove(cover.copy(isMangaFavorite = false))
    }

    private val colors = object : LinkedHashMap<MangaCover, Int?>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MangaCover, Int?>) = size > 256
    }

    @Synchronized
    operator fun get(cover: MangaCover): Int? = colors[cover.copy(isMangaFavorite = false)]

    @Synchronized
    fun contains(cover: MangaCover): Boolean = colors.containsKey(cover.copy(isMangaFavorite = false))

    @Synchronized
    fun put(cover: MangaCover, color: Int?) {
        colors[cover.copy(isMangaFavorite = false)] = color
        changes.value += 1
    }
}
