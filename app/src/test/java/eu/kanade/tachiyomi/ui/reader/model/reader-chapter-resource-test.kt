@file:Suppress("ktlint:standard:filename")

package eu.kanade.tachiyomi.ui.reader.model

import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

class ReaderChapterResourceTest {
    private class Loader : PageLoader() {
        override var isLocal = true
        private val recycled = AtomicInteger()
        val closes get() = recycled.get()
        var failClose = false
        var open: suspend () -> InputStream? = { "image".byteInputStream() }
        override suspend fun getPages(): List<ReaderPage> = emptyList()
        override suspend fun getPageDataStream(url: String) = open()
        override fun recycle() {
            super.recycle()
            recycled.incrementAndGet()
            if (failClose) throw IOException("close failure")
        }
    }

    @Test
    fun `replaced loaders retire until the referenced chapter is released`() {
        val chapter = ReaderChapter(Chapter.create())
        val old = Loader()
        val current = Loader()
        chapter.ref()
        chapter.pageLoader = old
        chapter.pageLoader = current
        assertEquals(0, old.closes)
        chapter.unref()
        assertEquals(1, old.closes)
        assertEquals(1, current.closes)
    }

    @Test
    fun `unreferenced preload loader stays usable but its replacement closes`() = runBlocking {
        val chapter = ReaderChapter(Chapter.create())
        val old = Loader()
        val current = Loader()
        chapter.pageLoader = old
        assertEquals(0, old.closes)
        chapter.pageLoader = current
        assertEquals(1, old.closes)
        assertEquals(0, current.closes)
    }

    @Test
    fun `a scoped copy pins its retired loader and closes its stream once`() = runBlocking {
        val chapter = ReaderChapter(Chapter.create())
        val old = Loader()
        val current = Loader()
        val streamCloses = AtomicInteger()
        old.open = {
            object : ByteArrayInputStream(byteArrayOf(7)) {
                override fun close() {
                    streamCloses.incrementAndGet()
                    super.close()
                }
            }
        }
        chapter.ref()
        chapter.pageLoader = old
        chapter.withPageDataStream(old, "image") { stream ->
            chapter.pageLoader = current
            chapter.unref()
            assertEquals(0, old.closes)
            assertEquals(1, current.closes)
            assertEquals(7, stream.read())
        }
        assertEquals(1, streamCloses.get())
        assertEquals(1, old.closes)
        chapter.withPageDataStream(old, "late") { error("Retired loader must not reopen") }
    }

    @Test
    fun `getPages lease prevents unref from recycling a loader while work is running`() = runBlocking {
        val chapter = ReaderChapter(Chapter.create())
        val loader = Loader()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        chapter.ref()
        chapter.pageLoader = loader
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            chapter.withPageLoader(loader) {
                entered.complete(Unit)
                release.await()
                loader.getPages()
            }
        }
        entered.await()
        chapter.unref()
        assertEquals(0, loader.closes)
        release.complete(Unit)
        assertTrue(job.await().isEmpty())
        assertEquals(1, loader.closes)
    }

    @Test
    fun `cancelled opening closes a stream created before a dispatcher handoff completes`() = runBlocking {
        val chapter = ReaderChapter(Chapter.create())
        val loader = Loader()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val streamCloses = AtomicInteger()
        loader.open = {
            withContext(Dispatchers.IO) {
                val stream = object : ByteArrayInputStream(byteArrayOf(1)) {
                    override fun close() {
                        streamCloses.incrementAndGet()
                        super.close()
                    }
                }
                entered.complete(Unit)
                release.await()
                stream
            }
        }
        chapter.ref()
        chapter.pageLoader = loader
        val job = launch {
            chapter.withPageDataStream(loader, "image") { error("Cancelled opening must not start a copy") }
        }
        entered.await()
        job.cancel()
        chapter.unref()
        assertEquals(0, loader.closes)
        release.complete(Unit)
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, streamCloses.get())
        assertEquals(1, loader.closes)
    }

    @Test
    fun `null and failing opens release their resource lease`() = runBlocking {
        for (fail in listOf(false, true)) {
            val chapter = ReaderChapter(Chapter.create())
            val loader = Loader()
            chapter.ref()
            chapter.pageLoader = loader
            loader.open = { if (fail) throw IOException("source failure") else null }
            val result = runCatching {
                chapter.withPageDataStream(loader, "image") { error("No stream should be opened") }
            }
            assertEquals(fail, result.isFailure)
            chapter.unref()
            assertEquals(1, loader.closes)
        }
    }

    @Test
    fun `copy and close failures preserve the original error and release the lease once`() = runBlocking {
        for (failCopy in listOf(false, true)) {
            val chapter = ReaderChapter(Chapter.create())
            val loader = Loader()
            val copyError = IOException("copy failure")
            val closeError = IOException("close failure")
            val streamCloses = AtomicInteger()
            loader.open = {
                object : ByteArrayInputStream(byteArrayOf()) {
                    override fun close() {
                        streamCloses.incrementAndGet()
                        throw closeError
                    }
                }
            }
            chapter.ref()
            chapter.pageLoader = loader
            val result = runCatching {
                chapter.withPageDataStream(loader, "image") {
                    chapter.unref()
                    if (failCopy) throw copyError
                }
            }
            val expected = if (failCopy) copyError else closeError
            val failure = result.exceptionOrNull()
            assertEquals(expected.message, failure?.message)
            // Coroutine stack recovery can copy an exception while retaining its original cause.
            assertTrue(generateSequence(failure) { it.cause }.any { it === expected })
            if (failCopy) assertSame(closeError, copyError.suppressed.single())
            assertEquals(1, streamCloses.get())
            assertEquals(1, loader.closes)
        }
    }

    @Test
    fun `one failed retirement still closes every other reserved loader`() {
        val chapter = ReaderChapter(Chapter.create())
        val first = Loader().apply { failClose = true }
        val second = Loader().apply { failClose = true }
        chapter.ref()
        chapter.pageLoader = first
        chapter.pageLoader = second
        assertTrue(runCatching { chapter.unref() }.isFailure)
        assertEquals(1, first.closes)
        assertEquals(1, second.closes)
    }
}
