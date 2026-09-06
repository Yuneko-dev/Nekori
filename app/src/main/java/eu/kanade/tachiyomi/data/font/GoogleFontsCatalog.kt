package eu.kanade.tachiyomi.data.font

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.util.Locale

/** Catalog used by the Google Fonts website; fetched only when the font browser is opened. */
internal class GoogleFontsCatalog(private val fetch: suspend () -> String) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private var cached: List<GoogleFontInfo>? = null

    suspend fun search(query: String): List<GoogleFontInfo> {
        val fonts = mutex.withLock {
            cached ?: parse(fetch()).also { cached = it }
        }
        val term = query.trim()
        return fonts.filter { it.family.contains(term, ignoreCase = true) }
    }

    private fun parse(body: String): List<GoogleFontInfo> {
        val catalog = json.decodeFromString<Catalog>(body.trimStart().removePrefix(")]}'").trimStart())
        return catalog.familyMetadataList.map { family ->
            require(family.family.isNotBlank()) { "Invalid Google Fonts family" }
            GoogleFontInfo(family.family, family.category.lowercase(Locale.ROOT), family.fonts.keys.toList())
        }.distinctBy { it.family }.sortedBy { it.family.lowercase(Locale.ROOT) }.also {
            require(it.isNotEmpty()) { "Google Fonts catalog is empty" }
        }
    }

    @Serializable
    private data class Catalog(val familyMetadataList: List<Family>)

    @Serializable
    private data class Family(
        val family: String,
        val category: String = "",
        val fonts: Map<String, JsonElement> = emptyMap(),
    )
}
