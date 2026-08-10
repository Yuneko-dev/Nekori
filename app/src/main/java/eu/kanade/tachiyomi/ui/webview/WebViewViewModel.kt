package eu.kanade.tachiyomi.ui.webview

import android.content.Context
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import eu.kanade.presentation.more.stats.StatsScreenState
import eu.kanade.tachiyomi.jsplugin.source.JsSource
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import logcat.LogPriority
import mihon.core.viewmodel.StateViewModel
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class WebViewViewModel(
    val sourceId: Long?,
    private val sourceManager: SourceManager = Injekt.get(),
    private val network: NetworkHelper = Injekt.get(),
) : StateViewModel<StatsScreenState>(StatsScreenState.Loading) {

    companion object {
        val SOURCE_ID_KEY = CreationExtras.Key<Long?>()

        val Factory = viewModelFactory {
            initializer {
                WebViewViewModel(
                    sourceId = get(SOURCE_ID_KEY),
                )
            }
        }
    }

    private val source = sourceId?.let(sourceManager::get)

    var headers = emptyMap<String, String>()

    init {
        (source as? HttpSource)?.let { source ->
            try {
                headers = source.headers.toMultimap().mapValues { it.value.getOrNull(0) ?: "" }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to build headers" }
            }
        }
        headers = headers.withDefaultUserAgent(network.defaultUserAgentProvider())
    }

    suspend fun usesPluginWebStorage(): Boolean =
        (source as? JsSource)?.usesWebStorage() == true

    fun savePluginWebStorage(snapshotJson: String) {
        (source as? JsSource)?.saveWebStorageSnapshot(snapshotJson)
    }

    fun shareWebpage(context: Context, url: String) {
        try {
            context.startActivity(url.toUri().toShareIntent(context, type = "text/plain"))
        } catch (e: Exception) {
            context.toast(e.message)
        }
    }

    fun openInBrowser(context: Context, url: String) {
        context.openInBrowser(url, forceDefaultBrowser = true)
    }

    fun clearCookies(url: String) {
        url.toHttpUrlOrNull()?.let {
            val cleared = network.cookieJar.remove(it)
            logcat { "Cleared $cleared cookies for: $url" }
        }
    }

    fun defaultUserAgentProvider() = network.defaultUserAgentProvider()
}

internal fun Map<String, String>.withDefaultUserAgent(userAgent: String): Map<String, String> =
    if (keys.any { it.equals("user-agent", ignoreCase = true) }) {
        this
    } else {
        this + ("user-agent" to userAgent)
    }
