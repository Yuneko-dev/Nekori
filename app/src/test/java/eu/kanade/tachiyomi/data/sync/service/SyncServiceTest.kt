package eu.kanade.tachiyomi.data.sync.service

import android.content.Context
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupNovelStructure
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Characterizes PR 437 / Komikku policy, including its local/remote asymmetries. */
class SyncServiceTest {
    private val preferences = mockk<SyncPreferences> { every { uniqueDeviceID() } returns "device" }
    private val service = object : SyncService(mockk<Context>(), Json, preferences) {
        override suspend fun doSync(syncData: SyncData): Backup? = syncData.backup
        fun merge(local: Backup, remote: Backup) = requireNotNull(
            mergeSyncData(SyncData(backup = local), SyncData(backup = remote)).backup,
        )
    }

    private fun novel(version: Long = 1) = BackupManga(42, "//book#part", "Novel", version = version, isNovel = true)

    @Test
    fun `higher version wins and ties keep local`() {
        val local = novel().apply { favorite = true }
        val remote = novel(2).apply { favorite = false }
        assertSame(remote, service.merge(Backup(listOf(local)), Backup(listOf(remote))).backupManga.single())
        assertSame(local, service.merge(Backup(listOf(local)), Backup(listOf(novel()))).backupManga.single())
    }

    @Test
    fun `original composite identity includes title and author without changing stored paths`() {
        val local = novel()
        val renamed = novel().apply { title = "Renamed" }
        val result = service.merge(Backup(listOf(local)), Backup(listOf(renamed)))
        assertEquals(2, result.backupManga.size)
        assertTrue(result.backupManga.all { it.url == "//book#part" })
    }

    @Test
    fun `chapter winner and remote source order follow original version rule`() {
        val local = novel().apply {
            chapters = listOf(BackupChapter("c", "C", version = 5, read = false, sourceOrder = 1))
        }
        val remote = novel().apply {
            chapters = listOf(
                BackupChapter("c", "C", version = 4, read = true, sourceOrder = 2),
                BackupChapter("d", "D"),
            )
        }
        val chapters = service.merge(Backup(listOf(local)), Backup(listOf(remote))).backupManga.single().chapters
        assertEquals(2, chapters.size)
        assertFalse(chapters.first().read)
        assertEquals(2L, chapters.first().sourceOrder)
    }

    @Test
    fun `renamed chapters remain separate as in PR composite key`() {
        val local = novel().apply { chapters = listOf(BackupChapter("c", "Old")) }
        val remote = novel().apply { chapters = listOf(BackupChapter("c", "New")) }
        assertEquals(2, service.merge(Backup(listOf(local)), Backup(listOf(remote))).backupManga.single().chapters.size)
    }

    @Test
    fun `category order is preserved rather than globally renumbered`() {
        val local = Backup(listOf(novel().apply { categories = listOf(3) }), listOf(BackupCategory("A", 3)))
        val remote = Backup(emptyList(), listOf(BackupCategory("A", 9), BackupCategory("B", 9)))
        val result = service.merge(local, remote)
        assertEquals(listOf(9L, 9L), result.backupCategories.map { it.order })
        assertEquals(listOf(9L), result.backupManga.single().categories)
    }

    @Test
    fun `common sources and app preferences follow original omission policy`() {
        fun backup() = Backup(
            emptyList(),
            backupSources = listOf(BackupSource("Source", 42, isJs = true)),
            backupPreferences = listOf(BackupPreference("theme", StringPreferenceValue("dark"))),
        )
        val result = service.merge(backup(), backup())
        assertTrue(result.backupSources.isEmpty())
        assertTrue(result.backupPreferences.isEmpty())
    }

    @Test
    fun `source preference collisions keep remote value`() {
        fun backup(value: String) = Backup(
            emptyList(),
            backupSourcePreferences = listOf(
                BackupSourcePreferences("source_42", listOf(BackupPreference("setting", StringPreferenceValue(value)))),
            ),
        )
        val result = service.merge(backup("local"), backup("remote"))
        assertEquals(StringPreferenceValue("remote"), result.backupSourcePreferences.single().prefs.single().value)
    }

    @Test
    fun `winning novel keeps native structure and history without extra merging`() {
        val local = novel().apply { history = listOf(BackupHistory("c", 99, 99)) }
        val remote = novel(2).apply {
            history = listOf(BackupHistory("c", 1, 1))
            novelStructure = BackupNovelStructure(0, 1, emptyList())
        }
        val result = service.merge(Backup(listOf(local)), Backup(listOf(remote))).backupManga.single()
        assertEquals(1L, result.history.single().lastRead)
        assertSame(remote.novelStructure, result.novelStructure)
    }

    @Test
    fun `Nekori boundary excludes manga`() {
        val result = service.merge(Backup(listOf(novel())), Backup(listOf(BackupManga(1, "manga"))))
        assertTrue(result.backupManga.all { it.isNovel })
        assertEquals(1, result.backupManga.size)
    }
}
