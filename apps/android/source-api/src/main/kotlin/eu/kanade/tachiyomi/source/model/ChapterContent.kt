package eu.kanade.tachiyomi.source.model

import kotlinx.serialization.Serializable
import org.jsoup.Jsoup

/** Official Nekori chapter interface. HTML is a payload, never a carrier for Nekori policy. */
@Serializable
data class ChapterContent(
    val state: String = STATE_READY,
    val type: String = TYPE_NOVEL,
    val html: String,
    val noCache: Boolean = false,
    val noPrefetch: Boolean = false,
    val checkpointMessage: String? = null,
) {
    val isCheckpoint: Boolean get() = state == STATE_CHECKPOINT

    /** Checkpoint forces no-cache and no-prefetch. Adapters read this, not the raw flags. */
    val effectiveNoCache: Boolean get() = noCache || isCheckpoint
    val effectiveNoPrefetch: Boolean get() = noPrefetch || isCheckpoint

    fun requireDownloadable() {
        check(!isCheckpoint) { checkpointMessage ?: "Chapter requires interaction" }
    }

    companion object {
        const val STATE_READY = "ready"
        const val STATE_CHECKPOINT = "checkpoint"
        const val TYPE_NOVEL = "novel"
        const val TYPE_VIDEO = "video"

        /** Legacy HTML and offline content share the same ChapterContent interface. */
        fun fromLegacy(html: String): ChapterContent {
            val document = Jsoup.parse(html)
            return ChapterContent(
                html = html,
                type = if (document.selectFirst(
                        "meta[name=nekori-chapter-type], meta[name=lnreader-chapter-type]",
                    )?.attr("content") ==
                    TYPE_VIDEO
                ) {
                    TYPE_VIDEO
                } else {
                    TYPE_NOVEL
                },
                noCache = document.selectFirst("meta#no-cache-marker") != null,
                noPrefetch = document.selectFirst("meta#no-prefetch-marker") != null,
            )
        }
    }
}
