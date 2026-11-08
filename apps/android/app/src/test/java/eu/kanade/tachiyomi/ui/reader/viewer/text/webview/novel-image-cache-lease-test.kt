@file:Suppress("ktlint:standard:filename")

package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.chapter.model.Chapter
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NovelImageCacheLeaseTest {
    @TempDir lateinit var directory: File
    private val reference = "tsundoku-novel-image://cover.jpg"

    @Test
    fun `foreground source stream outlives chapter unref until its copy closes`() = runBlocking {
        assertCopyLease(prefetch = false)
    }

    @Test
    fun `cancelled prefetch holds source stream lease until blocking copy closes`() = runBlocking {
        assertCopyLease(prefetch = true)
    }

    private suspend fun assertCopyLease(prefetch: Boolean) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val recycled = AtomicInteger()
        val closed = AtomicInteger()
        val source = object : PageLoader() {
            override var isLocal = true
            override suspend fun getPages(): List<ReaderPage> = emptyList()
            override suspend fun getPageDataStream(url: String) = object : ByteArrayInputStream("image".toByteArray()) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    check(!isRecycled) { "Archive was recycled during the copy" }
                    return super.read(buffer, offset, length)
                }
                override fun close() {
                    closed.incrementAndGet()
                    super.close()
                }
            }
            override fun recycle() {
                super.recycle()
                recycled.incrementAndGet()
            }
        }
        val chapter = ReaderChapter(Chapter.create())
        chapter.ref()
        chapter.pageLoader = source
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        val cache = NovelWebViewImageCache(directory, scope)
        val foreground = if (prefetch) null else scope.async { cache.intercept(reference, 1, source, chapter) }
        try {
            if (prefetch) cache.schedulePrefetch("""<img src="$reference">""", 1, source, chapter)
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            if (prefetch) scope.cancel()
            chapter.unref()
            assertEquals(0, recycled.get(), "Copy still owns the archive resource")
            release.countDown()
            if (foreground != null) assertNotNull(foreground.await())
            job.children.toList().joinAll()
            assertEquals(1, closed.get())
            assertEquals(1, recycled.get())
        } finally {
            release.countDown()
            scope.cancel()
            job.children.toList().joinAll()
            cache.clear()
        }
    }
}
