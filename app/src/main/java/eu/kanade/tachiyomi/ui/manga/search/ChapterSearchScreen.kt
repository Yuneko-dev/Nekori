package eu.kanade.tachiyomi.ui.manga.search

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.google.re2j.PatternSyntaxException
import eu.kanade.presentation.manga.ChapterSearchScreenContent
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.download.ChapterContentReader
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.awaitSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import logcat.LogPriority
import mihon.core.viewmodel.StateViewModel
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.isLocal
import tachiyomi.source.local.isLocalNovel
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

/**
 * Searches the text of every downloaded chapter (or every chapter of an imported entry) of one entry.
 */
class ChapterSearchScreen(
    private val mangaId: Long,
    private val chapterIds: List<Long>,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current

        val viewModel = viewModel<Model>(
            factory = Model.Factory,
            extras = CreationExtras {
                set(Model.MANGA_ID_KEY, mangaId)
                set(Model.CHAPTER_IDS_KEY, chapterIds)
            },
        )
        val state by viewModel.state.collectAsStateWithLifecycle()

        ChapterSearchScreenContent(
            state = state,
            navigateUp = navigator::pop,
            onQueryChange = viewModel::updateQuery,
            onSearch = { viewModel.search() },
            onOptionsChange = viewModel::updateOptions,
            onResultClick = { chapter, snippet ->
                context.startActivity(
                    ReaderActivity.newIntent(context, chapter.mangaId, chapter.id, snippet?.position ?: 0f),
                )
            },
        )
    }

    class Model(
        private val mangaId: Long,
        chapterIds: List<Long>,
        private val getManga: GetManga = Injekt.get(),
        private val getChaptersByMangaId: GetChaptersByMangaId = Injekt.get(),
        private val sourceManager: SourceManager = Injekt.get(),
        private val downloadManager: DownloadManager = Injekt.get(),
        private val contentReader: ChapterContentReader = ChapterContentReader(
            Injekt.get<Application>(),
            Injekt.get<DownloadProvider>(),
        ),
    ) : StateViewModel<State>(State()) {

        private var searchJob: Job? = null
        private val searchGeneration = AtomicLong()
        private var source: Source? = null
        private val chapterIds = chapterIds.toSet()

        init {
            viewModelScope.launchIO {
                val manga = getManga.await(mangaId) ?: run {
                    mutableState.update { it.copy(chapters = emptyList()) }
                    return@launchIO
                }
                val source = (sourceManager.awaitSource(manga.source) ?: sourceManager.getOrStub(manga.source))
                    .also { source = it }
                val readsLocally = source.isLocal() || manga.isLocalNovel()
                if (!readsLocally) downloadManager.awaitDownloadCacheReady()
                val chapters = getChaptersByMangaId.await(mangaId, applyScanlatorFilter = true)
                    .filter { it.id in chapterIds }
                    .sortedWith(getChapterSort(manga, sortDescending = false))
                    .filter { chapter ->
                        readsLocally || downloadManager.isChapterDownloaded(
                            chapter.name,
                            chapter.scanlator,
                            chapter.url,
                            manga.title,
                            manga.source,
                        )
                    }
                mutableState.update { it.copy(manga = manga, chapters = chapters) }
                withUIContext { if (state.value.pendingSearch) search() }
            }
        }

        fun updateQuery(query: String) {
            mutableState.update { it.copy(query = query, regexError = null) }
            if (query.isBlank()) search()
        }

        fun updateOptions(options: ChapterSearchOptions) {
            mutableState.update { it.copy(options = options, regexError = null) }
            if (state.value.submittedQuery != null) search()
        }

        fun search() {
            searchJob?.cancel()
            val generation = searchGeneration.incrementAndGet()
            val current = state.value
            val query = current.query
            if (query.isBlank()) {
                mutableState.update {
                    it.copy(
                        isSearching = false,
                        pendingSearch = false,
                        submittedQuery = null,
                        searchedCount = 0,
                        failedCount = 0,
                        totalMatches = 0,
                        results = persistentListOf(),
                    )
                }
                return
            }

            val regex = try {
                ChapterTextSearch.buildRegex(query, current.options)
            } catch (e: PatternSyntaxException) {
                mutableState.update { it.copy(regexError = e.description, isSearching = false, pendingSearch = false) }
                return
            }

            val manga = current.manga
            val chapters = current.chapters
            val source = source
            if (manga == null || chapters == null || source == null) {
                // Entry still loading; run once it is ready
                mutableState.update { it.copy(pendingSearch = true) }
                return
            }

            mutableState.update {
                it.copy(
                    submittedQuery = query,
                    pendingSearch = false,
                    isSearching = true,
                    searchedCount = 0,
                    failedCount = 0,
                    totalMatches = 0,
                    results = persistentListOf(),
                )
            }

            searchJob = viewModelScope.launchIO {
                val context = currentCoroutineContext()
                val readsLocally = source.isLocal() || manga.isLocalNovel()
                val chapterFiles = if (readsLocally) {
                    emptyMap()
                } else {
                    contentReader.findChapterFiles(
                        manga,
                        chapters,
                        source,
                    )
                }
                chapters.forEachIndexed { index, chapter ->
                    context.ensureActive()
                    val matches = try {
                        val content = if (readsLocally) {
                            source.fetchPageText(Page(0, chapter.url))
                        } else {
                            chapterFiles[chapter.id]?.let(contentReader::readDownloadedContent)
                        }
                        content?.let {
                            ChapterTextSearch.findMatches(
                                text = ChapterTextSearch.toPlainText(content),
                                regex = regex,
                                isActive = { context.isActive },
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logcat(LogPriority.ERROR, e) { "Chapter search failed to read ${chapter.name}" }
                        null
                    }
                    mutableState.update { state ->
                        if (searchGeneration.get() != generation) return@update state
                        state.copy(
                            searchedCount = index + 1,
                            failedCount = state.failedCount + if (matches == null) 1 else 0,
                            totalMatches = state.totalMatches + (matches?.count ?: 0),
                            results = if (matches != null && matches.count > 0) {
                                state.results.adding(ChapterSearchResult(chapter, matches.snippets))
                            } else {
                                state.results
                            },
                        )
                    }
                }
                mutableState.update {
                    if (searchGeneration.get() != generation) it else it.copy(isSearching = false)
                }
            }
        }

        companion object {
            val MANGA_ID_KEY = CreationExtras.Key<Long>()
            val CHAPTER_IDS_KEY = CreationExtras.Key<List<Long>>()

            val Factory = viewModelFactory {
                initializer {
                    Model(mangaId = get(MANGA_ID_KEY)!!, chapterIds = get(CHAPTER_IDS_KEY)!!)
                }
            }
        }
    }

    @Immutable
    data class State(
        val manga: Manga? = null,
        /** Chapters whose text can be searched, in reading order; null while loading. */
        val chapters: List<Chapter>? = null,
        val query: String = "",
        val options: ChapterSearchOptions = ChapterSearchOptions(),
        val submittedQuery: String? = null,
        val pendingSearch: Boolean = false,
        val regexError: String? = null,
        val isSearching: Boolean = false,
        val searchedCount: Int = 0,
        val failedCount: Int = 0,
        val totalMatches: Int = 0,
        val results: PersistentList<ChapterSearchResult> = persistentListOf(),
    )
}

@Immutable
data class ChapterSearchResult(
    val chapter: Chapter,
    val snippets: List<ChapterSearchSnippet>,
) {
    val matchCount: Int get() = snippets.size
}
