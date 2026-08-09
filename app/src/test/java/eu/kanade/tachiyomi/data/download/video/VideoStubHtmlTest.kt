package eu.kanade.tachiyomi.data.download.video

import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.LOCAL_VIDEO_BUTTON_ID
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VideoStubHtmlTest {

    @Test
    fun `stub opens the downloaded video from a styled button`() {
        val document = Jsoup.parse(
            createVideoStub(
                localVideoFileName = "video.mp4",
                playLabel = "Play <video>",
            ),
        )
        val button = document.selectFirst("button")

        assertEquals("video.mp4", document.selectFirst("meta[name=lnreader-video-local]")?.attr("content"))
        assertEquals(LOCAL_VIDEO_BUTTON_ID, button?.id())
        assertEquals("Play <video>", button?.text())
        assertEquals(true, button?.hasClass("next-button"))
        assertEquals(true, button?.hasClass("local-video-button"))
    }
}
