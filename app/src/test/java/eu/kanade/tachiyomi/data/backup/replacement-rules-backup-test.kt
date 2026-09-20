@file:Suppress("ktlint:standard:filename")

package eu.kanade.tachiyomi.data.backup

import android.util.Log
import eu.kanade.tachiyomi.data.backup.create.creators.PreferenceBackupCreator
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.restore.restorers.PreferenceRestorer
import eu.kanade.tachiyomi.ui.reader.setting.RegexReplacement
import eu.kanade.tachiyomi.ui.reader.setting.ReplacementTarget
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class ReplacementRulesBackupTest {
    @Test
    fun `app preference backup restores novel scope without database IDs`() = runTest {
        val before = InMemoryPreferenceStore()
        val rule = RegexReplacement(
            "Names",
            "old",
            "new",
            scope = RegexReplacement.NOVEL,
            target = ReplacementTarget(42, "/novel//one#part"),
        )
        before.getString(RegexReplacement.PREFERENCE_KEY).set(Json.encodeToString(listOf(rule)))
        val backup = PreferenceBackupCreator(mockk(), before).createApp(false)
        val after = InMemoryPreferenceStore()
        PreferenceRestorer(mockk(), mockk(), after).restorePreferences(backup, after)
        assertEquals(listOf(rule), RegexReplacement.decode(after.getString(RegexReplacement.PREFERENCE_KEY).get()))
    }

    @Test
    fun `legacy preference restore stays global`() = runTest {
        val after = InMemoryPreferenceStore()
        val legacy = """[{"title":"Old","pattern":"x","replacement":"y","enabled":false}]"""
        val backup = listOf(BackupPreference(RegexReplacement.PREFERENCE_KEY, StringPreferenceValue(legacy)))
        PreferenceRestorer(mockk(), mockk(), after).restorePreferences(backup, after)
        val restored = RegexReplacement.decode(after.getString(RegexReplacement.PREFERENCE_KEY).get()).single()
        assertEquals(RegexReplacement.GLOBAL, restored.scope)
        assertEquals(false, restored.enabled)
    }

    @Test
    fun `malformed backup does not overwrite existing rules`() = runTest {
        mockkStatic(Log::class)
        try {
            every { Log.e(any(), any(), any()) } returns 0
            val store = InMemoryPreferenceStore()
            val existing = Json.encodeToString(listOf(RegexReplacement("Keep", "a", "b")))
            store.getString(RegexReplacement.PREFERENCE_KEY).set(existing)
            val backup = listOf(BackupPreference(RegexReplacement.PREFERENCE_KEY, StringPreferenceValue("broken")))
            PreferenceRestorer(mockk(), mockk(), store).restorePreferences(backup, store)
            assertEquals(existing, store.getString(RegexReplacement.PREFERENCE_KEY).get())
        } finally {
            unmockkStatic(Log::class)
        }
    }
}
