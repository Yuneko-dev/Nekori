package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Tag

internal data class NovelWebViewChapterDirectives(
    val noCache: Boolean = false,
    val video: VideoChapter? = null,
    val metadataHtml: String = "",
) {
    companion object {
        fun parse(html: String): NovelWebViewChapterDirectives {
            val document = Jsoup.parse(html)
            val noCache = document.selectFirst("meta#no-cache-marker") != null
            val isVideo = document
                .selectFirst("meta[name=lnreader-chapter-type]")
                ?.attr("content")
                ?.equals("video", ignoreCase = true) == true

            val video = if (isVideo) {
                val mode = if (document.metaContent("lnreader-video-mode").equals("direct", true)) {
                    VideoChapter.Mode.DIRECT
                } else {
                    VideoChapter.Mode.LAZY
                }
                val type = when (document.metaContent("lnreader-video-type").lowercase()) {
                    "m3u8" -> VideoChapter.Type.HLS
                    "video-file" -> VideoChapter.Type.VIDEO_FILE
                    "iframe" -> VideoChapter.Type.IFRAME
                    else -> null
                }
                val url = document.metaContent("lnreader-video-url")
                    .takeIf { mode != VideoChapter.Mode.DIRECT || type != null }
                    ?.takeIf(String::isNotBlank)
                    ?.takeIf { type != VideoChapter.Type.IFRAME || it.toHttpUrlOrNull() != null }

                VideoChapter(
                    mode = mode,
                    type = type,
                    url = url,
                    debug = document.metaContent("lnreader-debug-mode").equals("true", true),
                    playerType = document.metaContent("lnreader-player-type").takeIf(String::isNotBlank),
                    disableProgress = document.selectFirst("meta#lnreader-video-disable-progress") != null,
                )
            } else {
                null
            }

            val metadata = document.select(RECOGNIZED_META_SELECTOR)
            if (video?.mode == VideoChapter.Mode.DIRECT && video.type != null && video.url == null) {
                metadata.removeIf { it.attr("name").equals("lnreader-video-url", ignoreCase = true) }
            }
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
                metadataHtml = metadataHtml,
            )
        }

        private const val RECOGNIZED_META_SELECTOR =
            "meta#no-cache-marker, meta#no-prefetch-marker, " +
                "meta#lnreader-video-disable-progress, " +
                "meta[name=lnreader-chapter-type], meta[name=lnreader-video-mode], " +
                "meta[name=lnreader-video-type], meta[name=lnreader-video-url], " +
                "meta[name=lnreader-debug-mode], meta[name=lnreader-player-type]"
    }
}

internal data class VideoChapter(
    val mode: Mode,
    val type: Type?,
    val url: String?,
    val debug: Boolean,
    val playerType: String?,
    val disableProgress: Boolean,
) {
    enum class Mode { DIRECT, LAZY }

    enum class Type { HLS, VIDEO_FILE, IFRAME }
}

private fun org.jsoup.nodes.Document.metaContent(name: String): String =
    selectFirst("meta[name=$name]")?.attr("content").orEmpty().trim()
