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
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NovelImageCacheLimitsTest {
    @TempDir lateinit var directory: File
    private val reference = "tsundoku-novel-image://cover.jpg"

    private fun loader(fetch: suspend (String) -> InputStream?): PageLoader = object : PageLoader() {
        override var isLocal = true
        override suspend fun getPages(): List<ReaderPage> = emptyList()
        override suspend fun getPageDataStream(url: String) = fetch(url)
    }

    @Test
    fun `queued prefetches never exceed three active source streams`() = runBlocking {
        val owner = SupervisorJob()
        val scope = CoroutineScope(owner + Dispatchers.IO)
        val cache = NovelWebViewImageCache(directory, scope)
        val entered = CountDownLatch(3)
        val release = CountDownLatch(1)
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val calls = AtomicInteger()
        val source = loader { path ->
            calls.incrementAndGet()
            val current = active.incrementAndGet()
            maximum.accumulateAndGet(current, ::maxOf)
            entered.countDown()
            object : ByteArrayInputStream(path.toByteArray()) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    check(release.await(10, TimeUnit.SECONDS)) { "Prefetch streams were not released" }
                    return super.read(buffer, offset, length)
                }
                override fun close() {
                    active.decrementAndGet()
                    super.close()
                }
            }
        }
        try {
            val html = (1..9).joinToString("") { "<img src=\"tsundoku-novel-image://image$it.jpg\">" }
            cache.schedulePrefetch(html, 1, source)
            assertTrue(entered.await(10, TimeUnit.SECONDS), "Three transfers must start before release")
            assertEquals(3, active.get(), "Other transfers must remain queued while three streams are blocked")
            assertEquals(3, calls.get())
            release.countDown()
            withTimeout(10_000) { owner.children.toList().joinAll() }
            assertEquals(9, calls.get())
            assertEquals(3, maximum.get())
            assertEquals(0, active.get())
            assertEquals(9, directory.listFiles().orEmpty().count { it.extension == "jpg" })
        } finally {
            release.countDown()
            scope.cancel()
            withTimeout(10_000) { owner.children.toList().joinAll() }
            cache.clear()
        }
    }

    @Test
    fun `a partial copy failure cannot poison a later foreground retry`() = runBlocking {
        for (prefetch in listOf(false, true)) {
            val owner = SupervisorJob()
            val scope = CoroutineScope(owner + Dispatchers.IO)
            val cache = NovelWebViewImageCache(directory, scope)
            val calls = AtomicInteger()
            val source = loader {
                if (calls.incrementAndGet() == 1) {
                    object : InputStream() {
                        private var prefixWritten = false
                        override fun read(): Int = throw IOException("Broken test source")
                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                            if (prefixWritten) throw IOException("Broken test source after prefix")
                            prefixWritten = true
                            buffer[offset] = 7
                            return 1
                        }
                    }
                } else {
                    "COMPLETE".byteInputStream()
                }
            }
            try {
                if (prefetch) {
                    cache.schedulePrefetch("<img src=\"$reference\">", 1, source)
                    withTimeout(10_000) { owner.children.toList().joinAll() }
                } else {
                    assertNull(cache.intercept(reference, 1, source))
                }
                assertTrue(
                    directory.listFiles().orEmpty().isEmpty(),
                    "Failed copy must leave no completed or temporary file",
                )
                assertNotNull(cache.intercept(reference, 1, source))
                assertNotNull(cache.intercept(reference, 1, source))
                assertEquals(2, calls.get(), "Only the failed attempt and clean retry may read the source")
                assertArrayEquals("COMPLETE".toByteArray(), directory.listFiles().orEmpty().single().readBytes())
            } finally {
                scope.cancel()
                withTimeout(10_000) { owner.children.toList().joinAll() }
                cache.clear()
            }
        }
    }

    @Test
    fun `clear never removes an unrelated temporary file`() {
        val unrelated = File(directory, "another-feature.tmp").apply { writeText("KEEP") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val cache = NovelWebViewImageCache(directory, scope)
        try {
            assertNotNull(cache.intercept(reference, 1, loader { "IMAGE".byteInputStream() }))
            cache.clear()
            assertEquals(listOf(unrelated), directory.listFiles().orEmpty().toList())
            assertEquals("KEEP", unrelated.readText())
        } finally {
            scope.cancel()
            cache.clear()
        }
    }

    @Test
    fun `a missing foreground stream releases its claim for a later prefetch`() = runBlocking {
        val owner = SupervisorJob()
        val scope = CoroutineScope(owner + Dispatchers.IO)
        val cache = NovelWebViewImageCache(directory, scope)
        val calls = AtomicInteger()
        try {
            assertNull(cache.intercept(reference, 1, loader { null }))
            cache.schedulePrefetch(
                "<img src=\"$reference\">",
                1,
                loader {
                    calls.incrementAndGet()
                    "RETRIED".byteInputStream()
                },
            )
            withTimeout(10_000) { owner.children.toList().joinAll() }
            assertEquals(1, calls.get(), "A missing stream must not leave an owner that blocks prefetch")
            assertNotNull(cache.intercept(reference, 1, loader { error("Expected cached retry") }))
            assertArrayEquals("RETRIED".toByteArray(), directory.listFiles().orEmpty().single().readBytes())
        } finally {
            scope.cancel()
            withTimeout(10_000) { owner.children.toList().joinAll() }
            cache.clear()
        }
    }
}
