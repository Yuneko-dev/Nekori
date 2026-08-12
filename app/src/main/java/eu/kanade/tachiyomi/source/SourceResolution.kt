package eu.kanade.tachiyomi.source

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import tachiyomi.domain.source.service.SourceManager

/**
 * Suspends until every source is registered, or [timeoutMillis] elapses.
 *
 * Sources here come from JavaScript plugins, so the registry fills after a Hermes boot and a scan of
 * the plugins directory — seconds, not the milliseconds an APK-extension scan takes. Anything that
 * can run before or during that (a restored download queue, a scheduled job, a notification action
 * on a dead process) must await this first; [SourceManager.get] returns null until it completes and
 * callers written against the synchronous API silently treat that as "source uninstalled".
 *
 * Returns false on timeout, which callers must not read as "no such source": it means the runtime
 * never came up, and destructive cleanup keyed on a missing source has to be skipped.
 */
suspend fun SourceManager.awaitInitialized(timeoutMillis: Long = DEFAULT_INIT_TIMEOUT_MILLIS): Boolean {
    if (isInitialized.value) return true
    return withTimeoutOrNull(timeoutMillis) { isInitialized.first { it } } != null
}

/**
 * Tries to resolve a real source instance after process restore where source registration can lag.
 * Returns null on timeout so callers can safely fall back to getOrStub().
 */
suspend fun SourceManager.awaitSource(
    sourceId: Long,
    timeoutMillis: Long = 5_000L,
    pollIntervalMillis: Long = 100L,
): Source? {
    get(sourceId)?.let { return it }

    return withTimeoutOrNull(timeoutMillis) {
        awaitInitialized(timeoutMillis)

        var resolved: Source? = null
        while (resolved == null) {
            resolved = get(sourceId)
            if (resolved == null) {
                delay(pollIntervalMillis)
            }
        }
        resolved
    }
}

private const val DEFAULT_INIT_TIMEOUT_MILLIS = 30_000L
