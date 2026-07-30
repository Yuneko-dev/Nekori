package eu.kanade.tachiyomi.discord

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

internal object DiscordProtocol {
    const val CLIENT_ID = "1510134118048858142"
    const val REDIRECT_URI = "lnreader:/authorize/callback"
    const val SCOPE = "openid sdk.social_layer sdk.social_layer_presence"
    const val API_BASE = "https://gaming-sdk.com/api"
    const val GATEWAY_URL = "wss://gateway.gaming-sdk.com/?v=9&encoding=json"
    const val USER_AGENT = "Discord Embedded/1.9.15780"
    const val APP_LOGO_ASSET_ID = "1512169205879934986"

    const val OP_DISPATCH = 0
    const val OP_HEARTBEAT = 1
    const val OP_IDENTIFY = 2
    const val OP_PRESENCE_UPDATE = 3
    const val OP_RECONNECT = 7
    const val OP_INVALID_SESSION = 9
    const val OP_HELLO = 10
    const val OP_HEARTBEAT_ACK = 11

    const val DEFAULT_INTENTS =
        (1 shl 12) or
            (1 shl 18) or
            (1 shl 19) or
            (1 shl 22) or
            (1 shl 23) or
            (1 shl 27) or
            (1 shl 28) or
            (1 shl 29)

    val nonResumableCloseCodes = setOf(4004, 4010, 4011, 4012, 4013, 4014)

    val superProperties = buildJsonObject {
        put("browser", "Discord Embedded")
        put("browser_user_agent", USER_AGENT)
        put("browser_version", "1.9.15780")
        put("client_build_number", 15780)
        put("client_version", "1.9.15780")
        put("design_id", 0)
        put("device", "console")
        put("native_build_number", 15780)
        put("os", "Android")
        put("release_channel", "unknown")
    }

    fun superPropertiesHeader(json: Json): String = Base64.getEncoder().encodeToString(
        json.encodeToString(JsonObject.serializer(), superProperties).toByteArray(Charsets.US_ASCII),
    )

    fun newVerifier(byteCount: Int = 32): String = randomBase64Url(byteCount)

    fun newState(): String = randomBase64Url(16)

    fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
    )

    fun validateCallback(
        expectedState: String,
        returnedState: String?,
        code: String?,
        error: String?,
        errorDescription: String?,
    ): String {
        if (error != null) throw IllegalArgumentException("$error: ${errorDescription.orEmpty()}")
        require(returnedState == expectedState) { "Invalid Discord OAuth state" }
        return requireNotNull(code) { "Missing Discord authorization code" }
    }

    private fun randomBase64Url(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

@Serializable
internal data class DiscordToken(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("refresh_token") val refreshToken: String,
    val scope: String,
    @SerialName("id_token") val idToken: String? = null,
    @SerialName("expires_at") val expiresAt: Long = 0,
) {
    fun withAbsoluteExpiry(now: Long = System.currentTimeMillis()) = copy(
        expiresAt = now + expiresIn * 1_000,
    )

    fun authorizationHeader() = "$tokenType $accessToken"
}

@Serializable
data class DiscordProfile(
    val id: String,
    val username: String,
    @SerialName("global_name") val globalName: String? = null,
    val avatar: String? = null,
    val banner: String? = null,
    @SerialName("accent_color") val accentColor: Int? = null,
) {
    val avatarUrl: String
        get() = if (avatar != null) {
            "https://cdn.discordapp.com/avatars/$id/$avatar.png?size=256"
        } else {
            val index = ((id.toULongOrNull() ?: 0u) shr 22).toInt() % 6
            "https://cdn.discordapp.com/embed/avatars/$index.png"
        }

    val bannerUrl: String?
        get() = banner?.let { "https://cdn.discordapp.com/banners/$id/$it.png?size=512" }
}

@Serializable
internal data class PendingDiscordAuth(
    val verifier: String,
    val state: String,
    val createdAt: Long,
)

sealed interface DiscordAuthState {
    data object Loading : DiscordAuthState
    data object Disconnected : DiscordAuthState
    data object Authorizing : DiscordAuthState
    data class Connected(val profile: DiscordProfile) : DiscordAuthState
    data class Error(val message: String) : DiscordAuthState
}

internal data class DiscordPresence(
    val sessionId: String,
    val details: String? = null,
    val state: String? = null,
    val largeImage: String? = null,
    val largeText: String? = null,
    val smallImage: String? = null,
    val smallText: String? = null,
    val buttons: List<Pair<String, String>> = emptyList(),
    val startedAt: Long,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("name", "Tsundoku")
        put("type", 3)
        put("application_id", DiscordProtocol.CLIENT_ID)
        formatRpcString(state)?.let { put("state", it) }
        formatRpcString(details)?.let { put("details", it) }
        put("timestamps", buildJsonObject { put("start", startedAt) })
        put("created_at", System.currentTimeMillis())
        put("session_id", sessionId)

        val validButtons = buttons.take(2).mapNotNull { (name, url) ->
            url.takeIf(::isHttpUrl)?.let { formatRpcString(name, 32).orEmpty() to it }
        }
        if (validButtons.isNotEmpty()) {
            put("buttons", JsonArray(validButtons.map { JsonPrimitive(it.first) }))
            put(
                "metadata",
                buildJsonObject {
                    put(
                        "button_urls",
                        buildJsonArray { validButtons.forEach { add(JsonPrimitive(it.second)) } },
                    )
                },
            )
        }

        if (listOf(largeImage, largeText, smallImage, smallText).any { !it.isNullOrBlank() }) {
            put(
                "assets",
                buildJsonObject {
                    normalizeDiscordImage(largeImage)?.let { put("large_image", it) }
                    formatRpcString(largeText)?.let { put("large_text", it) }
                    normalizeDiscordImage(smallImage)?.let { put("small_image", it) }
                    formatRpcString(smallText)?.let { put("small_text", it) }
                },
            )
        }
    }
}

internal fun formatRpcString(value: String?, maxLength: Int = 128): String? {
    val trimmed = value?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    if (trimmed.length < 2) return "$trimmed\u200B"
    if (trimmed.length <= maxLength) return trimmed
    return if (maxLength <= 3) trimmed.take(maxLength) else trimmed.take(maxLength - 3) + "..."
}

internal fun isHttpUrl(value: String): Boolean = runCatching {
    val url = java.net.URI(value)
    url.scheme == "http" || url.scheme == "https"
}.getOrDefault(false)

internal fun normalizeDiscordImage(value: String?): String? {
    if (value.isNullOrBlank()) return null
    return when {
        value.startsWith("mp:") -> value
        value.startsWith("external/") -> "mp:$value"
        value.matches(Regex("""\d{17,19}""")) -> value
        value.startsWith("https://cdn.discordapp.com/") -> value.replace(
            "https://cdn.discordapp.com/",
            "mp:",
        )
        value.startsWith("https://media.discordapp.net/") -> value.replace(
            "https://media.discordapp.net/",
            "mp:",
        )
        else -> value
    }
}
