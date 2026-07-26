package eu.kanade.tachiyomi.jsruntime

import android.content.Context

/**
 * The only entry point the rest of the app is allowed to use.
 *
 * Everything React Native — `ReactHost`, TurboModules, Hermes, the JS bundle — stays behind this
 * class inside `:js-runtime`. No `com.facebook.*` type appears in any signature here, which is what
 * keeps `react-android` declarable as `implementation` rather than `api` and makes the containment
 * gate a compiler error rather than a code-review habit.
 */
class JsRuntime(context: Context) {

    private val host = ReactHostHolder(context)

    fun isStarted(): Boolean = host.isStarted()

    /**
     * Boots Hermes and evaluates the bundled JavaScript. Idempotent, safe to call concurrently, and
     * suspends on [kotlinx.coroutines.Dispatchers.IO] internally.
     *
     * Returning normally means the bundle in `assets/index.android.bundle` loaded and evaluated —
     * a missing or corrupt bundle surfaces here as "Unable to load script".
     */
    suspend fun start() {
        host.ensureStarted()
    }
}
