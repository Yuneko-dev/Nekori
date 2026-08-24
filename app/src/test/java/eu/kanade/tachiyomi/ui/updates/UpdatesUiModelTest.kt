package eu.kanade.tachiyomi.ui.updates

import eu.kanade.presentation.updates.UpdatesUiModel
import eu.kanade.tachiyomi.data.download.model.Download
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.updates.model.UpdatesWithRelations

class UpdatesUiModelTest {

    @Test
    fun `date list groups repeated novels within each date only`() {
        val newerDate = LocalDate(2026, 8, 24)
        val olderDate = LocalDate(2026, 8, 23)
        val state = UpdatesViewModel.State(
            items = listOf(
                item(mangaId = 1, chapterId = 11, date = newerDate),
                item(mangaId = 2, chapterId = 21, date = newerDate),
                item(mangaId = 1, chapterId = 12, date = newerDate),
                item(mangaId = 1, chapterId = 13, date = olderDate),
                item(mangaId = 1, chapterId = 14, date = olderDate),
            ),
        )

        val models = state.getUiModel()

        assertEquals(UpdatesUiModel.Header(newerDate), models[0])
        val newerGroup = models[1] as UpdatesUiModel.Group
        assertEquals(UpdatesUiModel.GroupKey(newerDate, 1), newerGroup.key)
        assertEquals(listOf(11L, 12L), newerGroup.items.map { it.update.chapterId })
        assertEquals(21L, (models[2] as UpdatesUiModel.Item).item.update.chapterId)
        assertEquals(UpdatesUiModel.Header(olderDate), models[3])
        val olderGroup = models[4] as UpdatesUiModel.Group
        assertEquals(UpdatesUiModel.GroupKey(olderDate, 1), olderGroup.key)
        assertEquals(listOf(13L, 14L), olderGroup.items.map { it.update.chapterId })
    }

    @Test
    fun `date group exposes unread and complete selection state`() {
        val date = LocalDate(2026, 8, 24)
        val mixedGroup = UpdatesViewModel.State(
            items = listOf(
                item(mangaId = 1, chapterId = 11, date = date, read = true, selected = true),
                item(mangaId = 1, chapterId = 12, date = date),
            ),
        ).getUiModel().filterIsInstance<UpdatesUiModel.Group>().single()
        assertTrue(mixedGroup.hasUnread)
        assertFalse(mixedGroup.allSelected)

        val completedGroup = UpdatesViewModel.State(
            items = listOf(
                item(mangaId = 1, chapterId = 11, date = date, read = true, selected = true),
                item(mangaId = 1, chapterId = 12, date = date, read = true, selected = true),
            ),
        ).getUiModel().filterIsInstance<UpdatesUiModel.Group>().single()

        assertFalse(completedGroup.hasUnread)
        assertTrue(completedGroup.allSelected)
    }

    private fun item(
        mangaId: Long,
        chapterId: Long,
        date: LocalDate,
        read: Boolean = false,
        selected: Boolean = false,
    ): UpdatesItem {
        val sourceId = 10L
        return UpdatesItem(
            update = UpdatesWithRelations(
                mangaId = mangaId,
                mangaTitle = "Novel $mangaId",
                chapterId = chapterId,
                chapterName = "Chapter $chapterId",
                scanlator = null,
                chapterUrl = "/chapter-$chapterId",
                read = read,
                bookmark = false,
                lastPageRead = 0,
                sourceId = sourceId,
                dateFetch = LocalDateTime(date, LocalTime(12, 0))
                    .toInstant(TimeZone.currentSystemDefault())
                    .toEpochMilliseconds(),
                coverData = MangaCover(
                    mangaId = mangaId,
                    sourceId = sourceId,
                    isMangaFavorite = true,
                    url = null,
                    lastModified = 0,
                ),
            ),
            downloadStateProvider = { Download.State.NOT_DOWNLOADED },
            downloadProgressProvider = { 0 },
            selected = selected,
            isNovel = true,
        )
    }
}
