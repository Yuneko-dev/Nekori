package eu.kanade.tachiyomi.network

import okhttp3.Interceptor
import okhttp3.Response

data class AdditionalCookie(val value: String)

internal class AdditionalCookieInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val additional = request.tag(AdditionalCookie::class)?.value
        val merged = mergeCookieHeaders(additional, request.headers.values("Cookie").joinToString("; "))
            ?: return chain.proceed(request)

        return chain.proceed(
            request.newBuilder()
                .header("Cookie", merged)
                .build(),
        )
    }
}

internal fun mergeCookieHeaders(additional: String?, existing: String?): String? =
    listOfNotNull(
        additional?.trim()?.takeIf(String::isNotEmpty),
        existing?.trim()?.takeIf(String::isNotEmpty),
    )
        .joinToString("; ")
        .ifEmpty { null }
