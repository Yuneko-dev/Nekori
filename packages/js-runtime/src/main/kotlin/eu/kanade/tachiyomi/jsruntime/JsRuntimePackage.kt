package eu.kanade.tachiyomi.jsruntime

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider
import com.facebook.react.modules.blob.BlobModule
import com.facebook.react.modules.blob.FileReaderModule
import com.facebook.react.modules.network.NetworkingModule
import com.preeternal.reactnativecookiemanager.CookieManagerModule

/** Registers only the native modules used by the headless plugin runtime. */
internal class JsRuntimePackage(
    private val userAgentProvider: () -> String,
) : BaseReactPackage() {

    override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? =
        when (name) {
            NativeHostApiModule.NAME -> NativeHostApiModule(reactContext, userAgentProvider)
            CookieManagerModule.NAME -> CookieManagerModule(reactContext)
            NetworkingModule.NAME -> NetworkingModule(reactContext)
            BlobModule.NAME -> BlobModule(reactContext)
            FileReaderModule.NAME -> FileReaderModule(reactContext)
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
            CookieManagerModule.NAME to moduleInfo<CookieManagerModule>(
                CookieManagerModule.NAME,
                isTurboModule = true,
            ),
            NetworkingModule.NAME to moduleInfo<NetworkingModule>(NetworkingModule.NAME),
            BlobModule.NAME to moduleInfo<BlobModule>(BlobModule.NAME),
            FileReaderModule.NAME to moduleInfo<FileReaderModule>(FileReaderModule.NAME),
        )
    }

    private inline fun <reified T : NativeModule> moduleInfo(
        name: String,
        isTurboModule: Boolean = ReactModuleInfo.classIsTurboModule(T::class.java),
    ) = ReactModuleInfo(
        name,
        T::class.java.name,
        false,
        false,
        false,
        isTurboModule,
    )
}
