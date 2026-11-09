package eu.kanade.tachiyomi.data.download

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import java.io.File

class DownloadCacheTest {

    /**
     * Guards the fix for the cold start that always re-indexed. Subscribing to the source list makes
     * startup look like a source change - the list emits empty, then again once the JS plugins have
     * loaded - and every emission wiped the persisted index and rescanned the whole downloads tree.
     * Sources settling is not a change: [DownloadCache.renewCache] already waits for them before it
     * builds a source map.
     */
    @Test
    fun `does not subscribe to the source list`(@TempDir tempDir: File) {
        val storageManager = mockk<StorageManager>(relaxed = true) {
            every { changes } returns MutableSharedFlow()
            every { getDownloadsDirectory() } returns null
        }
        val sourceManager = mockk<SourceManager>(relaxed = true)

        DownloadCache(
            context = mockk<Context>(relaxed = true) { every { cacheDir } returns tempDir },
            provider = mockk(relaxed = true),
            sourceManager = sourceManager,
            storageManager = storageManager,
        )

        // The listener was registered from init's coroutines, so give them a chance to run before
        // concluding they did not.
        Thread.sleep(SETTLE_MILLIS)

        verify(exactly = 0) { sourceManager.sources }
        // One call from the rootDownloadsDir field initializer. A second would mean a scan started
        // on construction, without anything having asked for a download count.
        verify(exactly = 1) { storageManager.getDownloadsDirectory() }
    }

    private companion object {
        const val SETTLE_MILLIS = 300L
    }
}
