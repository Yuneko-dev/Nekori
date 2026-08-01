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
        assertEquals(VideoChapter.Mode.DIRECT, directives.video?.mode)
        assertEquals(VideoChapter.Type.HLS, directives.video?.type)
        assertEquals("https://media.example/video.m3u8", directives.video?.url)
        assertTrue(directives.video?.debug == true)
        assertEquals("html5", directives.video?.playerType)
        assertTrue(directives.video?.disableProgress == true)
        assertTrue(directives.metadataHtml.contains("no-prefetch-marker"))
    }

    @Test
    fun `text chapter has no video directives`() {
        val directives = NovelWebViewChapterDirectives.parse("<p>Chapter text</p>")

        assertFalse(directives.noCache)
        assertNull(directives.video)
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

        assertEquals(VideoChapter.Mode.DIRECT, directives.video?.mode)
        assertNull(directives.video?.type)
        assertNull(directives.video?.url)
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

        assertNull(directives.video?.url)
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

        assertNull(directives.video?.url)
        assertFalse(directives.metadataHtml.contains("lnreader-video-url"))
    }
}
