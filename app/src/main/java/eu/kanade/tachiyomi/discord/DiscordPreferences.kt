package eu.kanade.tachiyomi.discord

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

class DiscordPreferences(
    preferenceStore: PreferenceStore,
) {
    val enabled: Preference<Boolean> = preferenceStore.getBoolean("discord_rpc_enabled", true)
    val showAppAndLibrary: Preference<Boolean> = preferenceStore.getBoolean("discord_rpc_app_open", true)
    val showBrowsing: Preference<Boolean> = preferenceStore.getBoolean("discord_rpc_browsing", true)
    val showReading: Preference<Boolean> = preferenceStore.getBoolean("discord_rpc_reading", true)
}
