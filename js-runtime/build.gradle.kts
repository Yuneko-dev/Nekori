import java.util.Properties

plugins {
    alias(mihonx.plugins.android.library)
    alias(mihonx.plugins.spotless)
}

android {
    namespace = "eu.kanade.tachiyomi.jsruntime"
}

/**
 * React Native's Android artifacts are versioned by the installed npm package, not by anything in
 * the Gradle catalog, and Hermes uses a separate group and its own version scheme entirely
 * (`com.facebook.hermes:hermes-android:250829098.0.14` for React Native 0.86.0). `react-android`
 * does not pull Hermes transitively.
 *
 * Read both from node_modules so a `pnpm up` in js-runtime/ cannot silently leave Gradle behind.
 */
fun npmProperty(path: String, key: String): String {
    val file = layout.projectDirectory.file("node_modules/react-native/$path").asFile
    check(file.exists()) { "Missing $file — run `pnpm install` in js-runtime/ first." }
    val value = file.inputStream().use { stream -> Properties().apply { load(stream) } }.getProperty(key)
    return checkNotNull(value) { "No `$key` in $file" }
}

val reactNativeVersion = npmProperty("ReactAndroid/gradle.properties", "VERSION_NAME")
val hermesVersion = npmProperty("sdks/hermes-engine/version.properties", "HERMES_V1_VERSION_NAME")

// `hermes-compiler` is a transitive dependency of react-native, but pnpm's isolated node_modules
// only symlinks direct dependencies and the React Native Gradle plugin looks for `hermesc` at the
// literal path `node_modules/hermes-compiler/hermesc/%OS-BIN%/` (PathUtils.kt:243). So it is pinned
// as a direct dependency in package.json — which means its version can drift away from the Hermes
// version React Native actually ships. Fail loudly here instead of shipping a bytecode compiler that
// does not match the runtime.
run {
    val hermesCompilerPackageJson =
        layout.projectDirectory.file("node_modules/hermes-compiler/package.json").asFile
    check(hermesCompilerPackageJson.exists()) {
        "Missing $hermesCompilerPackageJson — `hermes-compiler` must be a direct dependency of " +
            "js-runtime/package.json, pinned to $hermesVersion."
    }
    val declared = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"")
        .find(hermesCompilerPackageJson.readText())
        ?.groupValues
        ?.get(1)
    check(declared == hermesVersion) {
        "hermes-compiler is $declared but React Native $reactNativeVersion ships Hermes " +
            "$hermesVersion. " +
            "Set \"hermes-compiler\": \"$hermesVersion\" in js-runtime/package.json and reinstall."
    }
}

dependencies {
    implementation(projects.core.common)

    // `api` so :app sees the React Native types without depending on the npm layout itself.
    api("com.facebook.react:react-android:$reactNativeVersion")
    api("com.facebook.hermes:hermes-android:$hermesVersion")
}
