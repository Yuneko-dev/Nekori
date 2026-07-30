package eu.kanade.tachiyomi.core.security

import dev.icerock.moko.resources.StringResource
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum
import tachiyomi.i18n.MR

class SecurityPreferences(
    preferenceStore: PreferenceStore,
) {

    val useAuthenticator: Preference<Boolean> = preferenceStore.getBoolean("use_biometric_lock", false)

    val lockAppAfter: Preference<Int> = preferenceStore.getInt("lock_app_after", 0)

    val secureScreen: Preference<SecureScreenMode> = preferenceStore.getEnum(
        "secure_screen_v2",
        SecureScreenMode.INCOGNITO,
    )

    val hideNotificationContent: Preference<Boolean> = preferenceStore.getBoolean("hide_notification_content", false)

    val mixedBlockReadingProgress = preferenceStore.getBoolean("mixed_block_reading_progress", false)
    val mixedBlockReadingHistory = preferenceStore.getBoolean("mixed_block_reading_history", false)
    val mixedBlockDiscordRpc = preferenceStore.getBoolean("mixed_block_discord_rpc", false)

    val nsfwBlockReadingProgress = preferenceStore.getBoolean("nsfw_block_reading_progress", false)
    val nsfwBlockReadingHistory = preferenceStore.getBoolean("nsfw_block_reading_history", false)
    val nsfwBlockDiscordRpc = preferenceStore.getBoolean("nsfw_block_discord_rpc", true)

    /**
     * For app lock. Will be set when there is a pending timed lock.
     * Otherwise, this pref should be deleted.
     */
    val lastAppClosed: Preference<Long> = preferenceStore.getLong(
        Preference.appStateKey("last_app_closed"),
        0,
    )

    enum class SecureScreenMode(val titleRes: StringResource) {
        ALWAYS(MR.strings.lock_always),
        INCOGNITO(MR.strings.pref_incognito_mode),
        NEVER(MR.strings.lock_never),
    }
}
