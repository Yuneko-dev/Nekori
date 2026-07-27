package eu.kanade.tachiyomi.jsruntime

import android.util.Base64
import com.facebook.proguard.annotations.DoNotStrip
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.turbomodule.core.interfaces.TurboModule
import java.security.SecureRandom

/**
 * The native half of the TurboModule declared by `specs/NativeHostApi.ts`.
 *
 * Codegen runs once in `:app`, where its C++ registration is linked into `libappmodules.so`.
 * Generating the Java base class again in this library creates a duplicate class that debug D8
 * tolerates but release R8 rejects. This implementation therefore declares the generated method
 * signatures directly; instrumented bridge tests exercise the complete contract.
 */
internal class NativeHostApiModule(
    reactContext: ReactApplicationContext,
    private val userAgentProvider: () -> String,
) : ReactContextBaseJavaModule(reactContext), TurboModule {

    private val pluginStorage = PluginStorage(reactContext)

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

    @ReactMethod(isBlockingSynchronousMethod = true)
    @DoNotStrip
    fun getRandomBase64(byteLength: Double): String {
        val size = byteLength.toInt()
        require(byteLength == size.toDouble() && size in 0..MAX_RANDOM_BYTES) {
            "getRandomValues length must be an integer between 0 and $MAX_RANDOM_BYTES"
        }
        return ByteArray(size)
            .also(SECURE_RANDOM::nextBytes)
            .let { Base64.encodeToString(it, Base64.NO_WRAP) }
    }

    @ReactMethod(isBlockingSynchronousMethod = true)
    @DoNotStrip
    fun getUserAgent(): String = userAgentProvider()

    @ReactMethod
    @DoNotStrip
    fun loadPluginStorage(pluginId: String, promise: Promise) {
        runCatching { pluginStorage.load(pluginId) }
            .onSuccess { promise.resolve(it) }
            .onFailure { promise.reject("PLUGIN_STORAGE_LOAD", it.message, it) }
    }

    @ReactMethod
    @DoNotStrip
    fun applyPluginStorageMutation(pluginId: String, mutationJson: String, promise: Promise) {
        runCatching { pluginStorage.apply(pluginId, mutationJson) }
            .onSuccess { promise.resolve(null) }
            .onFailure { promise.reject("PLUGIN_STORAGE_WRITE", it.message, it) }
    }

    override fun invalidate() {
        JsCallDispatcher.detach(this)
        super.invalidate()
    }

    companion object {
        const val NAME = "NativeHostApi"
        private const val MAX_RANDOM_BYTES = 65_536
        private val SECURE_RANDOM = SecureRandom()
    }
}
