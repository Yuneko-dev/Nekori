package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class LnReaderCompatConfig(
    val novel: Novel,
    val chapter: Chapter,
    val nextChapter: Chapter?,
    val strings: Map<String, String> = emptyMap(),
    val autoSaveInterval: Int = 5_000,
    val proxyEndpoint: String? = null,
) {
    @Serializable
    data class Novel(
        val id: Long,
        val name: String,
        val path: String,
    )

    @Serializable
    data class Chapter(
        val id: Long,
        val name: String,
        val path: String,
        val progress: Int,
    )

    fun encode(): String = Json.encodeToString(this)
        .replace(Regex("</script>", RegexOption.IGNORE_CASE)) { "<\\/" + it.value.substring(2) }
}
