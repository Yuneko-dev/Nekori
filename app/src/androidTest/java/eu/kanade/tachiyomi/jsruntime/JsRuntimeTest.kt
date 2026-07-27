package eu.kanade.tachiyomi.jsruntime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Lifecycle of the runtime itself. The command protocol is covered by [JsRuntimeBridgeTest].
 *
 * Instrumented because Hermes and the React Native native libraries only exist on a device. Every
 * assertion is order-independent: React Native is a process-wide singleton, so whichever test runs
 * first is the one that actually starts it.
 */
@RunWith(AndroidJUnit4::class)
class JsRuntimeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val networkClient by lazy { Injekt.get<NetworkHelper>().client }

    @Test
    fun startsHermesAndEvaluatesTheBundle() = runBlocking {
        val runtime = JsRuntime(context, networkClient)

        runtime.start()

        // Reaching JS_READY means SoLoader linked the native libraries, the bridgeless feature flags
        // applied, Hermes came up, the bytecode in assets/index.android.bundle evaluated, and the JS
        // side subscribed and called back.
        assertEquals(JsRuntimeState.JS_READY, runtime.state)
    }

    @Test
    fun startIsIdempotentUnderConcurrency() = runBlocking {
        val runtime = JsRuntime(context, networkClient)

        // Must not start a second instance or re-apply the feature-flag overrides, which throw on a
        // second call.
        (1..8).map { async { runtime.start() } }.awaitAll()

        assertEquals(JsRuntimeState.JS_READY, runtime.state)
    }

    @Test
    fun separateInstancesShareOneProcessWideRuntime() = runBlocking {
        JsRuntime(context, networkClient).start()

        val second = JsRuntime(context, networkClient)
        second.start()

        assertEquals(JsRuntimeState.JS_READY, second.state)
    }
}
