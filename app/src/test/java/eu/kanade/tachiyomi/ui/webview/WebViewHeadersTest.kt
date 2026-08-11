package eu.kanade.tachiyomi.ui.webview

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WebViewHeadersTest {

    @Test
    fun `app user agent replaces any source override`() {
        assertEquals(
            mapOf("Referer" to "https://example.com", "User-Agent" to "NetworkHelper UA"),
            mapOf(
                "Referer" to "https://example.com",
                "user-agent" to "Source UA",
            ).withDefaultUserAgent("NetworkHelper UA"),
        )
    }
}
