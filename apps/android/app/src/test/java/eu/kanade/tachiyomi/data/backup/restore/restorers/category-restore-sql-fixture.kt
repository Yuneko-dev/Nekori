@file:Suppress("ktlint:standard:filename")

package eu.kanade.tachiyomi.data.backup.restore.restorers

import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import io.mockk.coEvery
import io.mockk.mockk
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.data.AlternativeTitlesColumnAdapter
import tachiyomi.data.Chapters
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.category.CategoryRepositoryImpl
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.domain.track.interactor.GetTracks

internal class CategoryRestoreSqlFixture private constructor(
    private val driver: JdbcSqliteDriver,
    val database: Database,
) : AutoCloseable {
    val categories = CategoryRepositoryImpl(database)
    val getCategories = GetCategories(categories)
    val categoryRestorer = CategoriesRestorer(database, getCategories, LibraryPreferences(InMemoryPreferenceStore()))
    private val chapters = GetChaptersByMangaId(ChapterRepositoryImpl(database))
    private val tracks = mockk<GetTracks>().apply { coEvery { await(any()) } returns emptyList() }
    private val mangaRestorer = MangaRestorer(
        database = database,
        getCategories = getCategories,
        getMangaByUrlAndSourceId = GetMangaByUrlAndSourceId(MangaRepositoryImpl(database)),
        getChaptersByMangaId = chapters,
        updateManga = mockk(relaxed = true),
        getTracks = tracks,
        insertTrack = mockk(relaxed = true),
        novelStructureRepository = mockk(relaxed = true),
        fetchInterval = FetchInterval(chapters),
    )

    suspend fun seedCategory(name: String, type: Int, order: Long = 7, flags: Long = 39): Category {
        database.categoriesQueries.insert(name, order, flags, type.toLong())
        return categories.get(database.categoriesQueries.selectLastInsertedRowId().awaitAsOne())!!
    }

    suspend fun restoreNovel(path: String, orders: List<Long>, definitions: List<BackupCategory>): Long =
        mangaRestorer.restore(
            BackupManga(source = 91, url = path, title = "Novel $path", isNovel = true, categories = orders),
            definitions,
        )

    suspend fun userCategories() = getCategories.await().filter { it.id != 0L }

    suspend fun pairs() = categories.getAllMangaCategoryPairs().sortedWith(compareBy({ it.first }, { it.second }))

    override fun close() = driver.close()

    companion object {
        suspend fun create(): CategoryRestoreSqlFixture {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            try {
                Database.Schema.create(driver).await()
                driver.execute(null, "PRAGMA foreign_keys = ON", 0).await()
                return CategoryRestoreSqlFixture(
                    driver,
                    Database(
                        driver,
                        Chapters.Adapter(MemoColumnAdapter),
                        History.Adapter(DateColumnAdapter),
                        Mangas.Adapter(
                            genreAdapter = StringListColumnAdapter,
                            update_strategyAdapter = UpdateStrategyColumnAdapter,
                            alternative_titlesAdapter = AlternativeTitlesColumnAdapter,
                            memoAdapter = MemoColumnAdapter,
                        ),
                    ),
                )
            } catch (error: Throwable) {
                driver.close()
                throw error
            }
        }
    }
}
