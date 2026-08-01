package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import android.webkit.WebView
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NovelWebViewTapPolicyTest {

    @Test
    fun `image taps bypass tap zones and toggle the reader menu`() {
        assertTrue(
            isDirectMenuTapTarget(
                isVideoChapter = false,
                hitTestType = WebView.HitTestResult.IMAGE_TYPE,
            ),
        )
    }
}
