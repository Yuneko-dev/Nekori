package eu.kanade.tachiyomi.data.sync.service

import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.sync.SyncManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.PreferenceStore

class GoogleDriveAvailabilityTest {
    @Test
    fun `availability follows build client ID`() {
        assertEquals(BuildConfig.GOOGLE_DRIVE_CLIENT_ID.isNotBlank(), GoogleDriveService.isConfigured)
    }

    @Test
    fun `saved Drive selection requires configuration while WebDAV stays available`() {
        val store = mockk<PreferenceStore>()
        var selected = SyncManager.SyncService.GOOGLE_DRIVE.value
        every { store.getInt("sync_service", 0) } returns mockk {
            every { get() } answers { selected }
        }
        val preferences = SyncPreferences(store)
        mockkObject(GoogleDriveService.Companion)
        try {
            for (configured in listOf(false, true)) {
                every { GoogleDriveService.isConfigured } returns configured
                selected = SyncManager.SyncService.GOOGLE_DRIVE.value
                assertEquals(configured, preferences.isSyncEnabled())
                selected = SyncManager.SyncService.WEB_DAV.value
                assertEquals(true, preferences.isSyncEnabled())
                selected = SyncManager.SyncService.NONE.value
                assertEquals(false, preferences.isSyncEnabled())
            }
        } finally {
            unmockkObject(GoogleDriveService.Companion)
        }
    }
}
