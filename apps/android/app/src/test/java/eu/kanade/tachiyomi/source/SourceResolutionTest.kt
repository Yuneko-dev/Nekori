package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.download.service.RateLimitCandidate
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager

/**
 * Sources come from JavaScript plugins here, so registration lags process start by seconds. Callers
 * that run in that window must be able to tell "not ready yet" from "not installed".
 */
class SourceResolutionTest {

    private class FakeSourceManager : SourceManager {
        private val _isInitialized = MutableStateFlow(false)
        override val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

        fun markInitialized() {
            _isInitialized.value = true
        }

        override val sources: Flow<List<Source>> = flowOf(emptyList())
        override fun get(sourceKey: Long): Source? = null
        override fun getOrStub(sourceKey: Long): Source = throw UnsupportedOperationException()
        override fun getAll(): List<Source> = emptyList()
        override fun getOnlineSources(): List<HttpSource> = emptyList()
        override fun getRateLimitCandidates(): List<RateLimitCandidate> = emptyList()
        override fun getStubSources(): List<StubSource> = emptyList()
    }

    @Test
    fun `awaitInitialized returns true once registration completes`() = runTest {
        val sourceManager = FakeSourceManager()
        launch {
            delay(1_000)
            sourceManager.markInitialized()
        }

        assertTrue(sourceManager.awaitInitialized(timeoutMillis = 30_000))
    }

    @Test
    fun `awaitInitialized reports failure instead of hanging when registration never completes`() = runTest {
        assertFalse(FakeSourceManager().awaitInitialized(timeoutMillis = 30_000))
    }
}
