package eu.kanade.presentation.manga.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate

class TranslatedMangaDetailsTest {
    private val manga = Manga.create().copy(
        id = 42,
        title = "Original title",
        description = "Original description",
        genre = listOf("Original genre"),
        notes = "Translated Description:\nOld translation\n\nPersonal note: keep this",
    )

    @Test
    fun `saving translation preserves description and personal notes`() {
        val update = TranslatedMangaDetails(translatedDescription = "New translation").toMangaUpdate(manga)

        assertNull(update.description)
        assertEquals("${manga.notes}\n\nTranslated Description:\nNew translation", update.notes)
    }

    @Test
    fun `saving tags to notes does not require changing genres`() {
        val update = TranslatedMangaDetails(
            translatedGenres = listOf("Translated genre"),
            saveGenres = false,
            saveTagsToNotes = true,
        ).toMangaUpdate(manga)

        assertNull(update.genre)
        assertEquals("${manga.notes}\n\nTranslated Tags: Translated genre", update.notes)
    }

    @Test
    fun `all save options disabled leave metadata unchanged`() {
        val update = TranslatedMangaDetails(
            translatedTitle = "Translated title",
            translatedDescription = "Translated description",
            translatedGenres = listOf("Translated genre"),
            addToAltTitles = false,
            saveDescriptionToNotes = false,
            saveGenres = false,
            saveTagsToNotes = false,
        ).toMangaUpdate(manga)

        assertEquals(MangaUpdate(id = manga.id), update)
    }

    @Test
    fun `genre merge and replacement respect selection`() {
        val details = TranslatedMangaDetails(translatedGenres = listOf("Translated genre", "Original genre"))
        assertEquals(listOf("Original genre", "Translated genre"), details.toMangaUpdate(manga).genre)
        assertEquals(details.translatedGenres, details.copy(mergeGenres = false).toMangaUpdate(manga).genre)
    }

    @Test
    fun `repeated translation does not duplicate notes or alternative titles`() {
        val details = TranslatedMangaDetails(
            translatedTitle = "Translated title",
            translatedDescription = "Translated description",
            translatedGenres = listOf("Translated genre"),
            saveTagsToNotes = true,
        )
        val first = details.toMangaUpdate(manga)
        val saved = manga.copy(notes = first.notes!!, alternativeTitles = first.alternativeTitles!!)
        val second = details.toMangaUpdate(saved)

        assertNull(first.title)
        assertEquals(listOf("Translated title"), first.alternativeTitles)
        assertNull(second.notes)
        assertNull(second.alternativeTitles)
    }

    @Test
    fun `blank translation cannot erase existing metadata`() {
        val update = TranslatedMangaDetails(
            translatedTitle = " ",
            translatedDescription = " ",
            translatedGenres = emptyList(),
            mergeGenres = false,
            saveTagsToNotes = true,
        ).toMangaUpdate(manga)

        assertEquals(MangaUpdate(id = manga.id), update)
    }

    @Test
    fun `tag notes preserve user text including legacy headings`() {
        val original = "My notes\nTranslated Tags: old tags and my annotation\n\nKeep everything here"
        val update = TranslatedMangaDetails(
            translatedGenres = listOf("New tag"),
            saveTagsToNotes = true,
            saveGenres = false,
        ).toMangaUpdate(manga.copy(notes = original))

        assertEquals("$original\n\nTranslated Tags: New tag", update.notes)
    }

    @Test
    fun `genre save and note save work independently in every combination`() {
        for (saveGenres in listOf(false, true)) {
            for (saveNotes in listOf(false, true)) {
                val update = TranslatedMangaDetails(
                    translatedGenres = listOf("New tag"),
                    saveTagsToNotes = saveNotes,
                    saveGenres = saveGenres,
                ).toMangaUpdate(manga.copy(notes = ""))

                assertEquals(if (saveGenres) listOf("Original genre", "New tag") else null, update.genre)
                assertEquals(if (saveNotes) "Translated Tags: New tag" else null, update.notes)
            }
        }
    }
}
