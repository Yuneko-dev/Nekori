package eu.kanade.tachiyomi.discord

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum

enum class DiscordStatus(val wireName: String) {
    ONLINE("online"),
    IDLE("idle"),
    DND("dnd"),
}

class DiscordPreferences(
    preferenceStore: PreferenceStore,
) {
    val enabled: Preference<Boolean> = preferenceStore.getBoolean("discord_rpc_enabled", true)
    val status: Preference<DiscordStatus> = preferenceStore.getEnum("discord_rpc_status", DiscordStatus.IDLE)
    val showAppAndLibrary: Preference<Boolean> = preferenceStore.getBoolean("discord_rpc_app_open", true)
    val showBrowsing: Preference<Boolean> = preferenceStore.getBoolean("discord_rpc_browsing", true)
    val showReading: Preference<Boolean> = preferenceStore.getBoolean("discord_rpc_reading", true)
}
