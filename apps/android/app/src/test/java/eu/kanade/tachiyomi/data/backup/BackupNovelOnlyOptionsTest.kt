package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BackupNovelOnlyOptionsTest {
    @Test
    fun `create worker compatibility slots always select only novels`() {
        val values = BackupOptions().asBooleanArray()

        assertFalse(values[1])
        assertTrue(values[2])
    }

    @Test
    fun `restore worker compatibility slots always select only novels`() {
        val values = RestoreOptions().asBooleanArray()

        assertFalse(values[5])
        assertTrue(values[6])
    }
}
