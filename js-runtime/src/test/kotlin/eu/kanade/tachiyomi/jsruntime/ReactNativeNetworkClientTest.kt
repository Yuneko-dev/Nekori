package eu.kanade.tachiyomi.jsruntime

import com.facebook.react.modules.network.OkHttpClientProvider
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ReactNativeNetworkClientTest {

    @Test
    fun `installs the exact client once`() {
        val client = OkHttpClient()

        ReactNativeNetworkClient.install(client)
        ReactNativeNetworkClient.install(client)

        assertSame(client, OkHttpClientProvider.getOkHttpClient())
        assertThrows(IllegalStateException::class.java) {
            ReactNativeNetworkClient.install(OkHttpClient())
        }
    }
}
