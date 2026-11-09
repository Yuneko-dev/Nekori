package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.jsplugin.source.JsSource
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import logcat.LogPriority
import logcat.logcat
import tachiyomi.core.common.util.lang.withIOContext

/**
 * Loader used to load pages for a local novel source.
 */
class LocalNovelPageLoader(
    private val chapter: ReaderChapter,
    private val source: Source,
    private val forceRefresh: Boolean = false,
) : PageLoader() {

    override var isLocal: Boolean = true

    override suspend fun getPages(): List<ReaderPage> {
        val source = this.source
        val chapter = this.chapter

        // For HttpSource novels, the "page list" is just a single stub page.
        // Calling source.getPageList() would trigger an unnecessary HTTP request.
        val pages = when {
            source is HttpSource && source.isNovelSource -> {
                listOf(Page(0, chapter.chapter.url))
            }
            else -> (source as? CatalogueSource)?.getPageList(chapter.chapter)
                ?: listOf(Page(0, chapter.chapter.url))
        }

        return pages.mapIndexed { index, page ->
            ReaderPage(index, page.url, page.imageUrl).also { readerPage ->
                // If the source already populated page.text (e.g. JsSource.getPageList fetches
                // and stores the chapter HTML), copy it so loadPage() can skip a redundant fetch.
                if (!page.text.isNullOrBlank()) {
                    readerPage.text = page.text
                    readerPage.status = Page.State.Ready
                }
            }
        }
    }
    override suspend fun getPageDataStream(url: String): java.io.InputStream? {
        val sChapter = chapter.chapter
        return (source as? tachiyomi.source.local.LocalNovelSource)?.getChapterImage(sChapter, url)
    }

    override suspend fun loadPage(page: ReaderPage) = withIOContext {
        while (true) {
            when (val status = page.status) {
                Page.State.Ready -> return@withIOContext
                Page.State.LoadPage -> {
                    // Only a cancelled owner releases its claim back to the queue.
                    if (page.statusFlow.first { it != Page.State.LoadPage } != Page.State.Queue) return@withIOContext
                }
                else -> if (page.tryClaim(status, Page.State.LoadPage)) break
            }
        }
        try {
            currentCoroutineContext().ensureActive()
            if (source.isNovelSource) {
                // A reload from source must outlive the plugin's chapter-text cache, or it replays
                // the same HTML for as long as that entry stays fresh.
                if (forceRefresh) (source as? JsSource)?.invalidateChapterText(page.url)
                val content = source.fetchChapterContent(page)
                // A source may swallow cancellation or turn it into an ordinary I/O failure.
                currentCoroutineContext().ensureActive()
                page.chapterContent = content
                page.status = Page.State.Ready
            } else {
                throw IllegalStateException("Source is not a NovelSource")
            }
        } catch (e: Throwable) {
            if (currentCoroutineContext().isActive) {
                logcat(LogPriority.ERROR) {
                    "LocalNovelPageLoader: Failed to load page for ${chapter.chapter.name}: ${e.javaClass.name}: ${e.message}\n${e.stackTraceToString()}"
                }
            }
            onPageLoadFailed(page, e)
        }
    }
}
