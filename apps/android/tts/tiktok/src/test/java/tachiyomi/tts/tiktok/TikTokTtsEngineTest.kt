package tachiyomi.tts.tiktok

import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

class TikTokTtsEngineTest {
    @Test
    fun `audio messages assemble and playback callbacks preserve utterance id`() {
        val fixture = Fixture()
        fixture.engine.speak("hello", VOICE, 1f, 1f, "u1", fixture.listener)
        fixture.connection(0).apply {
            binary(byteArrayOf(1, 2))
            binary(byteArrayOf(3, 4))
            finish()
        }

        assertEquals(listOf("u1"), fixture.started)
        assertEquals(byteArrayOf(1, 2, 3, 4).toList(), fixture.players.single().data.toList())
        fixture.players.single().finish()
        assertEquals(listOf("u1"), fixture.done)
        fixture.close()
    }

    @Test
    fun `network failure retries three times then reports error without completing`() {
        val fixture = Fixture()

        fixture.engine.speak("hello", VOICE, 1f, 1f, "u1", fixture.listener)

        repeat(TikTokTtsEngine.MAX_RETRIES + 1) { attempt ->
            fixture.awaitConnections(attempt + 1)
            fixture.connection(attempt).fail()
        }

        fixture.await { fixture.errors.size == 1 }

        assertEquals(
            TikTokTtsEngine.MAX_RETRIES + 1,
            fixture.connections.size,
        )
        assertEquals("u1", fixture.errors.single().first)
        assertTrue(fixture.done.isEmpty())
        assertFalse(fixture.engine.isSpeaking)

        fixture.close()
    }

    @Test
    fun `protocol failure follows LNReader retry handling`() {
        val fixture = Fixture()
        fixture.engine.speak("hello", VOICE, 1f, 1f, "u1", fixture.listener)
        repeat(TikTokTtsEngine.MAX_RETRIES + 1) { attempt ->
            fixture.awaitConnections(attempt + 1)
            fixture.connection(attempt).taskFailed()
        }

        fixture.await { fixture.errors.size == 1 }
        assertEquals(TikTokTtsEngine.MAX_RETRIES + 1, fixture.connections.size)
        assertEquals("u1", fixture.errors.single().first)
        assertEquals(listOf("u1"), fixture.done)
        fixture.close()
    }

    @Test
    fun `preload is limited cache is bounded and stop rejects late callbacks`() {
        val fixture = Fixture()
        fixture.engine.speak("current", VOICE, 1f, 1f, "u1", fixture.listener)
        fixture.engine.preload((1..10).map { "next-$it" }, VOICE)
        assertEquals(1 + TikTokTtsEngine.MAX_PRELOAD_REQUESTS, fixture.connections.size)

        fixture.connections.toList().forEach { connection ->
            connection.binary(byteArrayOf(1, 2))
            connection.finish()
        }
        assertTrue(fixture.engine.cachedEntryCount() <= TikTokTtsEngine.MAX_CACHE_ENTRIES)

        val late = Fixture()
        late.engine.speak("cancel", VOICE, 1f, 1f, "u2", late.listener)
        val connection = late.connection(0)
        late.engine.stop()
        assertTrue(connection.socket.cancelled)
        connection.binary(byteArrayOf(1, 2))
        connection.finish()
        assertTrue(late.started.isEmpty())
        assertTrue(late.done.isEmpty())
        assertTrue(late.errors.isEmpty())
        fixture.close()
        late.close()
    }

    @Test
    fun `pause and resume operate on current audio track`() {
        val fixture = Fixture()
        fixture.engine.speak("hello", VOICE, 1f, 1f, "u1", fixture.listener)
        fixture.connection(0).apply {
            binary(byteArrayOf(1, 2))
            finish()
        }
        val player = fixture.players.single()

        fixture.engine.pause()
        assertFalse(fixture.engine.isSpeaking)
        fixture.engine.resume()
        assertTrue(fixture.engine.isSpeaking)
        assertEquals(1, player.pauseCount)
        assertEquals(1, player.resumeCount)
        fixture.close()
    }

    private class Fixture : AutoCloseable {
        val connections = CopyOnWriteArrayList<Connection>()
        val players = CopyOnWriteArrayList<FakePlayer>()
        val started = CopyOnWriteArrayList<String>()
        val done = CopyOnWriteArrayList<String>()
        val errors = CopyOnWriteArrayList<Pair<String, Throwable>>()
        val listener = object : TikTokTtsEngine.Listener {
            override fun onStart(utteranceId: String) {
                started += utteranceId
            }

            override fun onDone(utteranceId: String) {
                done += utteranceId
            }

            override fun onError(utteranceId: String, error: Throwable) {
                errors += utteranceId to error
            }
        }
        val engine = TikTokTtsEngine(
            newWebSocket = { request, listener ->
                FakeWebSocket(request).also { socket -> connections += Connection(socket, listener) }
            },
            endpoint = "wss://example.invalid/tts",
            callbackExecutor = Executor(Runnable::run),
            playerFactory = PcmPlayerFactory { data, _, _, onDone ->
                FakePlayer(data, onDone).also(players::add)
            },
            retryDelayMs = 0,
        )

        fun connection(index: Int) = connections[index]

        fun awaitConnections(count: Int) = await { connections.size >= count }

        fun await(condition: () -> Boolean) {
            repeat(200) {
                if (condition()) return
                Thread.sleep(5)
            }
            error("Condition was not met")
        }

        override fun close() = engine.close()
    }

    private data class Connection(val socket: FakeWebSocket, val listener: WebSocketListener) {
        fun binary(data: ByteArray) = listener.onMessage(socket, ByteString.of(*data))
        fun finish() = listener.onMessage(socket, """{"event":"TaskFinished"}""")
        fun taskFailed() = listener.onMessage(
            socket,
            """{"event":"TaskFailed","status_code":401,"status_text":"denied"}""",
        )
        fun fail() = listener.onFailure(socket, IOException("offline"), null)
    }

    private class FakeWebSocket(private val request: Request) : WebSocket {
        var cancelled = false
        override fun request() = request
        override fun queueSize() = 0L
        override fun send(text: String) = true
        override fun send(bytes: ByteString) = true
        override fun close(code: Int, reason: String?) = true
        override fun cancel() {
            cancelled = true
        }
    }

    private class FakePlayer(val data: ByteArray, private val onDone: () -> Unit) : PcmPlayer {
        var playing = false
        var pauseCount = 0
        var resumeCount = 0
        override val isPlaying get() = playing
        override fun play() {
            playing = true
        }
        override fun pause() {
            pauseCount++
            playing = false
        }
        override fun resume() {
            resumeCount++
            playing = true
        }
        override fun release() {
            playing = false
        }
        fun finish() {
            playing = false
            onDone()
        }
    }

    companion object {
        private const val VOICE = "BV074_streaming"
    }
}
