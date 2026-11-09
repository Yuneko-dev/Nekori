package mihon.domain.migration

import tachiyomi.domain.manga.model.Manga

/**
 * Pairs each selectable manga with its plugin path, dropping the ones already favorited on
 * the target source. [existingFavoriteUrls] is the one-shot set of target-source favorite urls, so
 * duplicate detection is in-memory instead of one query per manga.
 *
 * The path is a source identity and is carried across verbatim: upstream normalizes the leading
 * slash to the target source's convention here, which would migrate an entry onto a key that does
 * not match what the plugin returns.
 */
fun quickMigrateTargets(
    selected: List<Manga>,
    existingFavoriteUrls: Set<String>,
): List<Pair<Manga, String>> =
    selected.mapNotNull { manga ->
        if (manga.url in existingFavoriteUrls) null else manga to manga.url
    }

/** The other half of [quickMigrateTargets]: the entries the target source already has. */
fun quickMigrateSkipped(
    selected: List<Manga>,
    existingFavoriteUrls: Set<String>,
): List<Manga> = selected.filter { it.url in existingFavoriteUrls }
