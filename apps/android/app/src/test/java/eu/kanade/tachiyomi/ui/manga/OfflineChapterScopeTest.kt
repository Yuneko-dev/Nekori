package eu.kanade.tachiyomi.ui.manga

import eu.kanade.domain.manga.model.downloadedFilter
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.Source
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

class OfflineChapterScopeTest {
    @BeforeEach
    fun setUp() {
        mockkStatic("eu.kanade.domain.manga.model.MangaKt")
        every { any<Manga>().downloadedFilter } returns TriState.DISABLED
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic("eu.kanade.domain.manga.model.MangaKt")
    }

    private fun state(flags: Long = 0L, source: Long = 20L) = MangaViewModel.State.Success(
        manga = Manga.create().copy(source = source, chapterFlags = flags),
        source = mockk<Source>(),
        isFromSource = false,
        chapters = listOf(
            item(1, downloaded = true, read = true, bookmark = false),
            item(2, downloaded = true, read = false, bookmark = true),
            item(3, downloaded = false, read = false, bookmark = true),
        ),
        availableScanlators = emptySet(),
        excludedScanlators = emptySet(),
        isNovel = true,
    )

    private fun item(id: Long, downloaded: Boolean, read: Boolean, bookmark: Boolean) = ChapterList.Item(
        chapter = Chapter.create().copy(id = id, read = read, bookmark = bookmark, sourceOrder = id),
        downloadState = if (downloaded) Download.State.DOWNLOADED else Download.State.NOT_DOWNLOADED,
        downloadProgress = 0,
    )

    @Test
    fun `no filters includes all downloaded chapters but excludes remote content`() {
        assertEquals(setOf(1L, 2L), state().readableChapters.map { it.id }.toSet())
    }

    @Test
    fun `unread and bookmark filters also scope offline tools`() {
        val scope = state(Manga.CHAPTER_SHOW_UNREAD or Manga.CHAPTER_SHOW_BOOKMARKED)
        assertEquals(listOf(2L), scope.readableChapters.map { it.id })
        assertEquals(2, scope.processedChapters.size)
    }

    @Test
    fun `filter leaving only remote chapters hides both tools`() {
        val original = state()
        val onlyRemote = original.copy(chapters = original.chapters.filter { !it.isDownloaded })
        assertEquals(emptyList<ChapterList.Item>(), onlyRemote.readableChapters)
    }

    @Test
    fun `local chapters remain readable without a download flag`() {
        assertEquals(3, state(source = 1L).readableChapters.size)
    }

    @Test
    fun `page picker does not restrict tools when chapter filters are disabled`() {
        assertEquals(setOf(1L, 2L), state().copy(selectedSection = "2").readableChapters.map { it.id }.toSet())
    }
}
