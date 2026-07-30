package eu.kanade.tachiyomi.discord

import eu.kanade.domain.base.BasePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import logcat.LogPriority
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class DiscordRpcManager internal constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val scope: CoroutineScope,
    private val auth: DiscordAuth,
    private val preferences: DiscordPreferences,
    private val basePreferences: BasePreferences,
    private val sensitiveContentPolicy: SensitiveContentPolicy,
) {
    private var gateway: DiscordGateway? = null
    private var connecting = false
    private var ready = false
    private var sessionId = ""
    private var accessToken = ""
    private var reconnectJob: Job? = null
    private var throttleJob: Job? = null
    private var pendingActivity: JsonObject? = null
    private var hasPendingActivity = false
    private var currentActivity: JsonObject? = null
    private var lastPayloadAt = 0L
    private val requestId = AtomicLong()
    private val connectionId = AtomicLong()
    private val externalAssets = ConcurrentHashMap<String, String>()
    private val appStartedAt = System.currentTimeMillis()

    fun initialize() {
        auth.initialize()
        scope.launch {
            combine(
                auth.state,
                preferences.enabled.changesWithInitial(),
                basePreferences.incognitoMode.changesWithInitial(),
            ) { authState, enabled, incognito -> Triple(authState, enabled, incognito) }
                .collect { (authState, enabled, incognito) ->
                    when {
                        !enabled || incognito -> {
                            clearActivity()
                            disconnect()
                        }
                        authState is DiscordAuthState.Connected -> connect()
                        else -> disconnect()
                    }
                }
        }
    }

    fun connect() {
        if (gateway != null || connecting || !preferences.enabled.get() || basePreferences.incognitoMode.get()) return
        val attemptId = connectionId.incrementAndGet()
        connecting = true
        scope.launchIO {
            val token = auth.validToken()
            if (
                token == null ||
                attemptId != connectionId.get() ||
                !preferences.enabled.get() ||
                basePreferences.incognitoMode.get()
            ) {
                if (attemptId == connectionId.get()) connecting = false
                return@launchIO
            }
            accessToken = token.accessToken
            val instance = DiscordGateway(client, json, scope, gatewayListener(attemptId))
            gateway = instance
            if (!instance.connect(token.authorizationHeader())) {
                if (attemptId == connectionId.get()) gateway = null
                if (attemptId == connectionId.get()) connecting = false
                return@launchIO
            }
            if (attemptId != connectionId.get()) {
                instance.close()
                return@launchIO
            }
            if (attemptId == connectionId.get()) connecting = false
        }
    }

    fun disconnect(clearActivity: Boolean = false) {
        connectionId.incrementAndGet()
        reconnectJob?.cancel()
        reconnectJob = null
        throttleJob?.cancel()
        throttleJob = null
        pendingActivity = null
        hasPendingActivity = false
        if (clearActivity && ready) gateway?.sendPresence(null)
        gateway?.close()
        gateway = null
        ready = false
        connecting = false
        sessionId = ""
    }

    fun showApp() = publishSimple(
        enabled = preferences.showAppAndLibrary.get(),
        state = "Browsing App",
    )

    fun showLibrary() = publishSimple(
        enabled = preferences.showAppAndLibrary.get(),
        state = "Browsing Library",
    )

    fun showSource(
        sourceId: Long,
        sourceName: String,
        sourceUrl: String? = null,
        sourceIcon: String? = null,
    ) {
        publishAsync(sourceId, preferences.showBrowsing.get()) { id ->
            val icon = resolveExternalAsset(sourceIcon)
            if (id != requestId.get()) return@publishAsync null
            basePresence(
                details = "Browsing Source",
                state = sourceName,
                largeImage = DiscordProtocol.APP_LOGO_ASSET_ID,
                largeText = "Tsundoku",
                smallImage = icon,
                smallText = sourceName,
                action = sourceUrl?.let { "View source" to it },
            )
        }
    }

    fun showNovel(
        sourceId: Long,
        novelName: String,
        cover: String? = null,
        novelUrl: String? = null,
    ) {
        publishAsync(sourceId, preferences.showReading.get()) { id ->
            val resolvedCover = resolveExternalAsset(cover)
            if (id != requestId.get()) return@publishAsync null
            basePresence(
                details = "Browsing Novel",
                state = novelName,
                largeImage = resolvedCover,
                largeText = novelName,
                smallImage = DiscordProtocol.APP_LOGO_ASSET_ID,
                smallText = "Tsundoku",
                action = novelUrl?.let { "View novel" to it },
            )
        }
    }

    fun showChapter(
        sourceId: Long,
        novelName: String,
        chapterName: String,
        cover: String? = null,
        chapterUrl: String? = null,
        chapterPage: String? = null,
    ) {
        publishAsync(sourceId, preferences.showReading.get()) { id ->
            val resolvedCover = resolveExternalAsset(cover)
            if (id != requestId.get()) return@publishAsync null
            basePresence(
                details = novelName,
                state = "Reading: $chapterName",
                largeImage = resolvedCover,
                largeText = chapterPage?.let { "[$it]: $chapterName" } ?: chapterName,
                smallImage = DiscordProtocol.APP_LOGO_ASSET_ID,
                smallText = "Tsundoku",
                action = chapterUrl?.let { "Read chapter" to it },
            )
        }
    }

    fun clearActivity() {
        requestId.incrementAndGet()
        throttleJob?.cancel()
        throttleJob = null
        currentActivity = null
        pendingActivity = null
        hasPendingActivity = false
        if (ready) gateway?.sendPresence(null)
    }

    private fun publishSimple(enabled: Boolean, state: String) {
        if (!canPublish(enabled, null)) {
            clearActivity()
            return
        }
        requestId.incrementAndGet()
        setActivity(
            basePresence(
                state = state,
                largeImage = DiscordProtocol.APP_LOGO_ASSET_ID,
                largeText = "Tsundoku",
            ),
        )
    }

    private fun publishAsync(
        sourceId: Long,
        enabled: Boolean,
        factory: suspend (Long) -> JsonObject?,
    ) {
        if (!canPublish(enabled, sourceId)) {
            clearActivity()
            return
        }
        val id = requestId.incrementAndGet()
        scope.launchIO {
            factory(id)?.takeIf { id == requestId.get() }?.let(::setActivity)
        }
    }

    private fun canPublish(enabled: Boolean, sourceId: Long?): Boolean {
        return preferences.enabled.get() &&
            enabled &&
            !sensitiveContentPolicy.isBlocked(SensitiveContentPolicy.Action.DISCORD_RPC, sourceId)
    }

    private fun basePresence(
        details: String? = null,
        state: String? = null,
        largeImage: String? = null,
        largeText: String? = null,
        smallImage: String? = null,
        smallText: String? = null,
        action: Pair<String, String>? = null,
    ): JsonObject {
        val buttons = buildList {
            add("Read on Tsundoku" to "https://github.com/tsundoku-otaku")
            action?.takeIf { isHttpUrl(it.second) }?.let(::add)
        }
        return DiscordPresence(
            sessionId = sessionId,
            details = details,
            state = state,
            largeImage = largeImage,
            largeText = largeText,
            smallImage = smallImage,
            smallText = smallText,
            buttons = buttons,
            startedAt = appStartedAt,
        ).toJson()
    }

    @Synchronized
    private fun setActivity(activity: JsonObject?) {
        currentActivity = activity
        val remaining = THROTTLE_MS - (System.currentTimeMillis() - lastPayloadAt)
        if (remaining <= 0 && ready) {
            throttleJob?.cancel()
            throttleJob = null
            pendingActivity = null
            hasPendingActivity = false
            send(activity)
            return
        }
        pendingActivity = activity
        hasPendingActivity = true
        if (throttleJob == null) {
            throttleJob = scope.launch {
                delay(remaining.coerceAtLeast(0))
                synchronized(this@DiscordRpcManager) {
                    if (hasPendingActivity && ready) send(pendingActivity)
                    pendingActivity = null
                    hasPendingActivity = false
                    throttleJob = null
                }
            }
        }
    }

    private fun send(activity: JsonObject?) {
        val activityWithSession = activity?.let {
            JsonObject(it + ("session_id" to JsonPrimitive(sessionId)))
        }
        gateway?.sendPresence(activityWithSession)
        lastPayloadAt = System.currentTimeMillis()
    }

    private suspend fun resolveExternalAsset(image: String?): String? {
        if (image.isNullOrBlank()) return null
        externalAssets[image]?.let { return it }
        if (accessToken.isBlank()) return null

        return runCatching {
            val publicImage = if (image.startsWith("file://")) uploadLocalCover(image) else image
            if (!isHttpUrl(publicImage)) return null
            val body = buildJsonObject {
                put("urls", JsonArray(listOf(JsonPrimitive(publicImage))))
            }.toString().toRequestBody(JSON_MEDIA_TYPE)
            val response = client.newCall(
                Request.Builder()
                    .url("${DiscordProtocol.API_BASE}/applications/${DiscordProtocol.CLIENT_ID}/external-assets")
                    .header("Authorization", "Bearer $accessToken")
                    .header("User-Agent", DiscordProtocol.USER_AGENT)
                    .header("X-Super-Properties", DiscordProtocol.superPropertiesHeader(json))
                    .post(body)
                    .build(),
            ).execute()
            response.use {
                check(it.isSuccessful) { "External asset failed: ${it.code}" }
                json.parseToJsonElement(it.body.string())
                    .jsonArray
                    .firstOrNull()
                    ?.jsonObject
                    ?.get("external_asset_path")
                    ?.jsonPrimitive
                    ?.content
                    ?.also { path -> externalAssets[image] = path }
            }
        }.onFailure {
            logcat(LogPriority.WARN, it) { "Discord external asset failed" }
        }.getOrNull()
    }

    private fun uploadLocalCover(image: String): String {
        val file = File(URI(image))
        check(file.isFile) { "Local Discord cover does not exist" }
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("reqtype", "fileupload")
            .addFormDataPart("time", "24h")
            .addFormDataPart("fileNameLength", "16")
            .addFormDataPart("fileToUpload", file.name, file.asRequestBody(OCTET_STREAM))
            .build()
        val response = client.newCall(
            Request.Builder()
                .url("https://litterbox.catbox.moe/resources/internals/api.php")
                .header("User-Agent", "Tsundoku (https://github.com/tsundoku-otaku)")
                .post(body)
                .build(),
        ).execute()
        return response.use {
            val url = it.body.string().trim()
            check(it.isSuccessful && url.startsWith("https://litter.catbox.moe/")) {
                "Local Discord cover upload failed: ${it.code}"
            }
            url
        }
    }

    private fun gatewayListener(attemptId: Long) = object : DiscordGateway.Listener {
        override fun onReady(sessionId: String) {
            if (attemptId != connectionId.get()) return
            this@DiscordRpcManager.sessionId = sessionId
            ready = true
            if (hasPendingActivity) {
                val pending = pendingActivity
                throttleJob?.cancel()
                throttleJob = null
                pendingActivity = null
                hasPendingActivity = false
                send(pending)
            } else {
                currentActivity?.let(::send)
            }
        }

        override fun onClosed(code: Int, reason: String) {
            if (attemptId != connectionId.get()) return
            ready = false
            gateway = null
            if (
                code != 1000 &&
                code !in DiscordProtocol.nonResumableCloseCodes &&
                preferences.enabled.get() &&
                !basePreferences.incognitoMode.get()
            ) {
                reconnectJob?.cancel()
                reconnectJob = scope.launch {
                    delay(RECONNECT_MS)
                    connect()
                }
            }
        }

        override fun onFailure(error: Throwable) {
            if (attemptId != connectionId.get()) return
            logcat(LogPriority.ERROR, error) { "Discord gateway failed" }
        }
    }

    private companion object {
        const val THROTTLE_MS = 5_000L
        const val RECONNECT_MS = 1_000L
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        val OCTET_STREAM = "application/octet-stream".toMediaType()
    }
}

private fun <T> Preference<T>.changesWithInitial() = changes()
