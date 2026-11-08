package eu.kanade.tachiyomi.jsruntime

import com.facebook.react.modules.network.OkHttpClientProvider
import okhttp3.OkHttpClient

internal object ReactNativeNetworkClient {

    private var installed: OkHttpClient? = null

    @Synchronized
    fun install(client: OkHttpClient) {
        check(installed == null || installed === client) {
            "React Native Networking is already bound to a different OkHttpClient"
        }

        if (installed == null) {
            OkHttpClientProvider.setOkHttpClientFactory { client }
            check(OkHttpClientProvider.getOkHttpClient() === client) {
                "React Native Networking initialized before Tsundoku's OkHttpClient was installed"
            }
            installed = client
        }
    }
}
