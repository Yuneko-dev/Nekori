package eu.kanade.tachiyomi.jsplugin.model

import kotlinx.serialization.Serializable
import java.net.URI

/**
 * Represents a JS plugin from LNReader-compatible repositories.
 * Maps directly to the plugin index JSON format.
 *
 * Also decoded straight from what `plugin.load` reports, which is the authority on the fields the
 * code owns. That answer carries no [url] or [iconUrl] - where to download a plugin from and what
 * icon to show it with belong to the repository listing, not to the plugin - hence their defaults.
 */
@Serializable
data class JsPlugin(
    val id: String,
    val name: String,
    val site: String,
    val lang: String,
    val version: String,
    val url: String = "",
    val iconUrl: String = "",
    val customCSS: String? = null,
    val customJS: String? = null,
    val customCSSFile: String? = null,
    val customJSFile: String? = null,
    val contentWarning: Int? = null,
    val contentType: String? = null,
    var repositoryUrl: String? = null,
) {
    companion object {
        /** Package name prefix for novel JS plugins - unique to tsundoku fork */
        const val PKG_PREFIX = "app.tsundoku.jsplugin."

        const val CONTENT_WARNING_SAFE = 1

        private const val CONTENT_TYPE_IMAGE = "image"
        private const val CONTENT_TYPE_VIDEO = "video"
        private const val CONTENT_TYPE_MIXED = "mixed"
    }

    fun displayName(): String = when (contentType) {
        CONTENT_TYPE_VIDEO -> "📺 $name"
        CONTENT_TYPE_IMAGE -> "🖼️ $name"
        CONTENT_TYPE_MIXED -> "🧭 $name"
        else -> name
    }

    fun hasAdultContentWarning(): Boolean = contentWarning in (CONTENT_WARNING_SAFE + 1)..3

    fun allowsInfiniteScroll(): Boolean = contentType != CONTENT_TYPE_VIDEO && contentType != CONTENT_TYPE_MIXED

    /**
     * Unique identifier combining plugin ID and repository URL for disambiguation
     */
    fun uniqueId(): String = "js:$id"

    /**
     * Unique package name for this plugin - prevents conflicts with other forks
     */
    fun pkgName(): String = "${PKG_PREFIX}$id"

    /**
     * Generate a stable Long ID for Source compatibility
     */
    fun sourceId(): Long {
        // Use same hashing approach as HttpSource for consistency
        val key = "${name.lowercase()}/$lang/js"
        return key.hashCode().toLong() and Long.MAX_VALUE
    }

    /**
     * Normalized language code for grouping (e.g., "English" -> "en")
     */
    fun langCode(): String = when {
        lang.contains("English", ignoreCase = true) -> "en"
        lang.contains("中文") || lang.contains("Chinese", ignoreCase = true) -> "zh"
        lang.contains("日本") || lang.contains("Japanese", ignoreCase = true) -> "ja"
        lang.contains("한국") || lang.contains("Korean", ignoreCase = true) -> "ko"
        lang.contains("Français", ignoreCase = true) -> "fr"
        lang.contains("Español", ignoreCase = true) -> "es"
        lang.contains("Português", ignoreCase = true) -> "pt"
        lang.contains("Русский", ignoreCase = true) -> "ru"
        lang.contains("Indonesia", ignoreCase = true) -> "id"
        lang.contains("Türkçe", ignoreCase = true) -> "tr"
        lang.contains("العربية") -> "ar"
        lang.contains("ไทย") -> "th"
        lang.contains("Việt", ignoreCase = true) -> "vi"
        lang.contains("Polski", ignoreCase = true) -> "pl"
        lang.contains("Українська", ignoreCase = true) -> "uk"
        lang.contains("Multi", ignoreCase = true) -> "all"
        else -> "other"
    }
}

/**
 * Represents a JS plugin repository
 */
@Serializable
data class JsPluginRepository(
    val name: String,
    val url: String,
    val enabled: Boolean = true,
) {
    companion object {
        fun nameFromUrl(url: String): String {
            val trimmed = url.trim()
            val uri = runCatching { URI(trimmed) }.getOrNull()
            val segments = uri?.path.orEmpty()
                .split('/')
                .filter(String::isNotBlank)

            return segments.take(2)
                .joinToString("/")
                .removeSuffix(".git")
                .ifBlank { uri?.host ?: trimmed }
        }
    }
}

/**
 * Installed JS plugin with cached code
 */
data class InstalledJsPlugin(
    val plugin: JsPlugin,
    val code: String,
    val installedVersion: String,
    val repositoryUrl: String,
    val customCSS: String = "",
    val customJS: String = "",
)
