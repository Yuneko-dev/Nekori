package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import android.webkit.WebResourceResponse
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import logcat.LogPriority
import logcat.logcat
import mihon.core.archive.NOVEL_IMAGE_SCHEME
import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class NovelWebViewImageCache(
    cacheDir: File,
    private val scope: CoroutineScope,
) {

    private val lock = Any()
    private val files = NovelImageCacheFiles(cacheDir, lock)
    private val prefetchJobs = ConcurrentHashMap<String, Job>()
    private val foregroundLocks = ConcurrentHashMap<String, ReentrantLock>()

    // Prefetch slots go to the most recently scheduled chapter first (preserving image order within
    // a chapter), so chapters already scrolled past don't hold up the one being read.
    private val chapterPriority = ConcurrentHashMap<Long, Long>()
    private var nextPriority = 0L
    private var nextWaiterOrder = 0L
    private val slotWaiters = mutableListOf<SlotWaiter>()
    private var runningPrefetches = 0

    private class SlotWaiter(
        val chapterId: Long,
        val order: Long,
        val gate: CompletableDeferred<Unit>,
    )

    private class AssetSource(val loader: PageLoader, val chapter: ReaderChapter?) {
        suspend fun useStream(path: String, block: (InputStream) -> Unit) {
            if (chapter == null) {
                loader.getPageDataStream(path)?.use(block)
            } else {
                chapter.withPageDataStream(loader, path, block)
            }
        }
    }
    private val chapterLoaderMap = ConcurrentHashMap<Long, AssetSource>()

    init {
        if (scope.isActive) {
            scope.launch(Dispatchers.IO) { files.sweepStaleFiles() }
        }
    }

    fun intercept(
        url: String,
        fallbackChapterId: Long?,
        fallbackLoader: PageLoader?,
        fallbackChapter: ReaderChapter? = null,
    ): WebResourceResponse? {
        if (!url.startsWith(URL_SCHEME_NOVEL_IMAGE)) return null
        val encodedSuffix = url.removePrefix(URL_SCHEME_NOVEL_IMAGE)

        // The infinite-scroll append path is the only producer of a literal "/" here: it prefixes the
        // scheme with "<chapterId>/". Real asset paths are URL-encoded whole, so their own slashes are
        // "%2F" and never appear as a literal slash. A leading numeric segment before a literal slash is
        // therefore always that append prefix, not an all-digits relative folder like "2020/cover.jpg".
        val slashIdx = encodedSuffix.indexOf('/')
        val (chapterId, encodedImagePath) = if (slashIdx > 0) {
            val possibleId = encodedSuffix.substring(0, slashIdx).toLongOrNull()
            if (possibleId != null) {
                possibleId to encodedSuffix.substring(slashIdx + 1)
            } else {
                fallbackChapterId to encodedSuffix
            }
        } else {
            fallbackChapterId to encodedSuffix
        }
        val imagePath = runCatching { URLDecoder.decode(encodedImagePath, "UTF-8") }.getOrNull() ?: return null

        val source = chapterId?.let { chapterLoaderMap[it] } ?: fallbackLoader?.let { AssetSource(it, fallbackChapter) }
        val cacheKey = buildCacheKey(chapterId, imagePath)
        return foregroundLocks.computeIfAbsent(cacheKey) { ReentrantLock() }.withLock {
            files.open(cacheKey)?.let { return@withLock WebResourceResponse(guessMimeType(imagePath), "UTF-8", it) }
            if (source == null) return@withLock null
            val owner = Any()
            synchronized(lock) {
                prefetchJobs.remove(cacheKey)?.cancel()
                files.claim(cacheKey, owner)
            }
            try {
                runBlocking {
                    source.useStream(imagePath) { input ->
                        files.write(cacheKey, owner, imagePath, input) { true }
                    }
                }
                files.open(cacheKey)?.let { WebResourceResponse(guessMimeType(imagePath), "UTF-8", it) }
            } catch (e: Exception) {
                logcat(LogPriority.DEBUG) { "NovelWebViewImageCache: sync load $imagePath failed: ${e.message}" }
                null
            } finally {
                files.release(cacheKey, owner)
            }
        }
    }

    fun schedulePrefetch(content: String, chapterId: Long?, loader: PageLoader?, chapter: ReaderChapter? = null) {
        if (chapterId == null || loader == null || !scope.isActive) return
        val source = AssetSource(loader, chapter)
        synchronized(lock) {
            chapterPriority[chapterId] = ++nextPriority
        }
        chapterLoaderMap[chapterId] = source
        IMAGE_URL_PATTERN.findAll(content)
            .map { it.groupValues[1] }
            .distinct()
            .forEach { encodedRef ->
                val slashIdx = encodedRef.indexOf('/')
                val encodedImagePath =
                    if (slashIdx > 0 && encodedRef.substring(0, slashIdx).toLongOrNull() == chapterId) {
                        encodedRef.substring(slashIdx + 1)
                    } else {
                        encodedRef
                    }
                val imagePath = runCatching { URLDecoder.decode(encodedImagePath, "UTF-8") }.getOrNull()
                    ?: return@forEach
                val order = synchronized(lock) { ++nextWaiterOrder }
                prefetch(chapterId, imagePath, source, order)
            }
    }

    internal fun queuedPrefetchCount(): Int = synchronized(lock) { slotWaiters.size }

    fun clear() = synchronized(lock) {
        prefetchJobs.values.forEach { it.cancel() }
        prefetchJobs.clear()
        slotWaiters.forEach { it.gate.cancel() }
        slotWaiters.clear()
        chapterPriority.clear()
        files.clear()
        chapterLoaderMap.clear()
    }

    private fun prefetch(chapterId: Long, imagePath: String, source: AssetSource, order: Long) {
        val cacheKey = buildCacheKey(chapterId, imagePath)
        val owner = Any()
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY, context = Dispatchers.IO) {
            try {
                withPrefetchSlot(chapterId, order) {
                    if (!isActive || files.contains(cacheKey)) return@withPrefetchSlot
                    source.useStream(imagePath) { input ->
                        files.write(cacheKey, owner, imagePath, input) { isActive }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.DEBUG) { "NovelWebViewImageCache: prefetch $imagePath failed: ${e.message}" }
            }
        }
        synchronized(lock) {
            if (!scope.isActive || job.isCancelled || files.contains(cacheKey) || files.isClaimed(cacheKey) ||
                prefetchJobs.putIfAbsent(cacheKey, job) != null
            ) {
                job.cancel()
                return
            }
            files.claim(cacheKey, owner)
            job.invokeOnCompletion {
                synchronized(lock) {
                    prefetchJobs.remove(cacheKey, job)
                    files.release(cacheKey, owner)
                }
            }
        }
        job.start()
    }

    private suspend fun <T> withPrefetchSlot(chapterId: Long, order: Long, block: suspend () -> T): T {
        val waiter = synchronized(lock) {
            if (runningPrefetches < MAX_CONCURRENT_PREFETCHES) {
                runningPrefetches++
                null
            } else {
                SlotWaiter(chapterId, order, CompletableDeferred()).also(slotWaiters::add)
            }
        }
        if (waiter != null) {
            try {
                waiter.gate.await()
            } catch (e: Throwable) {
                synchronized(lock) {
                    if (!slotWaiters.remove(waiter) && waiter.gate.isCompleted && !waiter.gate.isCancelled) {
                        releasePrefetchSlotLocked()
                    }
                }
                throw e
            }
        }
        return try {
            block()
        } finally {
            synchronized(lock) { releasePrefetchSlotLocked() }
        }
    }

    private fun releasePrefetchSlotLocked() {
        while (slotWaiters.isNotEmpty()) {
            val bestIdx = slotWaiters.indices.maxWith(
                compareBy<Int> { chapterPriority[slotWaiters[it].chapterId] ?: 0L }
                    .thenByDescending { slotWaiters[it].order },
            )
            val next = slotWaiters.removeAt(bestIdx)
            if (next.gate.complete(Unit)) return
        }
        runningPrefetches--
    }

    private fun buildCacheKey(chapterId: Long?, imagePath: String): String =
        "${chapterId ?: -1L}:$imagePath"

    private fun guessMimeType(imagePath: String): String =
        when (imagePath.substringBefore('?').substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "webp" -> "image/webp"
            "avif" -> "image/avif"
            "bmp" -> "image/bmp"
            "ico" -> "image/x-icon"
            "css" -> "text/css"
            "js", "mjs" -> "text/javascript"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "ogg", "ogv" -> "video/ogg"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "m4a" -> "audio/mp4"
            "vtt" -> "text/vtt"
            else -> "image/jpeg"
        }

    companion object {
        private const val MAX_CONCURRENT_PREFETCHES = 3
        const val URL_SCHEME_NOVEL_IMAGE = NOVEL_IMAGE_SCHEME
        private val IMAGE_URL_PATTERN = Regex("${Regex.escape(URL_SCHEME_NOVEL_IMAGE)}([^\"'<>\\s]+)")
    }
}
