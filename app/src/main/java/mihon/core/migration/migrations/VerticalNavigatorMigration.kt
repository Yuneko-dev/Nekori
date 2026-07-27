package mihon.core.migration.migrations

import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.withIOContext

class VerticalNavigatorMigration : Migration {
    override val version: Float = 23f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withIOContext {
        val preferenceStore = migrationContext.get<PreferenceStore>() ?: return@withIOContext false

        val oldVerticalNavigator = preferenceStore.getBoolean("pref_webtoon_vertical_navigator", true)
        if (oldVerticalNavigator.isSet()) {
            oldVerticalNavigator.delete()
        }

        val oldVerticalNavigatorOnLeft = preferenceStore.getBoolean("pref_webtoon_vertical_navigator_on_left", false)
        if (oldVerticalNavigatorOnLeft.isSet()) {
            oldVerticalNavigatorOnLeft.delete()
        }

        return@withIOContext true
    }
}
