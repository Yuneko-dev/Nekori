package eu.kanade.tachiyomi.data.download

import eu.kanade.tachiyomi.jsruntime.JsRuntimeException
import eu.kanade.tachiyomi.network.HttpException
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DownloaderRateLimitErrorTest {

    @Test
    fun `recognizes rate limited plugin and HTTP 429 errors`() {
        assertTrue(
            Downloader.isRateLimitedNovelDownloadError(
                JsRuntimeException(
                    message = "Slow down",
                    jsStack = "",
                    pluginErrorCode = "Too many requests (429). Please try again later.",
                ),
            ),
        )
        assertTrue(
            Downloader.isRateLimitedNovelDownloadError(
                JsRuntimeException(message = "Retry later", jsStack = "", httpStatus = 429),
            ),
        )
        assertTrue(
            Downloader.isRateLimitedNovelDownloadError(
                JsRuntimeException(message = "Request failed with status 429", jsStack = ""),
            ),
        )
        assertTrue(
            Downloader.isRateLimitedNovelDownloadError(
                RuntimeException("wrapped", HttpException(429)),
            ),
        )
    }

    @Test
    fun `does not classify other errors as rate limited`() {
        assertFalse(
            Downloader.isRateLimitedNovelDownloadError(
                JsRuntimeException(
                    message = "Captcha error, please open in webview",
                    jsStack = "",
                    pluginErrorCode = "Captcha error, please open in webview",
                    httpStatus = 403,
                ),
            ),
        )
        assertFalse(Downloader.isRateLimitedNovelDownloadError(HttpException(503)))
        assertFalse(Downloader.isRateLimitedNovelDownloadError(IllegalStateException("Chapter is empty")))
    }
}
