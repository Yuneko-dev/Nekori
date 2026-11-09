package eu.kanade.tachiyomi.ui.manga.search

import androidx.lifecycle.viewModelScope
import eu.kanade.tachiyomi.source.Source
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager

class ChapterSearchScreenTest {
    private val getManga = mockk<GetManga>()
    private val getChapters = mockk<GetChaptersByMangaId>()
    private val sources = mockk<SourceManager>()
    private val source = mockk<Source>()
    private var model: ChapterSearchScreen.Model? = null

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { source.id } returns 1L
        every { sources.get(1L) } returns source
        coEvery { getManga.await(10L) } returns Manga.create().copy(id = 10L, source = 1L)
        coEvery { getChapters.await(10L, applyScanlatorFilter = true) } returns listOf(
            Chapter.create().copy(id = 20L, mangaId = 10L),
        )
    }

    @AfterEach
    fun tearDown() {
        model?.viewModelScope?.cancel()
        Dispatchers.resetMain()
    }

    private fun createModel() = ChapterSearchScreen.Model(
        mangaId = 10L,
        chapterIds = listOf(20L),
        getManga = getManga,
        getChaptersByMangaId = getChapters,
        sourceManager = sources,
        downloadManager = mockk(),
        contentReader = mockk(),
    ).also { model = it }

    @Test
    fun `clearing the toolbar cancels the active read and resets results`() = runBlocking {
        val reading = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        coEvery { source.fetchPageText(any()) } coAnswers {
            reading.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
        val model = createModel()
        withTimeout(5_000) { model.state.first { it.chapters != null } }
        model.updateQuery("needle")
        model.search()
        withTimeout(5_000) { reading.await() }
        model.updateQuery("")
        withTimeout(5_000) { cancelled.await() }
        assertFalse(model.state.value.isSearching)
        assertNull(model.state.value.submittedQuery)
        assertTrue(model.state.value.results.isEmpty())
    }

    @Test
    fun `clearing a pending search does not start it after initialization`() = runBlocking {
        val manga = CompletableDeferred<Manga>()
        coEvery { getManga.await(10L) } coAnswers { manga.await() }
        val model = createModel()
        model.updateQuery("needle")
        model.search()
        assertTrue(model.state.value.pendingSearch)
        model.updateQuery("   ")
        manga.complete(Manga.create().copy(id = 10L, source = 1L))
        withTimeout(5_000) { model.state.first { it.chapters != null } }
        assertFalse(model.state.value.pendingSearch)
        assertFalse(model.state.value.isSearching)
        coVerify(exactly = 0) { source.fetchPageText(any()) }
    }
}
