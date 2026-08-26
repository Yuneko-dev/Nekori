package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import eu.kanade.presentation.util.LocalBackPress
import eu.kanade.tachiyomi.discord.DiscordAuth
import eu.kanade.tachiyomi.discord.DiscordAuthState
import eu.kanade.tachiyomi.discord.DiscordPreferences
import eu.kanade.tachiyomi.discord.DiscordProfile
import eu.kanade.tachiyomi.discord.DiscordRpcManager
import kotlinx.coroutines.launch
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object SettingsDiscordScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = TDMR.strings.pref_category_discord

    @Composable
    override fun Content() {
        val auth = remember { Injekt.get<DiscordAuth>() }
        val authState by auth.state.collectAsState()
        if (authState is DiscordAuthState.Connected) {
            super<SearchableSettings>.Content()
            return
        }

        val backPress = LocalBackPress.currentOrThrow
        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(TDMR.strings.pref_category_discord),
                    navigateUp = backPress::invoke,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { padding ->
            when (val state = authState) {
                DiscordAuthState.Loading -> LoadingContent(padding)
                DiscordAuthState.Disconnected,
                is DiscordAuthState.Error,
                -> LoggedOutContent(
                    padding = padding,
                    error = (state as? DiscordAuthState.Error)?.message,
                    onLogin = auth::startLogin,
                )
                DiscordAuthState.Authorizing -> LoadingContent(
                    padding = padding,
                    message = stringResource(TDMR.strings.discord_authorizing),
                )
                is DiscordAuthState.Connected -> Unit
            }
        }
    }

    @Composable
    override fun getPreferences(): List<Preference> {
        val auth = remember { Injekt.get<DiscordAuth>() }
        val authState by auth.state.collectAsState()
        val profile = (authState as? DiscordAuthState.Connected)?.profile ?: return emptyList()
        val rpc = remember { Injekt.get<DiscordRpcManager>() }
        val preferences = remember { Injekt.get<DiscordPreferences>() }
        val enabled by preferences.enabled.changes().collectAsState(preferences.enabled.get())
        val showApp by preferences.showAppAndLibrary.changes().collectAsState(preferences.showAppAndLibrary.get())
        val showBrowsing by preferences.showBrowsing.changes().collectAsState(preferences.showBrowsing.get())
        val showReading by preferences.showReading.changes().collectAsState(preferences.showReading.get())
        val scope = rememberCoroutineScope()

        return listOf(
            Preference.PreferenceItem.CustomPreference(
                title = stringResource(TDMR.strings.discord_connected_as, "@${profile.username}"),
                content = {
                    ProfileCard(profile) {
                        scope.launch {
                            rpc.disconnect(clearActivity = true)
                            auth.logout()
                        }
                    }
                },
            ),
            Preference.PreferenceGroup(
                title = stringResource(TDMR.strings.discord_rpc_settings),
                preferenceItems = buildList {
                    add(
                        discordSwitchPreference(
                            title = stringResource(TDMR.strings.discord_enable_rpc),
                            subtitle = stringResource(TDMR.strings.discord_enable_rpc_summary),
                            checked = enabled,
                            onCheckedChanged = {
                                preferences.enabled.set(it)
                                if (it) {
                                    rpc.connect()
                                    rpc.showApp()
                                } else {
                                    rpc.clearActivity()
                                    rpc.disconnect()
                                }
                            },
                        ),
                    )
                    if (enabled) {
                        add(
                            discordSwitchPreference(
                                title = stringResource(TDMR.strings.discord_show_app),
                                subtitle = stringResource(TDMR.strings.discord_show_app_summary),
                                checked = showApp,
                                onCheckedChanged = {
                                    preferences.showAppAndLibrary.set(it)
                                    if (it) rpc.showApp() else rpc.clearActivity()
                                },
                            ),
                        )
                        add(
                            discordSwitchPreference(
                                title = stringResource(TDMR.strings.discord_show_browsing),
                                subtitle = stringResource(TDMR.strings.discord_show_browsing_summary),
                                checked = showBrowsing,
                                onCheckedChanged = preferences.showBrowsing::set,
                            ),
                        )
                        add(
                            discordSwitchPreference(
                                title = stringResource(TDMR.strings.discord_show_reading),
                                subtitle = stringResource(TDMR.strings.discord_show_reading_summary),
                                checked = showReading,
                                onCheckedChanged = preferences.showReading::set,
                            ),
                        )
                    }
                },
            ),
        )
    }
}

@Composable
private fun LoadingContent(
    padding: PaddingValues,
    message: String? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            message?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun LoggedOutContent(
    padding: PaddingValues,
    error: String?,
    onLogin: () -> Unit,
) {
    val message = buildString {
        append(stringResource(TDMR.strings.discord_not_connected))
        error?.let {
            append("\n\n")
            append(stringResource(TDMR.strings.discord_error, it))
        }
    }
    EmptyScreen(
        message = message,
        modifier = Modifier.padding(padding),
        actions = listOf(
            EmptyScreenAction(
                stringRes = TDMR.strings.discord_login,
                icon = DiscordIcon,
                onClick = onLogin,
            ),
        ),
    )
}

@Composable
private fun ProfileCard(profile: DiscordProfile, onLogout: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Box {
            val accent = profile.accentColor?.let { Color(0xFF000000 or it.toLong()) }
                ?: MaterialTheme.colorScheme.surfaceVariant
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(104.dp)
                    .background(accent),
            )
            profile.bannerUrl?.let {
                AsyncImage(
                    model = it,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(104.dp),
                    contentScale = ContentScale.Crop,
                )
            }
            AsyncImage(
                model = profile.avatarUrl,
                contentDescription = null,
                modifier = Modifier
                    .padding(start = 16.dp)
                    .offset(y = 64.dp)
                    .size(80.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        }
        Spacer(Modifier.height(48.dp))
        Text(
            text = stringResource(TDMR.strings.discord_connected_as, "@${profile.username}"),
            modifier = Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.titleMedium,
        )
        OutlinedButton(
            onClick = onLogout,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(TDMR.strings.discord_logout),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun discordSwitchPreference(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChanged: (Boolean) -> Unit,
) = Preference.PreferenceItem.CustomPreference(title) {
    SwitchPreferenceWidget(
        title = title,
        subtitle = subtitle,
        checked = checked,
        onCheckedChanged = onCheckedChanged,
    )
}
