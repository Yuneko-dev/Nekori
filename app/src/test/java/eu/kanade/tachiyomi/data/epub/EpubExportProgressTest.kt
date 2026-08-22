package eu.kanade.tachiyomi.data.epub

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EpubExportProgressTest {

    @Test
    fun `throttles intermediate updates but keeps first boundary and forced updates`() {
        assertTrue(EpubExportJob.shouldNotifyEpubProgress(now = 1, lastNotifyAt = 0, force = false))
        assertFalse(EpubExportJob.shouldNotifyEpubProgress(now = 1_499, lastNotifyAt = 1_000, force = false))
        assertTrue(EpubExportJob.shouldNotifyEpubProgress(now = 1_500, lastNotifyAt = 1_000, force = false))
        assertTrue(EpubExportJob.shouldNotifyEpubProgress(now = 1_001, lastNotifyAt = 1_000, force = true))
    }
}
