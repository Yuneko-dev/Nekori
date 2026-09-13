package eu.kanade.tachiyomi.source.model

import kotlinx.serialization.Serializable
import org.jsoup.Jsoup

/** Official Nekori chapter contract. HTML is a payload, never a carrier for Nekori policy. */
@Serializable
data class ChapterContent(
    val state: String = "ready",
    val type: String = "novel",
    val html: String,
    val noCache: Boolean = false,
    val noPrefetch: Boolean = false,
    val checkpointMessage: String? = null,
) {
    val isCheckpoint: Boolean get() = state == "checkpoint"

    fun requireDownloadable() {
        check(!isCheckpoint) { checkpointMessage ?: "Chapter requires interaction" }
    }

    companion object {
        /** Legacy HTML and offline content enter the same contract at this boundary. */
        fun fromLegacy(html: String): ChapterContent {
            val document = Jsoup.parse(html)
            return ChapterContent(
                html = html,
                type = if (document.selectFirst("meta[name=lnreader-chapter-type]")?.attr("content") ==
                    "video"
                ) {
                    "video"
                } else {
                    "novel"
                },
                noCache = document.selectFirst("meta#no-cache-marker") != null,
                noPrefetch = document.selectFirst("meta#no-prefetch-marker") != null,
            )
        }
    }
}
