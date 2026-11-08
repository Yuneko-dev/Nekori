package mihon.core.migration.migrations

import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.withIOContext

/**
 * Re-keys [eu.kanade.tachiyomi.data.track.TrackerManager] tracker ids so they line up with
 * upstream mihon, keeping track backups interchangeable between the two apps.
 *
 * NovelUpdates/NovelList/RanobeDb are exclusive to tsundoku and previously sat at ids 10-12,
 * which pushed MangaBaka/Hikka to 13/14 instead of upstream's 11/10. This moves the exclusive
 * trackers into the 100+ range first (so they don't collide with 10/11 on the way in), then
 * moves MangaBaka/Hikka back onto upstream's ids.
 *
 * The corresponding `manga_sync.sync_id` rows are handled by the 33.sqm database migration;
 * this migration only carries forward the per-tracker credential/token preferences, which are
 * keyed by id and would otherwise be orphaned under the old id and silently log the user out.
 */
class TrackerIdRekeyMigration : Migration {
    override val version: Float = 23f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withIOContext {
        val preferenceStore = migrationContext.get<PreferenceStore>() ?: return@withIOContext false

        val idRemaps = listOf(
            10L to 100L, // NovelUpdates
            11L to 101L, // NovelList
            12L to 102L, // RanobeDb
            13L to 11L, // MangaBaka
            14L to 10L, // Hikka
        )

        for ((oldId, newId) in idRemaps) {
            moveStringPreference(preferenceStore, "pref_mangasync_username_$oldId", "pref_mangasync_username_$newId")
            moveStringPreference(
                preferenceStore,
                "pref_mangasync_displayname_$oldId",
                "pref_mangasync_displayname_$newId",
            )
            moveStringPreference(preferenceStore, "pref_mangasync_password_$oldId", "pref_mangasync_password_$newId")
            moveStringPreference(preferenceStore, "track_token_$oldId", "track_token_$newId")
            moveBooleanPreference(
                preferenceStore,
                "pref_tracker_auth_expired_$oldId",
                "pref_tracker_auth_expired_$newId",
            )
        }

        return@withIOContext true
    }

    private fun moveStringPreference(preferenceStore: PreferenceStore, oldKey: String, newKey: String) {
        val old = preferenceStore.getString(Preference.privateKey(oldKey), "")
        if (!old.isSet()) return
        preferenceStore.getString(Preference.privateKey(newKey), "").set(old.get())
        old.delete()
    }

    private fun moveBooleanPreference(preferenceStore: PreferenceStore, oldKey: String, newKey: String) {
        val old = preferenceStore.getBoolean(Preference.privateKey(oldKey), false)
        if (!old.isSet()) return
        preferenceStore.getBoolean(Preference.privateKey(newKey), false).set(old.get())
        old.delete()
    }
}
