package eu.kanade.tachiyomi.data.translation

import okhttp3.OkHttpClient
import kotlin.time.Duration.Companion.milliseconds

internal fun OkHttpClient.withTranslationTimeout(timeoutMs: Long): OkHttpClient {
    val timeout = timeoutMs.coerceAtLeast(1L).milliseconds
    return newBuilder()
        .connectTimeout(timeout)
        .readTimeout(timeout)
        .writeTimeout(timeout)
        .callTimeout(timeout)
        .build()
}
