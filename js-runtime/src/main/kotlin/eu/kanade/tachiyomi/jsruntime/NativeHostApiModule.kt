package eu.kanade.tachiyomi.jsruntime

import com.facebook.proguard.annotations.DoNotStrip
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.turbomodule.core.interfaces.TurboModule

/**
 * The native half of the TurboModule declared by `specs/NativeHostApi.ts`.
 *
 * Codegen runs once in `:app`, where its C++ registration is linked into `libappmodules.so`.
 * Generating the Java base class again in this library creates a duplicate class that debug D8
 * tolerates but release R8 rejects. This implementation therefore declares the three generated
 * method signatures directly; instrumented bridge tests exercise the complete contract.
 */
internal class NativeHostApiModule(
    reactContext: ReactApplicationContext,
) : ReactContextBaseJavaModule(reactContext), TurboModule {

    init {
        JsCallDispatcher.attach(this)
    }

    override fun getName(): String = NAME

    fun emitCommand(command: ReadableMap) {
        checkNotNull(mEventEmitterCallback) { "NativeHostApi event emitter is not attached" }
            .invoke("onCommand", command)
    }

    @ReactMethod
    @DoNotStrip
    fun ready() {
        JsCallDispatcher.onJsReady()
    }

    @ReactMethod
    @DoNotStrip
    fun resolve(id: String, json: String) {
        JsCallDispatcher.resolve(id, json)
    }

    @ReactMethod
    @DoNotStrip
    fun reject(id: String, message: String) {
        JsCallDispatcher.reject(id, message)
    }

    override fun invalidate() {
        JsCallDispatcher.detach(this)
        super.invalidate()
    }

    companion object {
        const val NAME = "NativeHostApi"
    }
}
