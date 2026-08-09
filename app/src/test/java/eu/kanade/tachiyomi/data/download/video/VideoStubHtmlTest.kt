package eu.kanade.tachiyomi.data.download.video

import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.LOCAL_VIDEO_BUTTON_ID
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VideoStubHtmlTest {

    @Test
    fun `stub includes titles and opens the downloaded video`() {
        val document = Jsoup.parse(
            createVideoStub(
                localVideoFileName = "video.mp4",
                playLabel = "Play <video>",
                novelTitle = "Novel <title>",
                chapterTitle = "Chapter & title",
            ),
        )
        val button = document.selectFirst("button")

        assertEquals("video.mp4", document.selectFirst("meta[name=lnreader-video-local]")?.attr("content"))
        assertEquals("Novel <title>", document.selectFirst("h2")?.text())
        assertEquals("Chapter & title", document.selectFirst("h4")?.text())
        assertEquals(LOCAL_VIDEO_BUTTON_ID, button?.id())
        assertEquals("Play <video>", button?.text())
        assertEquals(true, button?.hasClass("next-button"))
        assertEquals(true, button?.hasClass("local-video-button"))
    }
}
