package eu.kanade.tachiyomi.jsruntime

import com.facebook.react.bridge.Arguments
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Thrown when JavaScript rejects a call, carrying the JS-side message. */
class JsRuntimeException(message: String) : RuntimeException(message)

/**
 * The Kotlin end of the command protocol.
 *
 * ```
 * Kotlin call()  →  id = next()          JS  onCommand(cmd)
 *                   pending[id] = cont    →  run handler
 *                   emitOnCommand(cmd)    →  resolve(id, json) / reject(id, message)
 *                   suspend              ←   resume pending.remove(id)
 * ```
 *
 * An `object`, not an instance: React Native is a process-wide singleton (see [ReactHostHolder]), so
 * two dispatchers would mean two id spaces feeding one JS subscriber.
 *
 * **`ready` exists because a started host is not a subscribed one.** `ReactHost.start()` completing
 * means the bundle evaluated; it says nothing about whether JS has attached its `onCommand`
 * listener. A command emitted in that window is delivered to nobody and the caller suspends until
 * its timeout. So JS calls [onJsReady] after subscribing, and [call] waits for it.
 */
internal object JsCallDispatcher {

    private val pending = ConcurrentHashMap<String, CancellableContinuation<String>>()
    private val nextId = AtomicLong()

    @Volatile
    private var module: NativeHostApiModule? = null

    @Volatile
    private var jsReady = CompletableDeferred<Unit>()

    val isJsReady: Boolean get() = jsReady.isCompleted

    /** Called from [NativeHostApiModule]'s constructor — the TurboModule is created by React Native. */
    fun attach(module: NativeHostApiModule) {
        this.module = module
    }

    fun detach(module: NativeHostApiModule) {
        if (this.module === module) {
            this.module = null
            // A new instance means a new JS context, so the old readiness signal is stale. Failing
            // the in-flight calls is better than leaving them suspended against a dead runtime.
            failAll("React Native instance was torn down")
            jsReady = CompletableDeferred()
        }
    }

    /** JS has subscribed to `onCommand` and can receive work. */
    fun onJsReady() {
        jsReady.complete(Unit)
    }

    suspend fun awaitReady(timeoutMillis: Long) {
        if (jsReady.isCompleted) return
        try {
            withTimeout(timeoutMillis) { jsReady.await() }
        } catch (e: TimeoutCancellationException) {
            throw IllegalStateException(
                "JavaScript did not signal ready within $timeoutMillis ms — the bundle evaluated but " +
                    "never called NativeHostApi.ready()",
                e,
            )
        }
    }

    suspend fun call(method: String, payloadJson: String): String =
        suspendCancellableCoroutine { continuation ->
            val id = nextId.incrementAndGet().toString()
            pending[id] = continuation

            continuation.invokeOnCancellation {
                // Drop the entry first: a late resolve for a cancelled id must not resume anything,
                // and leaving it behind would leak one continuation per timed-out call for the
                // lifetime of the process.
                pending.remove(id)
            }

            val target = module
            if (target == null) {
                pending.remove(id)
                continuation.resumeWithException(
                    IllegalStateException("NativeHostApi is not registered; cannot dispatch \"$method\""),
                )
                return@suspendCancellableCoroutine
            }

            target.emitCommand(
                Arguments.createMap().apply {
                    putString("id", id)
                    putString("method", method)
                    putString("args", payloadJson)
                },
            )
        }

    fun resolve(id: String, json: String) {
        // A missing id means the call was cancelled or already completed — including a duplicate
        // resolve from JS. Dropping it is correct; resuming twice would crash the coroutine machinery
        // and crashing on a benign race would be worse than the race.
        val continuation = pending.remove(id) ?: run {
            logcat { "Dropping resolve for unknown call id $id" }
            return
        }
        continuation.resume(json)
    }

    fun reject(id: String, message: String) {
        val continuation = pending.remove(id) ?: run {
            logcat { "Dropping reject for unknown call id $id" }
            return
        }
        continuation.resumeWithException(JsRuntimeException(message))
    }

    /** Visible for tests: no call may outlive its answer. */
    fun pendingCount(): Int = pending.size

    private fun failAll(reason: String) {
        val ids = pending.keys.toList()
        ids.forEach { id ->
            pending.remove(id)?.resumeWithException(IllegalStateException(reason))
        }
    }
}
