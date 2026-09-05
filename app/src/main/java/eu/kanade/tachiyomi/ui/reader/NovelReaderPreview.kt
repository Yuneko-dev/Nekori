package eu.kanade.tachiyomi.ui.reader

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.quote.Quote
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.LocalNovelSource

/** A transient chapter fed to the ordinary reader; never inserted into the library. */
internal class NovelReaderPreview(private val readAsset: () -> String) : PageLoader() {
    val manga = Manga.create().copy(
        id = ID,
        source = LocalNovelSource.ID,
        title = "Lorem ipsum",
        url = "nekori-reader-preview",
        isNovel = true,
        initialized = true,
    )
    val chapter = ReaderChapter(
        Chapter.create().copy(
            id = ID,
            mangaId = ID,
            name = "Lorem ipsum dolor sit amet consectetuer adipiscing elit",
            url = "https://tsundoku.reader/",
            chapterNumber = 1.0,
        ),
    )
    val quotes = mutableListOf<Quote>()
    var editedText: String? = null
    override var isLocal = true

    override suspend fun getPages(): List<ReaderPage> = listOf(
        ReaderPage(index = 0, url = chapter.chapter.url).also {
            it.chapter = chapter
            loadPage(it)
        },
    )

    override suspend fun loadPage(page: ReaderPage) {
        page.text = editedText ?: readAsset()
        page.status = Page.State.Ready
    }

    suspend fun load() {
        chapter.pageLoader = this
        chapter.state = ReaderChapter.State.Loaded(getPages())
    }

    companion object {
        const val EXTRA = "novel_reader_preview"
        const val ASSET = "novel-reader/dummy.html"
        const val ID = -2L
    }
}
