@file:Suppress("ktlint:standard:filename")

package eu.kanade.domain.chapter.interactor

import eu.kanade.domain.download.interactor.DeleteDownload
import eu.kanade.tachiyomi.data.download.DownloadManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.download.service.NovelDownloadPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.translation.model.TranslationLocator
import tachiyomi.domain.translation.repository.TranslatedChapterRepository
import tachiyomi.domain.translation.service.TranslationPreferences

class SetReadStatusTest {
    @ParameterizedTest
    @CsvSource(
        "true, false, false",
        "true, false, true",
        "true, true, false",
        "true, true, true",
        "false, true, true",
    )
    fun `manual read status respects independent deletion settings`(
        read: Boolean,
        removeTranslations: Boolean,
        removeDownloads: Boolean,
    ) = runTest {
        val store = InMemoryPreferenceStore()
        val translationPrefs = TranslationPreferences(store)
        translationPrefs.removeAfterMarkedAsRead().set(removeTranslations)
        val downloadPrefs = NovelDownloadPreferences(store)
        downloadPrefs.removeAfterMarkedAsRead.set(removeDownloads)

        val manga = Manga.create().copy(id = 1, source = 2, title = "Novel")
        val chapter = Chapter.create().copy(id = 3, mangaId = manga.id, name = "Chapter 1", url = "/1", read = !read)
        val source = StubSource(id = manga.source, lang = "en", name = "Source", isNovelSource = true)
        val sourceManager = mockk<SourceManager> {
            every { get(manga.source) } returns source
            every { getOrStub(manga.source) } returns source
        }
        val mangaRepository = mockk<MangaRepository> {
            coEvery { getMangaById(manga.id) } returns manga
            coEvery { getMangaByIdOrNull(manga.id) } returns manga
        }
        val chapterRepository = mockk<ChapterRepository>(relaxUnitFun = true)
        val translations = mockk<TranslatedChapterRepository>(relaxUnitFun = true)
        val downloadManager = mockk<DownloadManager>(relaxUnitFun = true)
        val setReadStatus = SetReadStatus(
            downloadPrefs,
            translationPrefs,
            DeleteDownload(sourceManager, downloadManager),
            mangaRepository,
            chapterRepository,
            translations,
            sourceManager,
        )

        assertEquals(SetReadStatus.Result.Success, setReadStatus.await(read, chapter))

        val update = ChapterUpdate(id = chapter.id, read = read, lastPageRead = if (read) null else 0)
        coVerify(exactly = 1) {
            chapterRepository.updateAll(listOf(update))
        }
        val locator = TranslationLocator(source.toString(), manga.title, chapter.name, chapter.url)
        coVerify(exactly = if (read && removeTranslations) 1 else 0) {
            translations.deleteAllForChapter(locator)
        }
        verify(exactly = if (read && removeDownloads) 1 else 0) {
            downloadManager.deleteChapters(listOf(chapter), manga, source)
        }
    }
}
