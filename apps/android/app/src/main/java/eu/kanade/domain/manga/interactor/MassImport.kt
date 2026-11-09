package eu.kanade.domain.manga.interactor

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.jsplugin.source.JsSource
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.isNovelSource
import kotlinx.coroutines.Dispatchers
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Helper for the URL-based mass import flow. Provides URL parsing/analysis and per-URL novel
 * resolution. The actual batched import is executed by
 * [eu.kanade.tachiyomi.data.massimport.MassImportJob].
 */
class MassImport(
    private val sourceManager: SourceManager = Injekt.get(),
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
    private val mangaRepository: MangaRepository = Injekt.get(),
    private val sourcePreferences: SourcePreferences = Injekt.get(),
) {
    private val missingSourceHostLogCache = ConcurrentHashMap<String, Boolean>()
    private val domainForwarding by lazy { Injekt.get<NetworkHelper>().domainForwarding }

    companion object {
        private val GLUE_REGEX = Regex("(?<=[^\\s])(?=https?://)")
        private val SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+\\-.]*://")

        /**
         * Trailing-slash-insensitive comparison key for a source path. A plugin-returned path is
         * stored verbatim, so this is a **comparison key only** and never what gets written: mass
         * import is the one place holding a second, user-typed spelling of a path the plugin
         * already spelled its own way, and `https://site/novel/abc/` must still recognise the
         * stored `/novel/abc`.
         */
        fun pathCompareKey(path: String): String = if (path.length > 1) path.trimEnd('/') else path

        /** The other trailing-slash spelling of [path], or null when there is none worth probing. */
        fun trailingSlashVariant(path: String): String? {
            val key = pathCompareKey(path)
            if (key.length <= 1) return null
            return if (path == key) "$key/" else key
        }

        // Shared tokenizer for every entry point so the "valid" count matches what the import
        // walks. Splits on comma/semicolon/space/tab and de-glues separator-less URLs.
        fun tokenizeLine(line: String): List<String> {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return emptyList()
            return trimmed.replace(GLUE_REGEX, "\n")
                .split('\n', ',', ';', ' ', '\t')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }
    }

    suspend fun resolveMangaUrl(url: String, path: String, source: CatalogueSource): Manga {
        // No search-by-URL or slash-toggle fallbacks here: they never resolved anything reliably
        // and their failures (e.g. UnknownHostException from a slash-less baseUrl + url concat)
        // masked the real error from the direct details fetch.
        var resolved: eu.kanade.tachiyomi.source.model.SManga? = null
        if (source is eu.kanade.tachiyomi.source.online.ResolvableSource &&
            source.getUriType(url) == eu.kanade.tachiyomi.source.online.UriType.Manga
        ) {
            resolved = runCatching { source.getManga(url) }.getOrNull()
        }

        val sManga = resolved ?: source.getMangaUpdate(
            eu.kanade.tachiyomi.source.model.SManga.create().apply {
                this.url = path
            },
            emptyList(),
            fetchDetails = true,
            fetchChapters = false,
        ).manga

        try {
            val resolvedUrl = runCatching { sManga.url }.getOrNull().orEmpty()
            sManga.url = resolvedUrl.ifBlank { path }
        } catch (_: UninitializedPropertyAccessException) {
            sManga.url = path
        }

        try {
            @Suppress("UNUSED_VARIABLE")
            val titleCheck = sManga.title
        } catch (_: UninitializedPropertyAccessException) {
            throw Exception("Extension failed to parse novel title from $url")
        }

        return networkToLocalManga(sManga.toDomainManga(source.id, source.isNovelSource()))
    }

    private fun getAllSources(): List<CatalogueSource> {
        return sourceManager.getAll().filterIsInstance<JsSource>()
    }

    // Single source-matching algorithm shared by the analysis preview and the worker
    // (MassImportJob delegates here) so the dialog's "valid" classification can't disagree with
    // what the import actually resolves. Host + path-prefix match, not a raw string startsWith:
    // startsWith broke on www./mirror-subdomain differences.
    fun findMatchingSource(
        url: String,
        sources: List<CatalogueSource> = getAllSources(),
        preferredSourceId: Long? = null,
    ): CatalogueSource? {
        val urlHost = try {
            URI(url).host?.lowercase()?.removePrefix("www.")
        } catch (_: Exception) {
            null
        }
        if (urlHost.isNullOrEmpty()) return null
        val matchingSources = sources.filter { source ->
            baseUrlCandidates(source).any { base -> matchesBaseUrl(url, urlHost, base) }
        }

        if (matchingSources.isEmpty()) {
            if (missingSourceHostLogCache.putIfAbsent(urlHost, true) == null) {
                logcat(LogPriority.WARN) { "MassImport: No source match for $url host=$urlHost" }
            }
            return null
        }
        if (matchingSources.size == 1) return matchingSources.first()

        // Prefer the caller's source (e.g. the currently browsed source) when it matches.
        if (preferredSourceId != null) {
            matchingSources.firstOrNull { it.id == preferredSourceId }?.let { return it }
        }

        val enabledLanguages = sourcePreferences.enabledLanguages.get()
        val disabledSources = sourcePreferences.disabledSources.get()
        val enabledSources = matchingSources.filter {
            it.lang in enabledLanguages && it.id.toString() !in disabledSources
        }
        val bestLangSources = if (enabledSources.isNotEmpty()) enabledSources else matchingSources

        return bestLangSources.first()
    }

    fun getSourceBaseUrl(source: CatalogueSource): String {
        return (source as? JsSource)?.baseUrl.orEmpty()
    }

    /**
     * The base URLs a pasted link may legitimately carry for this source: the plugin's own site, plus
     * its domain-forwarding target when one is configured. A forwarded source is *reached* at the
     * target host, so that is the host the user copies out of a browser. The plugin's own baseUrl
     * stays the identity - this only widens matching.
     */
    private fun baseUrlCandidates(source: CatalogueSource): List<String> {
        val raw = getSourceBaseUrl(source).ifBlank { return emptyList() }
        val absolute = if (raw.startsWith("http")) raw else "https://$raw"
        // Mass import resolves through JsSource, whose traffic is tagged JsPluginOrigin, so
        // plugin-scoped mappings apply here as well as global ones.
        val forwarded = domainForwarding.rewrite(absolute, fromJsPlugin = true)
        return if (forwarded == absolute) listOf(absolute) else listOf(absolute, forwarded)
    }

    private fun matchesBaseUrl(url: String, urlHost: String, base: String): Boolean {
        return try {
            val baseUri = URI(base)
            val baseHost = baseUri.host?.lowercase()?.removePrefix("www.")
            if (baseHost.isNullOrEmpty()) return false

            val hostMatches = urlHost == baseHost ||
                urlHost.endsWith(".$baseHost") ||
                baseHost.endsWith(".$urlHost")
            if (!hostMatches) return false

            val basePath = baseUri.path?.trimEnd('/')
            if (!basePath.isNullOrBlank() && basePath != "/") {
                (URI(url).path ?: "").startsWith(basePath)
            } else {
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    fun extractPathFromUrl(url: String, baseUrl: String): String {
        // The source was already matched to this URL by the caller, so whenever the URL parses
        // as an absolute URL just take its path + query. Comparing hosts here breaks on
        // www./mirror-subdomain mismatches (e.g. sonicmtl.com vs www.sonicmtl.com) and used to
        // leak the host into the path, producing requests like "https://www.sonicmtl.comsonicmtl.com/...".
        val extractedPath = try {
            val urlUri = URI(url)
            if (urlUri.host != null) {
                buildString {
                    append(urlUri.rawPath ?: "")
                    val q = urlUri.rawQuery
                    if (!q.isNullOrBlank()) {
                        append('?')
                        append(q)
                    }
                }
            } else {
                extractPathFallback(url, baseUrl)
            }
        } catch (_: Exception) {
            extractPathFallback(url, baseUrl)
        }

        return extractedPath
    }

    /**
     * String-based path extraction for URLs that [URI] can't parse. Preserves path casing and
     * never leaks the host into the returned path even when it doesn't match [baseUrl].
     */
    private fun extractPathFallback(url: String, baseUrl: String): String {
        val schemeRegex = Regex("^[a-zA-Z][a-zA-Z0-9+\\-.]*://")
        val rawUrl = url.trim().replace(schemeRegex, "")
        val normalizedUrl = rawUrl.removePrefix("www.")
        val normalizedBase = baseUrl.trim().replace(schemeRegex, "")
            .removePrefix("www.")
            .removeSuffix("/")

        if (normalizedUrl.startsWith(normalizedBase, ignoreCase = true)) {
            return normalizedUrl.substring(normalizedBase.length)
        }

        // Host mismatch (mirror/subdomain): drop everything before the first slash.
        val slashIndex = normalizedUrl.indexOf('/')
        return if (slashIndex >= 0) normalizedUrl.substring(slashIndex) else normalizedUrl
    }

    fun parseUrls(text: String): List<String> {
        return text.lineSequence()
            .flatMap { tokenizeLine(it).asSequence() }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinctBy { urlDedupKey(it) }
            .toList()
    }

    data class UrlAnalysisResult(
        val validUrls: List<String>,
        val invalidUrls: List<Pair<String, String>>,
        val duplicateUrls: List<String>,
        val alreadyInLibrary: List<String>,
    ) {
        val totalValid get() = validUrls.size
    }

    suspend fun analyzeUrls(text: String): UrlAnalysisResult = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val novelSources = getAllSources()
        val libraryUrlIndex = try {
            // Keyed the same way the input dedup below is, so the two halves of this check can't
            // disagree about whether a trailing slash makes two URLs different.
            mangaRepository.getFavoriteSourceAndUrl()
                .mapTo(HashSet()) { (sourceId, url) -> sourceId to pathCompareKey(url) }
        } catch (_: Exception) {
            emptySet()
        }

        val rawLines = text.lineSequence().flatMap { tokenizeLine(it).asSequence() }
        val validUrls = mutableListOf<String>()
        val invalidUrls = mutableListOf<Pair<String, String>>()
        val duplicateUrls = mutableListOf<String>()
        val alreadyInLibrary = mutableListOf<String>()
        val seenKeys = mutableSetOf<String>()

        for (line in rawLines) {
            if (!line.startsWith("http://") && !line.startsWith("https://")) {
                invalidUrls.add(line to "Not a valid URL")
                continue
            }

            val key = urlDedupKey(line)
            if (key in seenKeys) {
                duplicateUrls.add(line)
                continue
            }
            seenKeys.add(key)

            val source = findMatchingSource(line, novelSources)
            if (source == null) {
                invalidUrls.add(line to "No matching source")
                continue
            }
            val path = extractPathFromUrl(line, getSourceBaseUrl(source))
            if (libraryUrlIndex.contains(source.id to pathCompareKey(path))) {
                alreadyInLibrary.add(line)
                continue
            }

            validUrls.add(line)
        }

        UrlAnalysisResult(validUrls, invalidUrls, duplicateUrls, alreadyInLibrary)
    }

    private fun urlDedupKey(url: String): String {
        return try {
            val uri = URI(url.trim())
            buildString {
                append(uri.host?.lowercase() ?: "")
                append(uri.rawPath?.trimEnd('/') ?: "")
                val q = uri.rawQuery
                if (!q.isNullOrBlank()) append('?').append(q)
            }
        } catch (_: Exception) {
            // Unparseable URL: lowercase only the host, preserve case-sensitive path.
            val noScheme = url.trim().replace(SCHEME_REGEX, "")
            val slash = noScheme.indexOf('/')
            val key = if (slash < 0) {
                noScheme.lowercase()
            } else {
                noScheme.substring(0, slash).lowercase() + noScheme.substring(slash)
            }
            key.removeSuffix("/")
        }
    }
}

private fun eu.kanade.tachiyomi.source.model.SManga.toDomainManga(sourceId: Long, isNovel: Boolean = false): Manga {
    return Manga.create().copy(
        url = url,
        title = title,
        artist = artist,
        author = author,
        description = description,
        genre = genre?.split(", ") ?: emptyList(),
        status = status.toLong(),
        thumbnailUrl = thumbnail_url,
        initialized = initialized,
        source = sourceId,
        isNovel = isNovel,
    )
}
