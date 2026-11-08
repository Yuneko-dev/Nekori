package eu.kanade.tachiyomi.extension

import android.content.Context
import eu.kanade.domain.extension.interactor.TrustExtension
import eu.kanade.domain.source.service.SourcePreferences
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExtensionManagerTest {

    @Test
    fun `initialization does not query installed packages`() {
        val context = mockk<Context>(relaxed = true)

        val manager = ExtensionManager(
            context = context,
            preferences = mockk<SourcePreferences>(relaxed = true),
            trustExtension = mockk<TrustExtension>(relaxed = true),
        )

        assertTrue(manager.isInitialized.value)
        verify(exactly = 0) { context.packageManager }
    }
}
