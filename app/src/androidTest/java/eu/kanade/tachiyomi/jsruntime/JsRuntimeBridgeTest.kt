package eu.kanade.tachiyomi.jsruntime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers the bridge semantics that a sequential ping could never catch.
 *
 * Runs in `:app`, not `:js-runtime`: React Native's Gradle plugin only bundles JavaScript in an
 * application module, so a library module's test APK has no `assets/index.android.bundle`. It still
 * imports no `com.facebook.*` type — everything goes through [JsRuntime], which is the containment
 * gate holding where it would be easiest to break.
 */
@RunWith(AndroidJUnit4::class)
class JsRuntimeBridgeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun startReturnsOnlyWhenJavaScriptHasSubscribed() = runBlocking {
        val runtime = JsRuntime(context)

        runtime.start()

        // HOST_STARTED would mean the bundle evaluated. JS_READY means JS is actually listening —
        // the distinction that stops a command being emitted into a void.
        assertEquals(JsRuntimeState.JS_READY, runtime.state)
    }

    @Test
    fun callsIntoJavaScriptAndGetsTheResultBack() = runBlocking {
        val runtime = JsRuntime(context)

        val json = runtime.call("sum", """{"a":2,"b":40}""")

        assertEquals("""{"result":42}""", json)
        assertEquals("pending map must drain on success", 0, runtime.pendingCallCount())
    }

    @Test
    fun javaScriptThrowBecomesKotlinException() = runBlocking {
        val runtime = JsRuntime(context)

        try {
            runtime.call("boom", """{"message":"plugin exploded"}""")
            fail("a JS throw must surface as JsRuntimeException")
        } catch (e: JsRuntimeException) {
            assertEquals("plugin exploded", e.message)
        }
        assertEquals("pending map must drain on rejection", 0, runtime.pendingCallCount())
    }

    @Test
    fun unknownMethodFailsLoudly() = runBlocking {
        val runtime = JsRuntime(context)

        try {
            runtime.call("noSuchMethod", "{}")
            fail("an unregistered method must throw, not resolve with null")
        } catch (e: JsRuntimeException) {
            assertTrue(
                "message should name the method, was: ${e.message}",
                e.message.orEmpty().contains("noSuchMethod"),
            )
        }
    }

    @Test
    fun concurrentCallsDoNotCrossWires() = runBlocking {
        val runtime = JsRuntime(context)

        // Each call carries its own id. A single shared continuation, a last-request-wins bug, or a
        // non-atomic pending mutation all show up here as answers landing on the wrong caller, and
        // none of them are visible to call → result → call → result.
        val results = (1..16)
            .map { n -> async { n to runtime.call("sum", """{"a":$n,"b":0}""") } }
            .awaitAll()

        results.forEach { (n, json) -> assertEquals("""{"result":$n}""", json) }
        assertEquals(0, runtime.pendingCallCount())
    }

    @Test
    fun cancellingACallDrainsItAndIgnoresTheLateAnswer() = runBlocking {
        val runtime = JsRuntime(context)
        runtime.start()
        val before = runtime.pendingCallCount()

        try {
            // "never" resolves on the JS side only when the process ends, so the only way out is the
            // timeout — which is exactly the shape of a plugin call that hangs on a dead site.
            withTimeout(500) { runtime.call("never", "{}") }
            fail("the call should have timed out")
        } catch (_: TimeoutCancellationException) {
            // expected
        }

        // Without invokeOnCancellation removing the entry, this leaks one continuation per abandoned
        // call and the map grows for the life of the process.
        assertEquals("cancelled call must be removed from the pending map", before, runtime.pendingCallCount())

        // A later answer for that id must be dropped rather than resuming anything, and the runtime
        // must still serve new work.
        delay(200)
        assertEquals("""{"result":7}""", runtime.call("sum", """{"a":7,"b":0}"""))
    }
}
