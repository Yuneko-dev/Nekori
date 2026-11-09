package eu.kanade.tachiyomi.network.interceptor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import tachiyomi.core.common.preference.Preference

class DomainForwarding(
    private val preference: Preference<String>,
) {

    @Serializable
    data class Mapping(val source: String, val target: String, val global: Boolean)

    private val _mappings = MutableStateFlow(loadMappings())

    val mappings = _mappings.asStateFlow()

    @Synchronized
    fun put(source: String, target: String, global: Boolean) {
        val normalizedSource = normalizeOrigin(source) ?: return
        val normalizedTarget = normalizeOrigin(target) ?: return
        _mappings.value = (
            _mappings.value.filterNot { it.source == normalizedSource } +
                Mapping(normalizedSource, normalizedTarget, global)
            ).sortedBy { it.source }
        saveMappings()
    }

    @Synchronized
    fun remove(source: String) {
        val normalizedSource = normalizeOrigin(source) ?: return
        _mappings.value = _mappings.value.filterNot { it.source == normalizedSource }
        saveMappings()
    }

    fun rewrite(url: String, fromJsPlugin: Boolean): String {
        val httpUrl = url.toHttpUrlOrNull() ?: return url
        val rewrittenUrl = rewrite(httpUrl, fromJsPlugin)
        return if (rewrittenUrl == httpUrl) url else rewrittenUrl.toString()
    }

    internal fun rewrite(url: HttpUrl, fromJsPlugin: Boolean): HttpUrl {
        val source = url.origin()
        val mapping = _mappings.value.firstOrNull { it.source == source } ?: return url
        if (!mapping.global && !fromJsPlugin) return url
        val target = mapping.target.toHttpUrlOrNull() ?: return url
        return url.newBuilder()
            .scheme(target.scheme)
            .host(target.host)
            .port(target.port)
            .build()
    }

    private fun loadMappings(): List<Mapping> = runCatching {
        Json.decodeFromString<List<Mapping>>(preference.get())
    }.getOrDefault(emptyList())
        .mapNotNull { mapping ->
            val source = normalizeOrigin(mapping.source) ?: return@mapNotNull null
            val target = normalizeOrigin(mapping.target) ?: return@mapNotNull null
            Mapping(source, target, mapping.global)
        }
        .distinctBy { it.source }
        .sortedBy { it.source }

    private fun saveMappings() {
        preference.set(Json.encodeToString(_mappings.value))
    }

    companion object {
        fun normalizeOrigin(value: String): String? = value.trim().toHttpUrlOrNull()?.origin()

        private fun HttpUrl.origin(): String = newBuilder()
            .username("")
            .password("")
            .encodedPath("/")
            .query(null)
            .fragment(null)
            .build()
            .toString()
            .removeSuffix("/")
    }
}

internal class DomainForwardingInterceptor(
    private val domainForwarding: DomainForwarding,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = domainForwarding.rewrite(request.url, request.isJsPluginOrigin)
        return chain.proceed(request.newBuilder().url(url).build())
    }
}
