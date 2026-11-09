package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Marks Hermes plugin traffic so JS-only network policies do not affect app requests. */
object JsPluginOrigin

private class JsPluginOriginInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(
        chain.request().newBuilder()
            .tag(JsPluginOrigin::class.java, JsPluginOrigin)
            .build(),
    )
}

/**
 * Returns a client identical to this one but whose requests are tagged as plugin-JavaScript
 * traffic. Inserted at the front of the chain for the same reason as
 * [rateLimitExempt]: [PerHostDynamicRateLimitInterceptor] is already baked into the base client,
 * and a plain `addInterceptor` would append the tag *after* the rate limiter has already read the
 * request.
 */
fun OkHttpClient.markJsPluginOrigin(): OkHttpClient = newBuilder()
    .apply { interceptors().add(0, JsPluginOriginInterceptor()) }
    .build()

val Request.isJsPluginOrigin: Boolean
    get() = tag(JsPluginOrigin::class.java) != null
