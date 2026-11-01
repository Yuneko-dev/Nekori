package eu.kanade.tachiyomi.ui.reader.model

import eu.kanade.domain.chapter.model.toDbChapter
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.FilterInputStream
import java.io.InputStream
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class ReaderChapter(val chapter: Chapter) {

    val stateFlow = MutableStateFlow<State>(State.Wait)
    val wordCount = MutableStateFlow<Int?>(null)

    // Retain checkpoint policy after no-cache HTML or recycled pages have been discarded.
    var isCheckpoint: Boolean = false
        get() = pages?.firstOrNull()?.chapterContent?.isCheckpoint ?: field

    var state: State
        get() = stateFlow.value
        set(value) {
            stateFlow.value = value
        }

    val pages: List<ReaderPage>?
        get() = (state as? State.Loaded)?.pages

    private class LoaderUse(val loader: PageLoader) {
        var active = 0
        var retired = false
        var closing = false
    }

    private val resourceLock = Any()
    private val loaders = IdentityHashMap<PageLoader, LoaderUse>()
    private var currentLoader: PageLoader? = null
    private var loadGeneration = 0L
    var pageLoader: PageLoader?
        get() = synchronized(resourceLock) { currentLoader }
        set(value) {
            val closing = synchronized(resourceLock) {
                if (value === currentLoader) return
                loadGeneration++
                replaceLoader(value)
                reserveRecycling()
            }
            recycle(closing)
        }

    private fun replaceLoader(value: PageLoader?) {
        check(value == null || (!value.isRecycled && loaders[value]?.closing != true)) { "Loader already recycled" }
        currentLoader?.let { loaders.getValue(it).retired = true }
        currentLoader = value
        value?.let { loaders.getOrPut(it) { LoaderUse(it) }.retired = false }
    }

    fun beginPageLoad(): Long = synchronized(resourceLock) {
        ++loadGeneration
        state = State.Loading
        loadGeneration
    }

    fun attachPageLoader(generation: Long, loader: PageLoader): Boolean {
        val closing = synchronized(resourceLock) {
            if (generation != loadGeneration) return false
            replaceLoader(loader)
            reserveRecycling()
        }
        recycle(closing)
        return true
    }

    fun completePageLoad(generation: Long, loader: PageLoader, pages: List<ReaderPage>): Boolean = synchronized(
        resourceLock,
    ) {
        if (generation != loadGeneration || currentLoader !== loader || loader.isRecycled) return false
        if (!chapter.read) requestedPage = chapter.last_page_read
        state = State.Loaded(pages)
        true
    }

    fun failPageLoad(generation: Long, error: Throwable, cancelled: Boolean = false): Boolean = synchronized(
        resourceLock,
    ) {
        if (generation != loadGeneration) return false
        state = if (cancelled) State.Wait else State.Error(error)
        true
    }

    suspend fun <T> withPageLoader(loader: PageLoader, block: suspend () -> T): T {
        val lease = checkNotNull(acquire(loader)) { "Loader is no longer available" }
        return try {
            block()
        } finally {
            release(lease)
        }
    }

    suspend fun openPageDataStream(loader: PageLoader, path: String): InputStream? {
        val lease = acquire(loader) ?: return null
        var input: InputStream? = null
        var handedOff = false
        try {
            // Opening a local closeable must hand it back before caller cancellation can discard it.
            // Page/plugin operations use withPageLoader instead and remain cancellable.
            withContext(NonCancellable) { input = loader.getPageDataStream(path) }
            currentCoroutineContext().ensureActive()
            val opened = input ?: return null
            val stream = object : FilterInputStream(opened) {
                private val closed = AtomicBoolean()
                override fun close() {
                    if (!closed.compareAndSet(false, true)) return
                    try {
                        super.close()
                    } finally {
                        release(lease)
                    }
                }
            }
            handedOff = true
            return stream
        } catch (error: Throwable) {
            try {
                input?.close()
            } catch (closeError: Throwable) {
                error.addSuppressed(closeError)
            }
            throw error
        } finally {
            if (!handedOff) release(lease)
        }
    }

    var requestedPage: Int = 0

    private var references = 0

    constructor(chapter: tachiyomi.domain.chapter.model.Chapter) : this(chapter.toDbChapter())

    fun ref() {
        synchronized(resourceLock) { references++ }
    }

    fun unref() {
        val closing = synchronized(resourceLock) {
            references--
            if (references == 0) {
                loadGeneration++
                currentLoader?.let { loaders.getValue(it).retired = true }
                currentLoader = null
                state = State.Wait
                wordCount.value = null
            }
            reserveRecycling()
        }
        recycle(closing)
    }

    private fun acquire(loader: PageLoader): LoaderUse? = synchronized(resourceLock) {
        loaders[loader]?.takeIf { !it.closing && !loader.isRecycled }?.also { it.active++ }
    }

    private fun release(lease: LoaderUse) {
        val closing = synchronized(resourceLock) {
            lease.active--
            reserveRecycling()
        }
        recycle(closing)
    }

    /** Reserve ownership under the lock; closing resources happens outside it. */
    private fun reserveRecycling(): List<LoaderUse> = loaders.values.filter {
        references <= 0 && it.retired && it.active == 0 && !it.closing
    }.onEach { it.closing = true }

    private fun recycle(closing: List<LoaderUse>) {
        var failure: Throwable? = null
        closing.forEach { entry ->
            try {
                if (!entry.loader.isRecycled) entry.loader.recycle()
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            } finally {
                synchronized(resourceLock) {
                    if (entry.loader.isRecycled) loaders.remove(entry.loader)
                }
            }
        }
        failure?.let { throw it }
    }

    sealed interface State {
        data object Wait : State
        data object Loading : State
        data class Error(val error: Throwable) : State
        data class Loaded(val pages: List<ReaderPage>) : State
    }
}
