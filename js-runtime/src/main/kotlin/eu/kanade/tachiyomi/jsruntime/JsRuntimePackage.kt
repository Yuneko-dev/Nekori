package eu.kanade.tachiyomi.jsruntime

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider
import eu.kanade.tachiyomi.jsruntime.spec.NativeHostApiSpec

/** Registers the app's TurboModules with the React Native instance. */
internal class JsRuntimePackage : BaseReactPackage() {

    override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? =
        when (name) {
            NativeHostApiSpec.NAME -> NativeHostApiModule(reactContext)
            else -> null
        }

    override fun getReactModuleInfoProvider(): ReactModuleInfoProvider = ReactModuleInfoProvider {
        mapOf(
            NativeHostApiSpec.NAME to ReactModuleInfo(
                NativeHostApiSpec.NAME,
                NativeHostApiModule::class.java.name,
                // canOverrideExistingModule
                false,
                // needsEagerInit — false, so the module is created when JS first requires it.
                false,
                // isCxxModule
                false,
                // isTurboModule
                true,
            ),
        )
    }
}
