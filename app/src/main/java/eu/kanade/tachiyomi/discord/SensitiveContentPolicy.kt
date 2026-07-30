package eu.kanade.tachiyomi.discord

import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.jsplugin.JsPluginManager

class SensitiveContentPolicy(
    private val basePreferences: BasePreferences,
    private val securityPreferences: SecurityPreferences,
    private val pluginManager: JsPluginManager,
) {
    enum class Action {
        READING_PROGRESS,
        READING_HISTORY,
        DISCORD_RPC,
    }

    fun isBlocked(action: Action, sourceId: Long?): Boolean {
        if (basePreferences.incognitoMode.get()) return true

        return when (pluginManager.contentWarningForSource(sourceId)) {
            CONTENT_WARNING_MIXED -> when (action) {
                Action.READING_PROGRESS -> securityPreferences.mixedBlockReadingProgress.get()
                Action.READING_HISTORY -> securityPreferences.mixedBlockReadingHistory.get()
                Action.DISCORD_RPC -> securityPreferences.mixedBlockDiscordRpc.get()
            }
            CONTENT_WARNING_NSFW -> when (action) {
                Action.READING_PROGRESS -> securityPreferences.nsfwBlockReadingProgress.get()
                Action.READING_HISTORY -> securityPreferences.nsfwBlockReadingHistory.get()
                Action.DISCORD_RPC -> securityPreferences.nsfwBlockDiscordRpc.get()
            }
            else -> false
        }
    }

    private companion object {
        const val CONTENT_WARNING_MIXED = 2
        const val CONTENT_WARNING_NSFW = 3
    }
}
