package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

internal data class ProtectedMediaOrigin(
    val scheme: String,
    val host: String,
    val port: Int,
)

internal fun protectedMediaOrigin(scheme: String?, host: String?, port: Int): ProtectedMediaOrigin? {
    val normalizedScheme = scheme?.lowercase() ?: return null
    val normalizedHost = host?.lowercase() ?: return null
    val normalizedPort = when {
        port >= 0 -> port
        normalizedScheme == "https" -> 443
        normalizedScheme == "http" -> 80
        else -> -1
    }
    return ProtectedMediaOrigin(normalizedScheme, normalizedHost, normalizedPort)
}

internal fun canGrantProtectedMediaPlayback(
    armed: Boolean,
    requestOrigin: ProtectedMediaOrigin?,
    documentOrigin: ProtectedMediaOrigin?,
    resources: List<String>,
    protectedMediaResource: String,
): Boolean =
    armed &&
        requestOrigin != null &&
        requestOrigin == documentOrigin &&
        resources == listOf(protectedMediaResource)
