package eu.kanade.presentation.manga.components

import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate

data class TranslatedMangaDetails(
    val translatedTitle: String? = null,
    val translatedDescription: String? = null,
    val translatedGenres: List<String>? = null,
    val addToAltTitles: Boolean = true,
    val saveTagsToNotes: Boolean = false,
    val mergeGenres: Boolean = true,
    val saveGenres: Boolean = true,
    val saveDescriptionToNotes: Boolean = true,
) {
    fun toMangaUpdate(manga: Manga): MangaUpdate {
        var notes = manga.notes
        val noteBlocks = buildList {
            if (saveDescriptionToNotes && !translatedDescription.isNullOrBlank()) {
                add("Translated Description:\n$translatedDescription")
            }
            if (saveTagsToNotes && !translatedGenres.isNullOrEmpty()) {
                add("Translated Tags: ${translatedGenres.joinToString(", ")}")
            }
        }
        // Notes are user-editable: legacy headings cannot safely delimit a replaceable block.
        for (block in noteBlocks) {
            if (block !in notes) notes = if (notes.isEmpty()) block else "$notes\n\n$block"
        }
        val alternativeTitles = translatedTitle?.takeIf {
            addToAltTitles && it.isNotBlank() && it != manga.title && it !in manga.alternativeTitles
        }?.let { listOf(it) + manga.alternativeTitles }
        val genres = translatedGenres?.takeIf { saveGenres && it.isNotEmpty() }?.let {
            if (mergeGenres) (manga.genre.orEmpty() + it).distinct() else it
        }
        return MangaUpdate(
            id = manga.id,
            alternativeTitles = alternativeTitles,
            genre = genres,
            notes = notes.takeIf { it != manga.notes },
        )
    }
}
