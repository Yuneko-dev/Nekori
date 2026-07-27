package eu.kanade.tachiyomi.jsruntime

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider

/** Registers the app's TurboModules with the React Native instance. */
internal class JsRuntimePackage : BaseReactPackage() {

    override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? =
        when (name) {
            NativeHostApiModule.NAME -> NativeHostApiModule(reactContext)
            else -> null
        }

    override fun getReactModuleInfoProvider(): ReactModuleInfoProvider = ReactModuleInfoProvider {
        mapOf(
            NativeHostApiModule.NAME to ReactModuleInfo(
                NativeHostApiModule.NAME,
                NativeHostApiModule::class.java.name,
                // canOverrideExistingModule
                false,
                // needsEagerInit: attach the bridge before the headless bundle starts.
                true,
                // isCxxModule
                false,
                // isTurboModule
                true,
            ),
        )
    }
}
