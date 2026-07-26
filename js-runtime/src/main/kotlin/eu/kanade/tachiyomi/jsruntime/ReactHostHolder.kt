package eu.kanade.tachiyomi.jsruntime

import android.content.Context
import com.facebook.react.ReactHost
import com.facebook.react.ReactPackage
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint
import com.facebook.react.defaults.DefaultReactHost
import com.facebook.react.soloader.OpenSourceMergedSoMapping
import com.facebook.soloader.SoLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.TimeUnit

/**
 * Owns the single React Native instance for the process.
 *
 * There is no React UI here: no `ReactActivity`, no `ReactRootView`, no Fabric surface. React Native
 * is present only to run LNReader-compatible plugin JavaScript on Hermes with npm libraries resolved
 * by Metro. Everything the rest of the app touches goes through [JsRuntime]; this class and the
 * `com.facebook.react` types it names must not escape `:js-runtime`.
 */
internal class ReactHostHolder(
    context: Context,
    private val packages: List<ReactPackage> = emptyList(),
) {

    private val appContext = context.applicationContext

    fun isStarted(): Boolean = host != null

    /**
     * Starts the React Native instance, or returns the running one.
     *
     * Safe to call concurrently: the double-check around the lock means N callers racing on first
     * use produce one host, and every later call is a volatile read with no locking.
     *
     * The lock and the host live in the companion, not on the instance, because React Native is a
     * process-wide singleton whether we like it or not — `DefaultReactHost` caches its host in a
     * static field and returns it for every later call, and `ReactNativeFeatureFlags.override`
     * refuses to run twice. Per-instance state would let two holders race into that.
     */
    suspend fun ensureStarted(): ReactHost {
        host?.let { return it }
        return mutex.withLock {
            host ?: startHost().also { host = it }
        }
    }

    /**
     * Runs on [Dispatchers.IO] deliberately. `SoLoader.init` unpacks and links native libraries, and
     * [com.facebook.react.interfaces.TaskInterface.waitForCompletion] blocks — React Native's own
     * documentation warns that awaiting it on the UI thread deadlocks.
     */
    private suspend fun startHost(): ReactHost = withContext(Dispatchers.IO) {
        val startedAt = System.nanoTime()

        // Latched separately from `host`, because these two calls are not retryable: SoLoader
        // tolerates re-init, but ReactNativeFeatureFlags.override throws "Feature flags cannot be
        // overridden more than once". Tying them to `host` would mean a failed start leaves the
        // latch open, and the retry dies on the override instead of the real cause.
        if (!nativeInitDone) {
            SoLoader.init(appContext, OpenSourceMergedSoMapping)

            // `newArchEnabled=true` in gradle.properties is a *build* flag; the runtime flags are
            // separate and must be set in code. Without this, `ReactHostImpl.getOrCreateStartTask`
            // asserts "enableBridgelessArchitecture FeatureFlag must be set to start ReactNative".
            // It also loads libappmodules.so, which autolinking codegen produced.
            DefaultNewArchitectureEntryPoint.load()

            nativeInitDone = true
        }

        val reactHost = DefaultReactHost.getDefaultReactHost(
            context = appContext,
            packageList = packages,
            // Only consulted when a Metro dev server is in play, which it never is here.
            jsMainModulePath = JS_MAIN_MODULE_PATH,
            // Defaults to ReactBuildConfig.DEBUG. Left at the default, a debug build would try to
            // reach a packager that this setup deliberately does not run — the JS bundle is compiled
            // into the APK for every variant (`debuggableVariants = emptyList()`).
            useDevSupport = false,
        )

        val task = reactHost.start()
        val completed = task.waitForCompletion(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        check(completed) { "React Native did not start within $START_TIMEOUT_SECONDS s" }
        task.getError()?.let { throw IllegalStateException("React Native failed to start", it) }

        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        logcat { "React Native started in $elapsedMs ms" }

        reactHost
    }

    private companion object {
        const val JS_MAIN_MODULE_PATH = "src/index"
        const val START_TIMEOUT_SECONDS = 30L

        val mutex = Mutex()

        @Volatile
        var host: ReactHost? = null

        @Volatile
        var nativeInitDone: Boolean = false
    }
}
