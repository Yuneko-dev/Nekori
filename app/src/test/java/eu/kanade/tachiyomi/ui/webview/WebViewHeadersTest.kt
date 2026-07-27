package eu.kanade.tachiyomi.ui.webview

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WebViewHeadersTest {

    @Test
    fun `default user agent fills missing value without replacing source override`() {
        assertEquals(
            "NetworkHelper UA",
            emptyMap<String, String>().withDefaultUserAgent("NetworkHelper UA")["user-agent"],
        )
        assertEquals(
            "Source UA",
            mapOf("User-Agent" to "Source UA").withDefaultUserAgent("NetworkHelper UA")["User-Agent"],
        )
    }
}
