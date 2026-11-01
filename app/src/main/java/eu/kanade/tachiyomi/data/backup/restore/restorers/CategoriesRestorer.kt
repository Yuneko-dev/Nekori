package eu.kanade.tachiyomi.data.backup.restore.restorers

import app.cash.sqldelight.async.coroutines.awaitAsOne
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import tachiyomi.data.Database
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.service.LibraryPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class CategoriesRestorer(
    private val database: Database = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
) {

    suspend operator fun invoke(backupCategories: List<BackupCategory>) {
        if (backupCategories.isNotEmpty()) {
            val dbCategories = getCategories.await()
            val dbCategoriesByName = dbCategories.groupBy { it.name }.toMutableMap()
            var nextOrder = dbCategories.maxOfOrNull { it.order }?.plus(1) ?: 0

            val categories = backupCategories
                .sortedBy { it.order }
                .map {
                    dbCategoriesByName[it.name].orEmpty().restoreMatch(it.contentType)?.let { existing ->
                        return@map existing
                    }
                    val order = nextOrder++
                    val category = database.transactionWithResult {
                        database.categoriesQueries.insert(it.name, order, it.flags, it.contentType.toLong())
                        database.categoriesQueries.selectLastInsertedRowId()
                            .awaitAsOne()
                            .let { id -> it.toCategory(id).copy(order = order) }
                    }
                    // Later backup orders with this same raw name must see the row just inserted.
                    dbCategoriesByName[it.name] = dbCategoriesByName[it.name].orEmpty() + category
                    category
                }

            libraryPreferences.categorizedDisplaySettings.set(
                (dbCategories + categories)
                    .distinctBy { it.flags }
                    .size > 1,
            )
        }
    }
}

internal fun List<Category>.restoreMatch(contentType: Int): Category? =
    firstOrNull { it.contentType == contentType } ?: firstOrNull { it.contentType == Category.CONTENT_TYPE_ALL }
