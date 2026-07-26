package eu.kanade.tachiyomi.jsruntime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented because Hermes and the React Native native libraries only exist on a device.
 *
 * This lives in `:app`, not `:js-runtime`, for one concrete reason: the React Native Gradle plugin
 * only bundles JavaScript in an **application** module (`ReactPlugin.kt:63-105`), so `:js-runtime`'s
 * own test APK has no `assets/index.android.bundle` and React Native dies with "Unable to load
 * script". See F1 and F14 in `docs/superpowers/plans/m0-findings.md`.
 *
 * It still imports no `com.facebook.*` type — everything goes through [JsRuntime], which is the
 * containment gate working as intended rather than being worked around.
 */
@RunWith(AndroidJUnit4::class)
class JsRuntimeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun startsHermesAndEvaluatesTheBundle() = runBlocking {
        val runtime = JsRuntime(context)

        runtime.start()

        // Returning at all is the assertion. Reaching this line means SoLoader linked the native
        // libraries, the bridgeless feature flags applied, Hermes came up, and the bytecode in
        // assets/index.android.bundle evaluated — a missing bundle throws "Unable to load script"
        // and a missing flag asserts on enableBridgelessArchitecture.
        assertTrue(runtime.isStarted())
    }

    @Test
    fun startIsIdempotentUnderConcurrency() = runBlocking {
        val runtime = JsRuntime(context)

        // React Native is a process-wide singleton, so this must not start a second instance or
        // re-apply the feature-flag overrides, which throw on a second call.
        (1..8).map { async { runtime.start() } }.awaitAll()

        assertTrue(runtime.isStarted())
    }

    @Test
    fun separateInstancesShareOneProcessWideRuntime() = runBlocking {
        JsRuntime(context).start()

        val second = JsRuntime(context)
        second.start()

        assertTrue(second.isStarted())
    }
}
