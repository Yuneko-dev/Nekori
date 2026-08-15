package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Tag

internal const val LOCAL_VIDEO_BUTTON_ID = "tsundoku-play-local-video"

internal data class NovelWebViewChapterDirectives(
    val noCache: Boolean = false,
    val video: VideoChapter? = null,
    val localVideo: String? = null,
    val metadataHtml: String = "",
) {
    val isVideo: Boolean get() = video != null || localVideo != null

    companion object {
        fun parse(html: String): NovelWebViewChapterDirectives {
            val document = Jsoup.parse(html)
            val noCache = document.selectFirst("meta#no-cache-marker") != null
            val isVideo = document
                .selectFirst("meta[name=lnreader-chapter-type]")
                ?.attr("content")
                ?.equals("video", ignoreCase = true) == true
            val localVideo = document.metaContent("lnreader-video-local")
                .takeIf(String::isNotBlank)
                ?.takeIf { '/' !in it && '\\' !in it }

            val video = if (isVideo && localVideo == null) {
                val direct = document.metaContent("lnreader-video-mode").equals("direct", true)
                val type = document.metaContent("lnreader-video-type").lowercase()
                val knownType = type in VIDEO_TYPES
                val url = document.metaContent("lnreader-video-url")
                    .takeIf { !direct || knownType }
                    ?.takeIf(String::isNotBlank)
                    ?.takeIf { type != "iframe" || it.toHttpUrlOrNull() != null }
                if (direct && knownType && url == null) {
                    document.select("meta[name=lnreader-video-url]").remove()
                }

                VideoChapter(
                    directIframe = direct && type == "iframe",
                    disableProgress = document.selectFirst("meta#lnreader-video-disable-progress") != null,
                )
            } else {
                null
            }
            val metadata = document.select(RECOGNIZED_META_SELECTOR)
            val metadataHtml = metadata.joinToString("\n") { element ->
                Element(Tag.valueOf("meta"), "").apply {
                    element.id().takeIf(String::isNotBlank)?.let { attr("id", it) }
                    element.attr("name").takeIf(String::isNotBlank)?.let { attr("name", it) }
                    element.attr("content").takeIf(String::isNotBlank)?.let { attr("content", it) }
                }.outerHtml()
            }

            return NovelWebViewChapterDirectives(
                noCache = noCache,
                video = video,
                localVideo = localVideo,
                metadataHtml = metadataHtml,
            )
        }

        private const val RECOGNIZED_META_SELECTOR =
            "meta#no-cache-marker, meta#no-prefetch-marker, " +
                "meta#lnreader-video-disable-progress, " +
                "meta[name=lnreader-chapter-type], meta[name=lnreader-video-mode], " +
                "meta[name=lnreader-video-type], meta[name=lnreader-video-url], " +
                "meta[name=lnreader-video-poster], meta[name=lnreader-video-thumbnails], " +
                "meta[name=lnreader-debug-mode], meta[name=lnreader-player-type], " +
                "meta[name=lnreader-video-local]"

        /**
         * Named after the manifest extension, like `m3u8` — there is deliberately no `dash` alias,
         * because its counterpart `hls` is not accepted either. Mirrored by DIRECT_PLAYERS in
         * `assets/novel-reader/core-player.js`; a type accepted here but missing there fails at playback.
         */
        private val VIDEO_TYPES = setOf("m3u8", "mpd", "video-file", "iframe")
    }
}

internal data class VideoChapter(
    val directIframe: Boolean = false,
    val disableProgress: Boolean = false,
)

private fun org.jsoup.nodes.Document.metaContent(name: String): String =
    selectFirst("meta[name=$name]")?.attr("content").orEmpty().trim()
