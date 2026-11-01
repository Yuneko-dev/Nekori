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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NovelImageCacheConcurrencyTest {
    @TempDir lateinit var directory: File
    private val reference = "tsundoku-novel-image://cover.jpg"

    private fun loader(fetch: suspend (String) -> InputStream): PageLoader = object : PageLoader() {
        override var isLocal = true
        override suspend fun getPages(): List<ReaderPage> = emptyList()
        override suspend fun getPageDataStream(url: String) = fetch(url)
    }

    @Test
    fun `overlapping foreground requests share one complete source read`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val secondCopy = CountDownLatch(1)
        val calls = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val cache = NovelWebViewImageCache(directory, scope)
        val workers = Executors.newFixedThreadPool(2)
        val secondThread = java.util.concurrent.atomic.AtomicReference<Thread>()
        val source = loader {
            val first = calls.incrementAndGet() == 1
            object : ByteArrayInputStream("COMPLETE".toByteArray()) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (first) entered.countDown()
                    val gate = if (first) release else secondCopy
                    check(gate.await(10, TimeUnit.SECONDS)) { "Foreground stream was not released" }
                    return super.read(buffer, offset, length)
                }
            }
        }
        try {
            val first = workers.submit<Any?> { cache.intercept(reference, 1, source) }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val second = workers.submit<Any?> {
                secondThread.set(Thread.currentThread())
                secondStarted.countDown()
                cache.intercept(reference, 1, source)
            }
            assertTrue(secondStarted.await(10, TimeUnit.SECONDS))
            // Wait for the contender to block at either the per-key lock or source barrier.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (secondThread.get().state !in setOf(Thread.State.WAITING, Thread.State.TIMED_WAITING)) {
                check(System.nanoTime() < deadline) { "Second request never reached its blocking boundary" }
                Thread.onSpinWait()
            }
            release.countDown()
            assertNotNull(first.get(10, TimeUnit.SECONDS), "A concurrent reader must not steal the first response")
            secondCopy.countDown()
            assertNotNull(second.get(10, TimeUnit.SECONDS))
            assertEquals(1, calls.get(), "Same-key readers should reuse the first completed file")
            assertArrayEquals("COMPLETE".toByteArray(), directory.listFiles().orEmpty().single().readBytes())
        } finally {
            release.countDown()
            secondCopy.countDown()
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS))
            scope.cancel()
            cache.clear()
        }
    }

    @Test
    fun `prefetch cannot take ownership from an active foreground copy`() = runBlocking {
        val owner = SupervisorJob()
        val scope = CoroutineScope(owner + Dispatchers.IO)
        val cache = NovelWebViewImageCache(directory, scope)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val releasePrefetch = CountDownLatch(1)
        val workers = Executors.newSingleThreadExecutor()
        val source = loader {
            object : ByteArrayInputStream("FOREGROUND".toByteArray()) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    return super.read(buffer, offset, length)
                }
            }
        }
        try {
            val first = workers.submit<Any?> { cache.intercept(reference, 1, source) }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            cache.schedulePrefetch(
                "<img src=\"$reference\">",
                1,
                loader {
                    check(releasePrefetch.await(10, TimeUnit.SECONDS))
                    "PREFETCH".byteInputStream()
                },
            )
            release.countDown()
            assertNotNull(
                first.get(10, TimeUnit.SECONDS),
                "Prefetch must not invalidate an in-flight foreground result",
            )
            releasePrefetch.countDown()
            withTimeout(10_000) { owner.children.toList().joinAll() }
            assertArrayEquals("FOREGROUND".toByteArray(), directory.listFiles().orEmpty().single().readBytes())
        } finally {
            release.countDown()
            releasePrefetch.countDown()
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS))
            scope.cancel()
            withTimeout(10_000) { owner.children.toList().joinAll() }
            cache.clear()
        }
    }
}
