package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NovelWebViewChapterDirectivesTest {

    @Test
    fun `markers and direct video metadata are parsed from top level html`() {
        val directives = NovelWebViewChapterDirectives.parse(
            """
                <meta id="no-cache-marker">
                <meta id="no-prefetch-marker">
                <meta name="lnreader-chapter-type" content="video">
                <meta name="lnreader-video-mode" content="direct">
                <meta name="lnreader-video-type" content="m3u8">
                <meta name="lnreader-video-url" content="https://media.example/video.m3u8">
                <meta name="lnreader-debug-mode" content="true">
                <meta name="lnreader-player-type" content="html5">
                <meta id="lnreader-video-disable-progress">
            """.trimIndent(),
        )

        assertTrue(directives.noCache)
        assertFalse(directives.video?.directIframe == true)
        assertTrue(directives.video?.disableProgress == true)
        assertTrue(directives.metadataHtml.contains("no-prefetch-marker"))
        assertTrue(directives.metadataHtml.contains("https://media.example/video.m3u8"))
    }

    @Test
    fun `local video filename is parsed independently of online video fields`() {
        val directives = NovelWebViewChapterDirectives.parse(
            """
                <meta name="lnreader-chapter-type" content="video">
                <meta name="lnreader-video-local" content="video.mkv">
            """.trimIndent(),
        )

        assertEquals("video.mkv", directives.localVideo)
        assertNull(directives.video)
        assertTrue(directives.metadataHtml.contains("lnreader-video-local"))
    }

    @Test
    fun `text chapter has no video directives`() {
        val directives = NovelWebViewChapterDirectives.parse("<p>Chapter text</p>")

        assertFalse(directives.noCache)
        assertNull(directives.video)
        assertNull(directives.localVideo)
        assertEquals("", directives.metadataHtml)
    }

    @Test
    fun `unknown direct video type is retained without a playable type`() {
        val directives = NovelWebViewChapterDirectives.parse(
            """
                <meta name="lnreader-chapter-type" content="video">
                <meta name="lnreader-video-mode" content="direct">
                <meta name="lnreader-video-type" content="unsafe-player">
                <meta name="lnreader-video-url" content="javascript:alert(1)">
            """.trimIndent(),
        )

        assertTrue(directives.video != null)
        assertTrue(directives.metadataHtml.contains("lnreader-video-type"))
    }

    @Test
    fun `direct video with a blank url cannot reach the player`() {
        val directives = NovelWebViewChapterDirectives.parse(
            """
                <meta name="lnreader-chapter-type" content="video">
                <meta name="lnreader-video-mode" content="direct">
                <meta name="lnreader-video-type" content="video-file">
                <meta name="lnreader-video-url" content="">
            """.trimIndent(),
        )

        assertFalse(directives.metadataHtml.contains("lnreader-video-url"))
    }

    @Test
    fun `recognized metadata is rebuilt without unrelated attributes`() {
        val directives = NovelWebViewChapterDirectives.parse(
            """
                <meta name="lnreader-chapter-type" content="video" http-equiv="refresh">
                <meta name="lnreader-video-mode" content="lazy" onload="alert(1)">
            """.trimIndent(),
        )

        assertTrue(directives.metadataHtml.contains("lnreader-chapter-type"))
        assertFalse(directives.metadataHtml.contains("http-equiv"))
        assertFalse(directives.metadataHtml.contains("onload"))
    }

    @Test
    fun `direct iframe only accepts an http url`() {
        val directives = NovelWebViewChapterDirectives.parse(
            """
                <meta name="lnreader-chapter-type" content="video">
                <meta name="lnreader-video-mode" content="direct">
                <meta name="lnreader-video-type" content="iframe">
                <meta name="lnreader-video-url" content="javascript:alert(1)">
            """.trimIndent(),
        )

        assertTrue(directives.video?.directIframe == true)
        assertFalse(directives.metadataHtml.contains("lnreader-video-url"))
    }
}
