package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import eu.kanade.tachiyomi.source.model.ChapterContent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Tag

internal const val LOCAL_VIDEO_BUTTON_ID = "nekori-play-local-video"

internal data class NovelWebViewChapterDirectives(
    val checkpoint: Boolean = false,
    val noCache: Boolean = false,
    val noPrefetch: Boolean = false,
    val video: VideoChapter? = null,
    val localVideo: String? = null,
    val metadataHtml: String = "",
) {
    val isVideo: Boolean get() = video != null || localVideo != null

    /** Adapt persisted control data in memory; downloaded chapter files remain unchanged. */
    fun adaptLoadedHtml(html: String): String {
        if (localVideo == null || !html.contains("tsundoku-play-local-video")) return html
        val document = Jsoup.parse(html)
        val button = document.getElementById("tsundoku-play-local-video") ?: return html
        if (button.tagName() != "button") return html
        button.id(LOCAL_VIDEO_BUTTON_ID)
        return document.outerHtml()
    }

    companion object {
        fun parse(html: String): NovelWebViewChapterDirectives {
            val legacy = ChapterContent.fromLegacy(html)
            val type = Jsoup.parse(html).metaContent("nekori-chapter-type").takeIf { it == "video" }
            return fromContent(if (type == null) legacy else legacy.copy(type = type))
        }

        fun fromContent(content: ChapterContent): NovelWebViewChapterDirectives {
            val document = Jsoup.parse(content.html)
            val noCache = content.noCache || content.isCheckpoint
            val noPrefetch = content.noPrefetch || content.isCheckpoint
            val isVideo = content.type == "video"
            val localVideo = document.videoMeta("video-local")
                .takeIf(String::isNotBlank)
                ?.takeIf { it !in setOf(".", "..") && '/' !in it && '\\' !in it && '\u0000' !in it }

            val video = if (isVideo && localVideo == null) {
                val direct = document.videoMeta("video-mode").equals("direct", true)
                val type = document.videoMeta("video-type").lowercase()
                val knownType = type in VIDEO_TYPES
                val url = document.videoMeta("video-url")
                    .takeIf { !direct || knownType }
                    ?.takeIf(String::isNotBlank)
                    ?.takeIf { type != "iframe" || it.toHttpUrlOrNull() != null }
                if (direct && knownType && url == null) {
                    document.select("meta[name=lnreader-video-url], meta[name=nekori-video-url]").remove()
                }

                VideoChapter(
                    directIframe = direct && type == "iframe",
                    disableProgress =
                    document.selectFirst(
                        "meta#lnreader-video-disable-progress, meta#nekori-video-disable-progress",
                    ) !=
                        null,
                    direct = direct,
                    type = type.takeIf { knownType },
                    url = url.orEmpty(),
                    poster = document.videoMeta("video-poster"),
                    thumbnailsVtt = document.videoMeta("video-thumbnails"),
                    debug = document.videoMeta("debug-mode").equals("true", true),
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
                checkpoint = content.isCheckpoint,
                noCache = noCache,
                noPrefetch = noPrefetch,
                video = video,
                localVideo = localVideo,
                metadataHtml = metadataHtml,
            )
        }

        private val RECOGNIZED_META_SELECTOR =
            "meta#no-cache-marker, meta#no-prefetch-marker, " +
                "meta#lnreader-video-disable-progress, " +
                "meta[name=lnreader-chapter-type], meta[name=lnreader-video-mode], " +
                "meta[name=lnreader-video-type], meta[name=lnreader-video-url], " +
                "meta[name=lnreader-video-poster], meta[name=lnreader-video-thumbnails], " +
                "meta[name=lnreader-debug-mode], meta[name=lnreader-player-type], " +
                "meta[name=lnreader-video-local]" +
                ", " +
                listOf(
                    "chapter-type",
                    "video-mode",
                    "video-type",
                    "video-url",
                    "video-poster",
                    "video-thumbnails",
                    "debug-mode",
                    "video-local",
                )
                    .joinToString(", ") { "meta[name=nekori-$it]" } + ", meta#nekori-video-disable-progress"

        /**
         * Named after the manifest extension, like `m3u8` — there is deliberately no `dash` alias,
         * because its counterpart `hls` is not accepted either. Mirrored by DIRECT_PLAYERS in
         * `reader-runtime/src/media/direct.ts`; a type accepted here but missing there fails at playback.
         */
        private val VIDEO_TYPES = setOf("m3u8", "mpd", "video-file", "iframe")
    }
}

internal data class VideoChapter(
    val directIframe: Boolean = false,
    val disableProgress: Boolean = false,
    val direct: Boolean = false,
    val type: String? = null,
    val url: String = "",
    val poster: String = "",
    val thumbnailsVtt: String = "",
    val debug: Boolean = false,
) {
    fun playerConfig(
        mode: String,
        path: String = "",
        progress: Int = 0,
        nextChapterName: String? = null,
        proxyEndpoint: String? = null,
        downloadEndpoint: String? = null,
    ): JsonObject = buildJsonObject {
        put("mode", mode)
        proxyEndpoint?.let { put("proxyEndpoint", it) }
        downloadEndpoint?.let { put("downloadEndpoint", it) }
        put(
            "video",
            buildJsonObject {
                type?.let { put("type", it) }
                put("url", url)
                put("path", path)
                put("progress", progress)
                put("direct", direct)
                put("disableProgress", disableProgress)
                put("debug", debug)
                if (poster.isNotBlank()) put("poster", poster)
                if (thumbnailsVtt.isNotBlank()) put("thumbnailsVtt", thumbnailsVtt)
                nextChapterName?.let { put("nextChapter", buildJsonObject { put("name", it) }) }
            },
        )
    }
}

private fun org.jsoup.nodes.Document.metaContent(name: String): String =
    selectFirst("meta[name=$name]")?.attr("content").orEmpty().trim()

// The old spelling is persisted chapter DATA. The new runtime only receives typed directives.
private fun org.jsoup.nodes.Document.videoMeta(suffix: String): String =
    metaContent("nekori-$suffix").ifBlank { metaContent("lnreader-$suffix") }
