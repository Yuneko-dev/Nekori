@file:Suppress("ktlint:standard:filename")

package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NovelImageCacheRaceTest {
    @TempDir lateinit var directory: File
    private val reference = "tsundoku-novel-image://cover.jpg"

    private fun loader(fetch: suspend () -> InputStream): PageLoader = object : PageLoader() {
        override var isLocal = true
        override suspend fun getPages(): List<ReaderPage> = emptyList()
        override suspend fun getPageDataStream(url: String) = fetch()
    }

    private fun blockedStream(entered: CountDownLatch, release: CountDownLatch): InputStream =
        object : ByteArrayInputStream("OLD".toByteArray()) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "Test stream was not released" }
                return super.read(buffer, offset, length)
            }
        }

    @Test
    fun `canceled prefetch cannot overwrite or delete a foreground result`() = runBlocking {
        assertSuccessorSurvives(clear = false)
    }

    @Test
    fun `clear prevents an old blocking writer from publishing into a new cache generation`() = runBlocking {
        assertSuccessorSurvives(clear = true)
    }

    private suspend fun assertSuccessorSurvives(clear: Boolean) {
        val owner = SupervisorJob()
        val scope = CoroutineScope(owner + Dispatchers.IO)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val source = loader {
            if (calls.incrementAndGet() == 1) blockedStream(entered, release) else "NEW".byteInputStream()
        }
        val cache = NovelWebViewImageCache(directory, scope)
        try {
            cache.schedulePrefetch("""<img src="$reference">""", 1, source)
            assertTrue(entered.await(10, TimeUnit.SECONDS), "Prefetch must enter the blocking copy")
            if (clear) cache.clear()
            assertNotNull(cache.intercept(reference, 1, source))
            release.countDown()
            owner.children.toList().joinAll()
            assertNotNull(cache.intercept(reference, 1, source))
            assertEquals(2, calls.get(), "Successor must remain cached after old writer exits")
            val complete = directory.listFiles().orEmpty().filter { it.extension == "jpg" }
            assertEquals(1, complete.size)
            assertArrayEquals("NEW".toByteArray(), complete.single().readBytes())
            assertTrue(directory.listFiles().orEmpty().none { it.extension == "tmp" })
        } finally {
            release.countDown()
            scope.cancel()
            owner.children.toList().joinAll()
            cache.clear()
        }
    }

    @Test
    fun `clearing one cache instance leaves another instances published image intact`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val first = NovelWebViewImageCache(directory, scope)
        val second = NovelWebViewImageCache(directory, scope)
        try {
            first.intercept(reference, 1, loader { "FIRST".byteInputStream() })
            second.intercept(reference, 1, loader { "SECOND".byteInputStream() })
            first.clear()
            val calls = AtomicInteger()
            assertNotNull(
                second.intercept(
                    reference,
                    1,
                    loader {
                        calls.incrementAndGet()
                        "REFETCH".byteInputStream()
                    },
                ),
            )
            assertEquals(0, calls.get(), "Instance cleanup must not erase another reader's cache file")
        } finally {
            first.clear()
            second.clear()
            scope.cancel()
        }
    }
}
