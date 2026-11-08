package eu.kanade.tachiyomi.data.track

import eu.kanade.tachiyomi.data.track.anilist.Anilist
import eu.kanade.tachiyomi.data.track.bangumi.Bangumi
import eu.kanade.tachiyomi.data.track.hikka.Hikka
import eu.kanade.tachiyomi.data.track.kavita.Kavita
import eu.kanade.tachiyomi.data.track.kitsu.Kitsu
import eu.kanade.tachiyomi.data.track.komga.Komga
import eu.kanade.tachiyomi.data.track.mangabaka.MangaBaka
import eu.kanade.tachiyomi.data.track.mangaupdates.MangaUpdates
import eu.kanade.tachiyomi.data.track.myanimelist.MyAnimeList
import eu.kanade.tachiyomi.data.track.novellist.NovelList
import eu.kanade.tachiyomi.data.track.novelupdates.NovelUpdates
import eu.kanade.tachiyomi.data.track.ranobedb.RanobeDb
import eu.kanade.tachiyomi.data.track.shikimori.Shikimori
import eu.kanade.tachiyomi.data.track.suwayomi.Suwayomi
import kotlinx.coroutines.flow.combine

class TrackerManager {

    companion object {
        const val ANILIST = 2L
        const val KITSU = 3L
        const val KAVITA = 8L
        const val MANGABAKA = 11L
        const val NOVELUPDATES = 100L
        const val NOVELLIST = 101L
        const val RANOBEDB = 102L
    }

    val myAnimeList = MyAnimeList(1L)
    val aniList = Anilist(ANILIST)
    val kitsu = Kitsu(KITSU)
    val shikimori = Shikimori(4L)
    val bangumi = Bangumi(5L)
    val komga = Komga(6L)
    val mangaUpdates = MangaUpdates(7L)
    val kavita = Kavita(KAVITA)
    val suwayomi = Suwayomi(9L)
    val hikka = Hikka(10L)
    val mangaBaka = MangaBaka(MANGABAKA)

    // Exclusive to tsundoku: re-keyed into the 100+ range to stay clear of upstream mihon's ids.
    val novelUpdates = NovelUpdates(NOVELUPDATES)
    val novelList = NovelList(NOVELLIST)
    val ranobeDb = RanobeDb(RANOBEDB)

    val trackers = listOf(
        myAnimeList,
        aniList,
        kitsu,
        shikimori,
        bangumi,
        komga,
        mangaUpdates,
        kavita,
        suwayomi,
        novelUpdates,
        novelList,
        ranobeDb,
        mangaBaka,
        hikka,
    )

    /**
     * Trackers that support novel tracking.
     * - NovelUpdates & NovelList & RanobeDB: Novel-specific trackers
     * - MyAnimeList, Anilist, MangaUpdates, MangaBaka: Support both manga and novels
     */
    val novelTrackers = trackers

    /**
     * Trackers that are only for manga (no novel support).
     * These should be hidden when tracking novels.
     */
    val mangaOnlyTrackers = listOf(kitsu, shikimori, bangumi, komga, kavita, suwayomi, hikka)

    /**
     * Trackers that are only for novels.
     * These should be hidden when tracking manga.
     */
    val novelOnlyTrackers = listOf(novelUpdates, novelList, ranobeDb)

    fun loggedInTrackers() = trackers.filter { it.isLoggedIn }

    fun loggedInNovelTrackers() = novelTrackers.filter { it.isLoggedIn }

    fun loggedInMangaTrackers() = trackers.filter { it.isLoggedIn && it !in novelOnlyTrackers }

    fun loggedInTrackersFlow() = combine(trackers.map { it.isLoggedInFlow }) {
        it.mapIndexedNotNull { index, isLoggedIn ->
            if (isLoggedIn) trackers[index] else null
        }
    }

    fun get(id: Long) = trackers.find { it.id == id }

    fun getAll(ids: Set<Long>) = trackers.filter { it.id in ids }
}
