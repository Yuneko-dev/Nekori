@file:Suppress("ktlint:standard:filename")

package tachiyomi.data.manga

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tachiyomi.core.common.preference.TriState
import tachiyomi.data.Database
import tachiyomi.data.MangasQueries
import tachiyomi.domain.library.model.LibraryPageSpec
import tachiyomi.domain.manga.model.applyFilter

class LibraryIntervalMappingTest {
    @ParameterizedTest
    @ValueSource(longs = [-7, 0, 7])
    fun `library query mappings preserve stored intervals`(interval: Long) = runTest {
        val repository = repository(interval)
        val results = mapOf(
            "resident" to repository.getLibraryManga().single(),
            "paged" to repository.getLibraryMangaPage(0, true, 10, 0, LibraryPageSpec()).single(),
            "by id" to requireNotNull(repository.getLibraryMangaById(1)),
            "by ids" to repository.getLibraryMangaByIds(listOf(1)).single(),
            "flow" to repository.getLibraryMangaAsFlow().first().single(),
        )
        results.forEach { (path, model) ->
            assertEquals(interval.toInt(), model.manga.fetchInterval, path)
            assertEquals(interval < 0, applyFilter(TriState.ENABLED_IS) { model.manga.fetchInterval < 0 }, path)
            assertEquals(interval >= 0, applyFilter(TriState.ENABLED_NOT) { model.manga.fetchInterval < 0 }, path)
            assertEquals(true, applyFilter(TriState.DISABLED) { model.manga.fetchInterval < 0 }, path)
        }
    }

    private fun repository(interval: Long): MangaRepositoryImpl {
        // Execute the generated query mapper and repository callback; only the SQL cursor boundary is supplied.
        val driver = object : SqlDriver by mockk<SqlDriver>(relaxed = true) {
            override fun <R> executeQuery(
                identifier: Int?,
                sql: String,
                mapper: (SqlCursor) -> QueryResult<R>,
                parameters: Int,
                binders: (SqlPreparedStatement.() -> Unit)?,
            ): QueryResult<R> = mapper(object : SqlCursor {
                private var first = true
                override fun next() = QueryResult.Value(first.also { first = false })
                override fun getLong(index: Int): Long = when (index) {
                    0, 1 -> 1L
                    20 -> interval
                    else -> 0L
                }
                override fun getString(index: Int): String? = when (index) {
                    2 -> "/novel"
                    6 -> null
                    7 -> "Novel"
                    33 -> "0"
                    else -> ""
                }
                override fun getBoolean(index: Int) = true
                override fun getDouble(index: Int) = 0.0
                override fun getBytes(index: Int): ByteArray? = null
            })
        }
        val queries = MangasQueries(driver, mockk())
        return MangaRepositoryImpl(mockk<Database> { every { mangasQueries } returns queries })
    }
}
