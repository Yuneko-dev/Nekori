@file:Suppress("ktlint:standard:filename")

package eu.kanade.tachiyomi.data.backup.restore.restorers

import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.category.model.Category

class CategoryRestoreSqlTest {
    private fun incoming(name: String, order: Long, type: Int = Category.CONTENT_TYPE_NOVEL) =
        BackupCategory(name = name, order = order, flags = 2, contentType = type)

    @Test
    fun `a novel category reuses legacy All with local flags and order intact`() = runBlocking {
        CategoryRestoreSqlFixture.create().use { fixture ->
            val local = fixture.seedCategory("Favorites", Category.CONTENT_TYPE_ALL, order = 42, flags = 987)
            val definitions = listOf(incoming("Favorites", 100))
            fixture.categoryRestorer(definitions)
            val novelId = fixture.restoreNovel("raw//one#part", listOf(100), definitions)
            assertEquals(listOf(local), fixture.userCategories())
            assertEquals(listOf(novelId to local.id), fixture.pairs())
        }
    }

    @Test
    fun `an exact Novel category takes priority over earlier legacy All`() = runBlocking {
        CategoryRestoreSqlFixture.create().use { fixture ->
            val legacy = fixture.seedCategory("Shelf", Category.CONTENT_TYPE_ALL, order = 2)
            val exact = fixture.seedCategory("Shelf", Category.CONTENT_TYPE_NOVEL, order = 12, flags = 521)
            val definitions = listOf(incoming("Shelf", 9))
            fixture.categoryRestorer(definitions)
            val novelId = fixture.restoreNovel("/exact", listOf(9), definitions)
            assertEquals(listOf(legacy, exact), fixture.userCategories())
            assertEquals(listOf(novelId to exact.id), fixture.pairs())
        }
    }

    @Test
    fun `duplicate backup orders converge on one category and one pair per novel across restores`() = runBlocking {
        CategoryRestoreSqlFixture.create().use { fixture ->
            val definitions = listOf(incoming("Sách", 10), incoming("Sách", 20))
            fixture.categoryRestorer(definitions)
            val first = fixture.restoreNovel("/one", listOf(10, 20, 10), definitions)
            fixture.categoryRestorer(definitions)
            assertEquals(first, fixture.restoreNovel("/one", listOf(20, 10), definitions))
            val second = fixture.restoreNovel("/two", listOf(20), definitions)
            val canonical = fixture.userCategories().single()
            assertNotEquals(first, second)
            assertEquals(listOf(first to canonical.id, second to canonical.id), fixture.pairs())
        }
    }

    @Test
    fun `raw whitespace case and Unicode spellings stay separate`() = runBlocking {
        CategoryRestoreSqlFixture.create().use { fixture ->
            val names = listOf("Books", "books", " Books ", "Sách", "Sa\u0301ch")
            val definitions = names.mapIndexed { index, name -> incoming(name, index.toLong() + 1) }
            fixture.categoryRestorer(definitions)
            fixture.categoryRestorer(definitions)
            val novelId = fixture.restoreNovel("/raw", definitions.map { it.order }, definitions)
            val restored = fixture.userCategories()
            assertEquals(names, restored.map { it.name })
            assertEquals(5, restored.map { it.id }.distinct().size)
            assertEquals(restored.map { novelId to it.id }, fixture.pairs())
        }
    }

    @Test
    fun `a same-name Manga category is not a legacy fallback for a novel`() = runBlocking {
        CategoryRestoreSqlFixture.create().use { fixture ->
            val mangaOnly = fixture.seedCategory("Shelf", Category.CONTENT_TYPE_MANGA)
            val definitions = listOf(incoming("Shelf", 1))
            fixture.categoryRestorer(definitions)
            val novel = fixture.userCategories().single { it.contentType == Category.CONTENT_TYPE_NOVEL }
            val novelId = fixture.restoreNovel("/novel", listOf(1), definitions)
            assertNotEquals(mangaOnly.id, novel.id)
            assertEquals(listOf(novelId to novel.id), fixture.pairs())
        }
    }

    @Test
    fun `an incoming All category cannot reuse a same-name typed category`() = runBlocking {
        CategoryRestoreSqlFixture.create().use { fixture ->
            val typed = fixture.seedCategory("Shared", Category.CONTENT_TYPE_NOVEL)
            val definitions = listOf(incoming("Shared", 1, Category.CONTENT_TYPE_ALL))
            fixture.categoryRestorer(definitions)
            val legacy = fixture.userCategories().single { it.contentType == Category.CONTENT_TYPE_ALL }
            val novelId = fixture.restoreNovel("/all", listOf(1), definitions)
            assertNotEquals(typed.id, legacy.id)
            assertEquals(listOf(novelId to legacy.id), fixture.pairs())
        }
    }

    @Test
    fun `membership replacement affects only the restored novel and preserves local category metadata`() = runBlocking {
        CategoryRestoreSqlFixture.create().use { fixture ->
            val retained = fixture.seedCategory("Unrelated", Category.CONTENT_TYPE_ALL, order = 11, flags = 37)
            val target = fixture.seedCategory("Target", Category.CONTENT_TYPE_ALL, order = 22, flags = 49)
            val unrelated = fixture.restoreNovel("/untouched", emptyList(), emptyList())
            val restored = fixture.restoreNovel("/restored", emptyList(), emptyList())
            fixture.database.mangas_categoriesQueries.insert(unrelated, retained.id)
            fixture.database.mangas_categoriesQueries.insert(restored, retained.id)
            val definitions = listOf(incoming("Target", 200))
            fixture.categoryRestorer(definitions)
            assertEquals(restored, fixture.restoreNovel("/restored", listOf(200, 200), definitions))
            assertEquals(listOf(retained, target), fixture.userCategories())
            assertEquals(listOf(unrelated to retained.id, restored to target.id), fixture.pairs())
        }
    }
}
