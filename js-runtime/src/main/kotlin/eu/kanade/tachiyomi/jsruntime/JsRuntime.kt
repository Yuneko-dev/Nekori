package eu.kanade.tachiyomi.jsruntime

import android.content.Context
import okhttp3.OkHttpClient

/**
 * Lifecycle of the JavaScript runtime.
 *
 * [HOST_STARTED] is deliberately not the end state. It means the bundle evaluated; it does not mean
 * JavaScript has subscribed to commands. Only [JS_READY] does, and only [JS_READY] is safe to
 * dispatch against.
 */
enum class JsRuntimeState {
    UNINITIALIZED,
    HOST_STARTED,
    JS_READY,
}

/**
 * The only entry point the rest of the app is allowed to use.
 *
 * Everything React Native — `ReactHost`, TurboModules, Hermes, the JS bundle — stays behind this
 * class inside `:js-runtime`. No `com.facebook.*` type appears in any signature here, which is what
 * keeps `react-android` declarable as `implementation` rather than `api` and stops React Native
 * concepts leaking into app and domain code.
 */
class JsRuntime(
    context: Context,
    networkClient: OkHttpClient,
) {

    init {
        ReactNativeNetworkClient.install(networkClient)
    }

    private val host = ReactHostHolder(
        context,
        listOf(JsRuntimePackage()),
    )

    val state: JsRuntimeState
        get() = when {
            JsCallDispatcher.isJsReady -> JsRuntimeState.JS_READY
            host.isStarted() -> JsRuntimeState.HOST_STARTED
            else -> JsRuntimeState.UNINITIALIZED
        }

    /**
     * Boots Hermes, evaluates the bundle, and waits for JavaScript to subscribe to commands.
     * Idempotent and safe to call concurrently.
     *
     * Returns only at [JsRuntimeState.JS_READY]. Returning at `HOST_STARTED` would be the more
     * obvious contract and the wrong one: a command emitted before JS subscribes reaches nobody, and
     * the caller suspends until its timeout with no indication why.
     */
    suspend fun start() {
        host.ensureStarted()
        JsCallDispatcher.awaitReady(READY_TIMEOUT_MILLIS)
    }

    /**
     * Runs [method] in JavaScript with a JSON [payloadJson], and returns its JSON result.
     *
     * Starts the runtime if needed. Cancelling the calling coroutine removes the pending entry, so a
     * late answer from JavaScript is dropped rather than resuming a dead continuation. A JS-side
     * throw arrives as [JsRuntimeException] carrying its message; an unregistered method name is a
     * JS-side throw, not a silent null.
     *
     * Send one command per user-visible operation. The bridge is not free, and the design assumes JS
     * does the whole job — fetch, parse, normalize — and answers once.
     */
    suspend fun call(method: String, payloadJson: String = ""): String {
        start()
        return JsCallDispatcher.call(method, payloadJson)
    }

    /**
     * The number of dispatched calls still awaiting an answer.
     *
     * Exposed so tests can assert the pending map is drained on success, on rejection and on
     * cancellation — a leak here is invisible until the process has run for hours.
     */
    fun pendingCallCount(): Int = JsCallDispatcher.pendingCount()

    private companion object {
        const val READY_TIMEOUT_MILLIS = 15_000L
    }
}
