package eu.kanade.tachiyomi.data.translation

import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import kotlin.time.Duration.Companion.milliseconds

/**
 * The concurrency budget translation runs on, separate from the rest of the app.
 *
 * `NetworkHelper.client` keeps OkHttp's default `maxRequestsPerHost = 5` and shares one [Dispatcher]
 * with every consumer. All chunks of a chapter go to the same provider host, so that per-host cap -
 * sized for polite source browsing - was the real ceiling: a chapter set to translate 10 chunks at
 * once only ever had 5 in flight, and the slider silently did nothing above 5. `newBuilder()` copies
 * the dispatcher *reference*, so replacing it is the only way to opt out of the shared cap.
 *
 * Only the budget is separate. Everything else still rides along from the app client through
 * `newBuilder()`, and two of those must stay shared: the **DoH resolver** and the **DPI-bypass
 * socket factory** are user-facing network settings that have to apply to translation traffic too.
 * Do not build translation clients from a bare `OkHttpClient()` - that silently drops both.
 *
 * One instance for the process, not one per client: [LlmGenerator] rebuilds its client whenever the
 * timeout or RPM preference changes, and a dispatcher per client would strand a thread pool on every
 * change and hand the replacement an empty budget mid-chapter.
 */
private val translationDispatcher = Dispatcher().apply {
    // Matches the parallelism slider's upper bound, so the transport never caps below what the
    // user can ask for. The real limiter is the semaphore in TranslationParallelism.
    maxRequestsPerHost = TranslationService.MAX_PARALLEL_TRANSLATIONS
}

internal fun OkHttpClient.withTranslationTimeout(timeoutMs: Long): OkHttpClient {
    val timeout = timeoutMs.coerceAtLeast(1L).milliseconds
    return newBuilder()
        .dispatcher(translationDispatcher)
        .connectTimeout(timeout)
        .readTimeout(timeout)
        .writeTimeout(timeout)
        .callTimeout(timeout)
        .build()
}
