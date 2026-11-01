@file:Suppress("ktlint:standard:filename")

package eu.kanade.tachiyomi.ui.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.discord.SensitiveContentPolicy
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.ui.reader.loader.ChapterLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.NovelDownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.novel.repository.NovelStructureRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.translation.service.TranslationPreferences

internal class ReaderViewModelTestFixture(
    scheduler: TestCoroutineScheduler,
    initialPosition: Float? = null,
    restoredState: Map<String, Any?> = emptyMap(),
) : AutoCloseable {
    val preferences = InMemoryPreferenceStore()
    val savedState = SavedStateHandle(
        mapOf("manga" to -1L, "chapter" to 1L, "chapter_id" to 1L) + restoredState,
    ).apply {
        if (initialPosition != null) set(ReaderActivity.INITIAL_POSITION, initialPosition)
    }
    val sourceManager = mockk<SourceManager>(relaxed = true)
    val downloads = mockk<DownloadManager>(relaxed = true)
    val chapters = mockk<GetChaptersByMangaId>()
    val updateChapter = mockk<UpdateChapter>(relaxed = true)
    val novelStructure = mockk<NovelStructureRepository>()
    val policy = mockk<SensitiveContentPolicy>()
    val source = mockk<CatalogueSource>(relaxed = true)
    val downloadProvider = mockk<DownloadProvider>(relaxed = true)
    val loader: ChapterLoader
    val viewer = mockk<Viewer>(relaxed = true)
    val domainChapter = Chapter.create().copy(id = 1, mangaId = 2, name = "One", url = "/one")
    val chapter = ReaderChapter(domainChapter)
    val manga = Manga.create().copy(id = 2, source = 3, title = "Novel", url = "/novel", isNovel = true)
    private val store = ViewModelStore()
    val model: ReaderViewModel

    init {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        every { downloads.statusFlow() } returns emptyFlow()
        every { downloads.getQueuedDownloadOrNull(any()) } returns null
        every { sourceManager.get(any()) } returns null
        every { policy.isBlocked(any(), any()) } returns false
        coEvery { chapters.await(any(), any()) } returns listOf(domainChapter)
        coEvery { novelStructure.get(any()) } returns null
        val context = mockk<android.content.Context>(relaxed = true)
        every { source.isNovelSource } returns true
        loader = ChapterLoader(context, downloads, downloadProvider, manga, source, mockk(), sourceManager)
        model = ReaderViewModel(
            savedState = savedState,
            sourceManager = sourceManager,
            downloadManager = downloads,
            downloadProvider = downloadProvider,
            readerPreferences = ReaderPreferences(preferences),
            basePreferences = BasePreferences(context, preferences),
            novelDownloadPreferences = NovelDownloadPreferences(preferences),
            trackPreferences = TrackPreferences(preferences),
            trackChapter = mockk(relaxed = true),
            sourceTrackerDispatcher = mockk(relaxed = true),
            getManga = mockk(relaxed = true),
            getChaptersByMangaId = chapters,
            getNextChapters = mockk(relaxed = true),
            upsertHistory = mockk(relaxed = true),
            readingSessionRepository = mockk(relaxed = true),
            updateChapter = updateChapter,
            setMangaViewerFlags = mockk(relaxed = true),
            libraryPreferences = LibraryPreferences(preferences),
            translationPreferences = TranslationPreferences(preferences),
            translationService = mockk(relaxed = true),
            getLibraryManga = mockk(relaxed = true),
            novelStructureRepository = novelStructure,
            syncChaptersWithSource = mockk(relaxed = true),
            sensitiveContentPolicy = policy,
        )
        store.put("reader", model)
    }

    fun installChapter(withLoader: Boolean = true) {
        val current = ViewerChapters(chapter, null, null).also { it.ref() }
        state.value = state.value.copy(manga = manga, viewerChapters = current, viewer = viewer)
        if (withLoader) setField("loader", loader)
    }

    @Suppress("UNCHECKED_CAST")
    private val state: MutableStateFlow<ReaderViewModel.State>
        get() = field("mutableState").get(model) as MutableStateFlow<ReaderViewModel.State>

    private fun field(name: String) = ReaderViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun setField(name: String, value: Any?) = field(name).set(model, value)

    suspend fun <T> awaitIo(value: CompletableDeferred<T>): T = withContext(Dispatchers.Default) {
        withTimeout(10_000) { value.await() }
    }

    override fun close() {
        store.clear()
        Dispatchers.resetMain()
    }
}
