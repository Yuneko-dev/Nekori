package eu.kanade.tachiyomi.data.translation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
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
 * Request pacing is deliberately absent: it belongs to the HTTP client, where it applies to every
 * request rather than only the ones this function happens to issue.
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
