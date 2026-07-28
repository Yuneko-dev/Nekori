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

/** Thrown when JavaScript rejects a call, carrying its original message and stack. */
class JsRuntimeException(
    message: String,
    val jsStack: String,
) : RuntimeException(message) {
    override fun toString(): String = buildString {
        append(super.toString())
        if (jsStack.isNotBlank()) {
            append("\nJavaScript stack:\n")
            append(jsStack)
        }
    }
}

/**
 * The Kotlin end of the command protocol.
 *
 * ```
 * Kotlin call()  →  id = next()          JS  onCommand(cmd)
 *                   pending[id] = cont    →  run handler
 *                   emitOnCommand(cmd)    →  resolve(id, payload) / reject(id, message, stack)
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

    /**
     * Created once and never replaced.
     *
     * An earlier version reset this in [detach], reasoning that a torn-down instance invalidates the
     * signal. React Native invalidates and recreates the TurboModule during startup, so the reset ran
     * *after* JavaScript had already called `ready()` — leaving `awaitReady` waiting on a fresh
     * deferred that nothing would ever complete, and every call timing out at 15 s while the JS logs
     * showed a bootstrap that had plainly succeeded. React Native is a process-wide singleton here
     * ([ReactHostHolder]); a genuine instance teardown means restarting the process.
     */
    private val jsReady = CompletableDeferred<Unit>()

    val isJsReady: Boolean get() = jsReady.isCompleted

    /** Called from [NativeHostApiModule]'s constructor — the TurboModule is created by React Native. */
    fun attach(module: NativeHostApiModule) {
        this.module = module
    }

    fun detach(module: NativeHostApiModule) {
        if (this.module === module) {
            this.module = null
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

    fun resolve(id: String, payload: String) {
        // A missing id means the call was cancelled or already completed — including a duplicate
        // resolve from JS. Dropping it is correct; resuming twice would crash the coroutine machinery
        // and crashing on a benign race would be worse than the race.
        val continuation = pending.remove(id) ?: run {
            logcat { "Dropping resolve for unknown call id $id" }
            return
        }
        continuation.resume(payload)
    }

    fun reject(id: String, message: String, stack: String) {
        val continuation = pending.remove(id) ?: run {
            logcat { "Dropping reject for unknown call id $id" }
            return
        }
        continuation.resumeWithException(JsRuntimeException(message, stack))
    }

    /** Visible for tests: no call may outlive its answer. */
    fun pendingCount(): Int = pending.size
}
