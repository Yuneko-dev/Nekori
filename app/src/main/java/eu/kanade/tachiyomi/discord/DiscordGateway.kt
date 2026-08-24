/*
 * Kotlin port of the original TypeScript project:
 * https://github.com/aiko-chan-ai/Discord-OAuth2-RPC
 *
 * Copyright (c) 2026 Elysia
 * SPDX-License-Identifier: MIT
 */

package eu.kanade.tachiyomi.discord

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import logcat.LogPriority
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import tachiyomi.core.common.util.system.logcat
import kotlin.random.Random

internal class DiscordGateway(
    private val client: OkHttpClient,
    private val json: Json,
    private val scope: CoroutineScope,
    private val listener: Listener,
) : WebSocketListener() {
    interface Listener {
        fun onReady(sessionId: String)
        fun onClosed(code: Int, reason: String)
        fun onFailure(error: Throwable)
    }

    private var socket: WebSocket? = null
    private var helloTimeout: Job? = null
    private var firstHeartbeat: Job? = null
    private var heartbeatLoop: Job? = null

    @Volatile
    private var acknowledged = true

    @Volatile
    private var closed = false

    private lateinit var token: String

    @Synchronized
    fun connect(token: String): Boolean {
        if (closed || socket != null) return false
        this.token = token
        acknowledged = true
        socket = client.newWebSocket(
            Request.Builder()
                .url(DiscordProtocol.GATEWAY_URL)
                .header("User-Agent", DiscordProtocol.USER_AGENT)
                .header("X-Super-Properties", DiscordProtocol.superPropertiesHeader(json))
                .build(),
            this,
        )
        helloTimeout = scope.launch {
            delay(HELLO_TIMEOUT_MS)
            close(4009, "HELLO timeout")
        }
        return true
    }

    fun sendPresence(activity: JsonObject?) {
        send(
            DiscordProtocol.OP_PRESENCE_UPDATE,
            buildJsonObject {
                put(
                    "activities",
                    kotlinx.serialization.json.buildJsonArray {
                        activity?.let(::add)
                    },
                )
                put("afk", false)
                put("since", 0)
                put("status", "idle")
            },
        )
    }

    @Synchronized
    fun close(code: Int = 1000, reason: String = "Client disconnect") {
        val activeSocket = socket
        if (activeSocket == null || !activeSocket.close(code, reason)) {
            activeSocket?.cancel()
            finishClose(code, reason)
        }
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        runCatching {
            val packet = json.parseToJsonElement(text).jsonObject
            when (packet.getValue("op").jsonPrimitive.int) {
                DiscordProtocol.OP_HELLO -> {
                    val interval = packet.getValue("d").jsonObject
                        .getValue("heartbeat_interval")
                        .jsonPrimitive
                        .longOrNull
                        ?: error("Discord HELLO has no heartbeat interval")
                    helloTimeout?.cancel()
                    startHeartbeat(interval)
                    identify()
                }
                DiscordProtocol.OP_HEARTBEAT_ACK -> acknowledged = true
                DiscordProtocol.OP_HEARTBEAT -> heartbeat(force = true)
                DiscordProtocol.OP_RECONNECT -> close(4000, "Server requested reconnect")
                DiscordProtocol.OP_INVALID_SESSION -> close(1000, "Invalid session")
                DiscordProtocol.OP_DISPATCH -> {
                    if (packet["t"]?.jsonPrimitive?.content == "READY") {
                        val sessionId = packet.getValue("d").jsonObject
                            .getValue("session_id")
                            .jsonPrimitive
                            .content
                        listener.onReady(sessionId)
                    }
                }
            }
        }.onFailure {
            logcat(LogPriority.ERROR, it) { "Discord gateway packet failed" }
            listener.onFailure(it)
        }
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(code, reason)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        finishClose(code, reason)
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        response?.close()
        stopTimers()
        socket = null
        if (!closed) {
            closed = true
            listener.onFailure(t)
            listener.onClosed(0, t.message.orEmpty())
        }
    }

    private fun identify() {
        send(
            DiscordProtocol.OP_IDENTIFY,
            buildJsonObject {
                put("capabilities", 0)
                put("intents", DiscordProtocol.DEFAULT_INTENTS)
                put("token", token)
                put("properties", DiscordProtocol.superProperties)
            },
        )
    }

    private fun startHeartbeat(interval: Long) {
        firstHeartbeat?.cancel()
        heartbeatLoop?.cancel()
        firstHeartbeat = scope.launch {
            delay(Random.nextLong(interval.coerceAtLeast(1)))
            heartbeat()
            heartbeatLoop = scope.launch {
                while (true) {
                    delay(interval)
                    heartbeat()
                }
            }
        }
    }

    private fun heartbeat(force: Boolean = false) {
        if (!force && !acknowledged) {
            close(4009, "Heartbeat ACK missed")
            return
        }
        acknowledged = false
        send(DiscordProtocol.OP_HEARTBEAT, JsonNull)
    }

    private fun send(op: Int, data: kotlinx.serialization.json.JsonElement) {
        socket?.send(
            json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("op", op)
                    put("d", data)
                },
            ),
        )
    }

    private fun finishClose(code: Int, reason: String) {
        if (closed) return
        closed = true
        stopTimers()
        socket = null
        listener.onClosed(code, reason)
    }

    private fun stopTimers() {
        helloTimeout?.cancel()
        firstHeartbeat?.cancel()
        heartbeatLoop?.cancel()
        helloTimeout = null
        firstHeartbeat = null
        heartbeatLoop = null
    }

    private companion object {
        const val HELLO_TIMEOUT_MS = 20_000L
    }
}
