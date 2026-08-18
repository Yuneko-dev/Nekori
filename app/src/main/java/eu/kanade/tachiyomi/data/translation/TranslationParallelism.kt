package eu.kanade.tachiyomi.data.translation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicReference

/** Which value failed a parallel run, and why. */
internal data class ParallelFailure(val index: Int, val cause: Throwable)

/**
 * Runs [transform] over [values] with at most [maxParallel] of them in flight.
 *
 * Once one value fails, nothing that has not started yet is started - work past the failure is
 * discarded anyway, since only a contiguous prefix can be resumed. Values already in flight are
 * left to finish so their results stay usable. [transform] stores its own output; this returns
 * only the earliest failure, which is all either caller needs.
 *
 * Request pacing is deliberately absent here: it belongs to [RequestPacer] below, which every AI
 * request passes through rather than only the ones this function happens to issue.
 */
internal suspend fun <T> parallelCatchingFirstFailure(
    values: List<T>,
    maxParallel: Int,
    transform: suspend (index: Int, value: T) -> Unit,
): ParallelFailure? = coroutineScope {
    val permits = Semaphore(maxParallel.coerceAtLeast(1))
    val failure = AtomicReference<ParallelFailure?>()
    values.mapIndexed { index, value ->
        launch {
            permits.withPermit {
                if (failure.get() != null) return@withPermit
                try {
                    transform(index, value)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failure.updateAndGet { current ->
                        if (current == null || index < current.index) ParallelFailure(index, e) else current
                    }
                }
            }
        }
    }.joinAll()
    failure.get()
}

/** The translated chunks up to the first gap - the only run that a saved `.tmp` can be resumed from. */
internal fun contiguousTranslationPrefix(chunks: List<List<String>?>): List<String> =
    chunks.takeWhile { it != null }.filterNotNull().flatten()

/**
 * Holds callers back to at most N requests per minute, in arrival order.
 *
 * The wait happens *before* a request is issued rather than inside an OkHttp interceptor. The
 * translation client sets `callTimeout`, which covers application interceptors, so a request that
 * queued for its turn was spending its own timeout budget standing still - a low limit turned
 * throttling into timeouts and then into retries of requests that had never been sent.
 *
 * The limit is passed per call, not held as state: changing the preference takes effect on the next
 * request instead of when some client happens to be rebuilt.
 */
internal class RequestPacer {

    private val mutex = Mutex()

    /** Issue times of the requests still inside the window, oldest first. */
    private val issued = ArrayDeque<Long>()

    suspend fun acquire(perMinute: Int, now: () -> Long = { System.nanoTime() / 1_000_000 }) {
        if (perMinute <= 0) return
        // Held across the delay on purpose: the mutex is FIFO, so waiting inside it is what makes
        // turns come in arrival order instead of whoever wakes up first.
        mutex.withLock {
            while (true) {
                val current = now()
                while (issued.isNotEmpty() && current - issued.first() >= WINDOW_MS) issued.removeFirst()
                if (issued.size < perMinute) {
                    issued.addLast(current)
                    return
                }
                delay(WINDOW_MS - (current - issued.first()))
            }
        }
    }

    private companion object {
        const val WINDOW_MS = 60_000L
    }
}
