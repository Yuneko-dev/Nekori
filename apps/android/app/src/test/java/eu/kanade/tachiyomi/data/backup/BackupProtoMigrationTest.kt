package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.models.BackupJsPluginRepository
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupNovelSection
import eu.kanade.tachiyomi.data.backup.models.BackupNovelStructure
import eu.kanade.tachiyomi.data.backup.models.BackupReadingSession
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
/*
TODO: Remove this test after a few releases, when the migration is no longer needed.
 */
class BackupProtoMigrationTest {

    @Serializable
    private class LegacyBackup(
        @ProtoNumber(1) val backupManga: List<LegacyBackupManga> = emptyList(),
        @ProtoNumber(106) val backupExtensionStores: List<LegacyBackupExtensionStore> = emptyList(),
    )

    @Serializable
    private class LegacyBackupManga(
        @ProtoNumber(1) val source: Long,
        @ProtoNumber(2) val url: String,
        @ProtoNumber(3) val title: String = "",
        @ProtoNumber(112) val isNovel: Boolean = false,
    )

    @Serializable
    private class LegacyBackupExtensionStore(
        @ProtoNumber(1) val indexUrl: String,
        @ProtoNumber(2) val name: String,
        @ProtoNumber(5) val signingKey: String,
        @ProtoNumber(4) val contactWebsite: String,
        @ProtoNumber(7) val isLegacy: Boolean?,
        @ProtoNumber(8) val isNovel: Boolean,
    )

    @Test
    fun `legacy isNovel at 112 is remapped to 8000 and decodes`() {
        val legacy = LegacyBackup(
            listOf(
                LegacyBackupManga(source = 7, url = "/novel", title = "T", isNovel = true),
                LegacyBackupManga(source = 8, url = "/manga", title = "M", isNovel = false),
            ),
        )
        val bytes = ProtoBuf.encodeToByteArray(LegacyBackup.serializer(), legacy)

        val migrated = BackupProtoMigration.migrateLegacyIsNovel(bytes)
        val backup = ProtoBuf.decodeFromByteArray(Backup.serializer(), migrated)

        assertEquals(2, backup.backupManga.size)
        assertTrue(backup.backupManga[0].isNovel)
        assertEquals(7L, backup.backupManga[0].source)
        assertEquals("/novel", backup.backupManga[0].url)
        assertEquals("T", backup.backupManga[0].title)
        assertFalse(backup.backupManga[1].isNovel)
        assertEquals("M", backup.backupManga[1].title)
    }

    @Test
    fun `legacy extension store isNovel at 8 is remapped to 8000 and decodes`() {
        val legacy = LegacyBackup(
            backupExtensionStores = listOf(
                LegacyBackupExtensionStore("https://a/index.min.json", "A", "keyA", "https://a", false, true),
                LegacyBackupExtensionStore("https://b/index.min.json", "B", "keyB", "https://b", false, false),
            ),
        )
        val bytes = ProtoBuf.encodeToByteArray(LegacyBackup.serializer(), legacy)

        val migrated = BackupProtoMigration.migrateLegacyIsNovel(bytes)
        val backup = ProtoBuf.decodeFromByteArray(Backup.serializer(), migrated)

        assertEquals(2, backup.backupExtensionStores.size)
        assertTrue(backup.backupExtensionStores[0].isNovel)
        assertEquals("https://a/index.min.json", backup.backupExtensionStores[0].indexUrl)
        assertEquals("A", backup.backupExtensionStores[0].name)
        assertFalse(backup.backupExtensionStores[1].isNovel)
        assertEquals("B", backup.backupExtensionStores[1].name)
    }

    @Test
    fun `current extension store with extensionListUrl at 8 is unchanged`() {
        val backup = Backup(
            backupManga = emptyList(),
            backupExtensionStores = listOf(
                BackupExtensionStore(
                    indexUrl = "https://a/index.min.json",
                    name = "A",
                    badgeLabel = null,
                    signingKey = "key",
                    contactWebsite = "https://a",
                    contactDiscord = null,
                    isLegacy = true,
                    extensionListUrl = "https://a/extensions.min.json",
                    isNovel = true,
                ),
            ),
        )
        val bytes = ProtoBuf.encodeToByteArray(Backup.serializer(), backup)

        val migrated = BackupProtoMigration.migrateLegacyIsNovel(bytes)

        assertEquals(bytes.toList(), migrated.toList())
        val decoded = ProtoBuf.decodeFromByteArray(Backup.serializer(), migrated).backupExtensionStores[0]
        assertTrue(decoded.isNovel)
        assertEquals("https://a/extensions.min.json", decoded.extensionListUrl)
    }

    @Test
    fun `current backup with isNovel at 8000 is unchanged`() {
        val backup = Backup(
            backupManga = listOf(
                BackupManga(source = 1, url = "/x", title = "X", isNovel = true),
            ),
        )
        val bytes = ProtoBuf.encodeToByteArray(Backup.serializer(), backup)

        val migrated = BackupProtoMigration.migrateLegacyIsNovel(bytes)

        assertEquals(bytes.toList(), migrated.toList())
        assertTrue(ProtoBuf.decodeFromByteArray(Backup.serializer(), migrated).backupManga[0].isNovel)
    }

    @Test
    fun `novel structure sessions and JS repositories round trip`() {
        val session = BackupReadingSession(startedAt = 100, endedAt = 200, readDuration = 80)
        val structure = BackupNovelStructure(
            layout = 2,
            totalPages = 12,
            sections = listOf(
                BackupNovelSection(
                    name = "1",
                    pageNumber = 1,
                    path = "/page/1",
                    cover = "https://example.com/1.jpg",
                    chapterUrls = listOf("/chapter/1", "/chapter/2"),
                ),
            ),
        )
        val backup = Backup(
            backupManga = listOf(
                BackupManga(
                    source = 7,
                    url = "/novel",
                    isNovel = true,
                    chapters = listOf(
                        BackupChapter(
                            url = "/chapter/1",
                            name = "Chapter 1",
                            readingSessions = listOf(session),
                        ),
                    ),
                    novelStructure = structure,
                ),
            ),
            backupJsPluginRepositories = listOf(
                BackupJsPluginRepository("LNReader", "https://example.com/plugins.json", enabled = false),
            ),
        )

        val decoded = ProtoBuf.decodeFromByteArray(
            Backup.serializer(),
            ProtoBuf.encodeToByteArray(Backup.serializer(), backup),
        )

        assertEquals(structure, decoded.backupManga.single().novelStructure)
        assertEquals(session, decoded.backupManga.single().chapters.single().readingSessions.single())
        assertEquals(backup.backupJsPluginRepositories, decoded.backupJsPluginRepositories)
    }
}
