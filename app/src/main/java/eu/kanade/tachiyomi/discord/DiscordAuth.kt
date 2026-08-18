package eu.kanade.tachiyomi.discord

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.core.common.util.system.logcat

class DiscordAuth internal constructor(
    private val context: Context,
    private val client: OkHttpClient,
    private val json: Json,
    private val scope: CoroutineScope,
    private val store: DiscordSecureStore,
) {
    private val mutableState = MutableStateFlow<DiscordAuthState>(DiscordAuthState.Loading)
    val state: StateFlow<DiscordAuthState> = mutableState.asStateFlow()

    private var timeoutJob: Job? = null

    fun initialize() {
        scope.launch {
            val token = validToken()
            val initializedState = token?.let { loadProfileState(it) } ?: DiscordAuthState.Disconnected
            if (mutableState.value == DiscordAuthState.Loading) {
                mutableState.value = initializedState
            }
        }
    }

    fun startLogin() {
        val verifier = DiscordProtocol.newVerifier()
        val pending = PendingDiscordAuth(
            verifier = verifier,
            state = DiscordProtocol.newState(),
            createdAt = System.currentTimeMillis(),
        )
        store.write(PENDING_KEY, json.encodeToString(pending))
        mutableState.value = DiscordAuthState.Authorizing

        val query = Uri.Builder()
            .appendQueryParameter("client_id", DiscordProtocol.CLIENT_ID)
            .appendQueryParameter("redirect_uri", DiscordProtocol.REDIRECT_URI)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", DiscordProtocol.SCOPE)
            .appendQueryParameter("state", pending.state)
            .appendQueryParameter("code_challenge", DiscordProtocol.challenge(verifier))
            .appendQueryParameter("code_method", "S256")
            .appendQueryParameter("fromAppsFlyer", "false")
            .build()
            .encodedQuery

        val nativeUri = Uri.parse("discord://action/oauth2/authorize?$query")
        val webUri = Uri.parse("https://discord.com/oauth2/authorize?$query")
        val nativeIntent = Intent(Intent.ACTION_VIEW, nativeUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching {
            launchDiscordAuthorization(
                openNative = { context.startActivity(nativeIntent) },
                openBrowser = { openBrowserAuthorization(webUri) },
            )
        }.onFailure {
            store.delete(PENDING_KEY)
            mutableState.value = DiscordAuthState.Error(it.message ?: "Could not open Discord authorization")
            return
        }

        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(AUTH_TIMEOUT_MS)
            val current = readPending()
            if (current?.state == pending.state) {
                store.delete(PENDING_KEY)
                mutableState.value = DiscordAuthState.Error("Timeout waiting for Discord authorization")
            }
        }
    }

    fun handleRedirect(uri: Uri?): Boolean {
        if (uri?.scheme != "nekori" || uri.host != "discord-auth") return false
        mutableState.value = DiscordAuthState.Authorizing
        scope.launch { exchangeRedirect(uri) }
        return true
    }

    internal suspend fun validToken(): DiscordToken? {
        val stored = readToken() ?: return null
        if (System.currentTimeMillis() <= stored.expiresAt - REFRESH_BUFFER_MS) return stored
        return refresh(stored.refreshToken)
    }

    suspend fun logout() {
        timeoutJob?.cancel()
        readToken()?.let { token ->
            runCatching {
                execute(
                    Request.Builder()
                        .url("${DiscordProtocol.API_BASE}/oauth2/token/revoke")
                        .post(
                            FormBody.Builder()
                                .add("client_id", DiscordProtocol.CLIENT_ID)
                                .add("token", token.accessToken)
                                .build(),
                        )
                        .discordHeaders(json)
                        .build(),
                ).close()
            }
        }
        store.delete(TOKEN_KEY)
        store.delete(PENDING_KEY)
        mutableState.value = DiscordAuthState.Disconnected
    }

    private suspend fun exchangeRedirect(uri: Uri) {
        timeoutJob?.cancel()
        val pending = readPending()
        store.delete(PENDING_KEY)
        try {
            check(pending != null && System.currentTimeMillis() - pending.createdAt <= AUTH_TIMEOUT_MS) {
                "Discord authorization request expired"
            }
            val code = DiscordProtocol.validateCallback(
                expectedState = pending.state,
                returnedState = uri.getQueryParameter("state"),
                code = uri.getQueryParameter("code"),
                error = uri.getQueryParameter("error"),
                errorDescription = uri.getQueryParameter("error_description"),
            )
            val token = exchangeCode(code, pending.verifier)
            store.write(TOKEN_KEY, json.encodeToString(token))
            mutableState.value = loadProfileState(token)
        } catch (error: Exception) {
            logcat(LogPriority.ERROR, error) { "Discord OAuth callback failed" }
            mutableState.value = DiscordAuthState.Error(error.message ?: "Discord authorization failed")
        }
    }

    private suspend fun exchangeCode(code: String, verifier: String): DiscordToken {
        val response = execute(
            Request.Builder()
                .url("${DiscordProtocol.API_BASE}/oauth2/token")
                .post(
                    FormBody.Builder()
                        .add("client_id", DiscordProtocol.CLIENT_ID)
                        .add("grant_type", "authorization_code")
                        .add("code", code)
                        .add("code_verifier", verifier)
                        .add("redirect_uri", DiscordProtocol.REDIRECT_URI)
                        .build(),
                )
                .discordHeaders(json)
                .build(),
        )
        return response.use {
            check(it.isSuccessful) { "Token exchange failed: ${it.code} - ${it.body.string()}" }
            json.decodeFromString<DiscordToken>(it.body.string()).withAbsoluteExpiry()
        }
    }

    private suspend fun refresh(refreshToken: String): DiscordToken? {
        return runCatching {
            val response = execute(
                Request.Builder()
                    .url("${DiscordProtocol.API_BASE}/oauth2/token")
                    .post(
                        FormBody.Builder()
                            .add("client_id", DiscordProtocol.CLIENT_ID)
                            .add("grant_type", "refresh_token")
                            .add("refresh_token", refreshToken)
                            .build(),
                    )
                    .discordHeaders(json)
                    .build(),
            )
            response.use {
                if (it.code in 400..499) {
                    store.delete(TOKEN_KEY)
                    mutableState.value = DiscordAuthState.Disconnected
                    return null
                }
                check(it.isSuccessful) { "Token refresh failed: ${it.code}" }
                json.decodeFromString<DiscordToken>(it.body.string()).withAbsoluteExpiry().also { token ->
                    store.write(TOKEN_KEY, json.encodeToString(token))
                }
            }
        }.onFailure {
            logcat(LogPriority.ERROR, it) { "Discord token refresh failed" }
        }.getOrNull()
    }

    private suspend fun loadProfileState(token: DiscordToken): DiscordAuthState {
        return try {
            DiscordAuthState.Connected(loadProfile(token))
        } catch (_: DiscordUnauthorizedException) {
            store.delete(TOKEN_KEY)
            DiscordAuthState.Disconnected
        } catch (error: Exception) {
            error.let {
                logcat(LogPriority.ERROR, it) { "Discord profile load failed" }
                DiscordAuthState.Error(it.message ?: "Could not load Discord profile")
            }
        }
    }

    private suspend fun loadProfile(token: DiscordToken): DiscordProfile {
        val response = execute(
            Request.Builder()
                .url("${DiscordProtocol.API_BASE}/users/@me")
                .header("Authorization", token.authorizationHeader())
                .discordHeaders(json)
                .build(),
        )
        return response.use {
            if (it.code == 401 || it.code == 403) throw DiscordUnauthorizedException()
            check(it.isSuccessful) { "Discord profile failed: ${it.code}" }
            json.decodeFromString(it.body.string())
        }
    }

    private fun readToken(): DiscordToken? {
        val stored = store.read(TOKEN_KEY) ?: return null
        return runCatching { json.decodeFromString<DiscordToken>(stored) }
            .onFailure { store.delete(TOKEN_KEY) }
            .getOrNull()
    }

    private fun readPending(): PendingDiscordAuth? = store.read(PENDING_KEY)?.let {
        runCatching { json.decodeFromString<PendingDiscordAuth>(it) }.getOrNull()
    }

    private suspend fun execute(request: Request) = withContext(Dispatchers.IO) {
        client.newCall(request).execute()
    }

    private fun openBrowserAuthorization(uri: Uri) {
        val customTab = CustomTabsIntent.Builder()
            .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
            .build()
            .apply { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        runCatching { customTab.launchUrl(context, uri) }
            .getOrElse {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
    }

    private companion object {
        const val TOKEN_KEY = "discord_oauth_token"
        const val PENDING_KEY = "discord_oauth_pending"
        const val AUTH_TIMEOUT_MS = 60_000L
        const val REFRESH_BUFFER_MS = 24 * 60 * 60 * 1_000L
    }
}

internal inline fun launchDiscordAuthorization(
    openNative: () -> Unit,
    openBrowser: () -> Unit,
): Boolean {
    return try {
        openNative()
        true
    } catch (_: Exception) {
        openBrowser()
        false
    }
}

private class DiscordUnauthorizedException : Exception()

private fun Request.Builder.discordHeaders(json: Json): Request.Builder = apply {
    header("User-Agent", DiscordProtocol.USER_AGENT)
    header("X-Super-Properties", DiscordProtocol.superPropertiesHeader(json))
}
