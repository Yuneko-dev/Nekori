pluginManagement {
    includeBuild("gradle/build-logic")

    // The React Native Gradle plugin ships inside node_modules, so it has to be resolved through
    // Node rather than a coordinate. `js-runtime/` is the npm root for this repo — see
    // docs/superpowers/plans/2026-07-27-m0-rn-brownfield-spike.md.
    val nodeExecutable = if (System.getProperty("os.name").lowercase().contains("windows")) {
        "node.exe"
    } else {
        "node"
    }
    val reactNativeGradlePlugin = File(
        providers.exec {
            workingDir(rootDir.resolve("js-runtime"))
            commandLine(nodeExecutable, "--print", "require.resolve('@react-native/gradle-plugin/package.json')")
        }.standardOutput.asText.get().trim(),
    ).parentFile.absolutePath
    includeBuild(reactNativeGradlePlugin)

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven(url = "https://www.jitpack.io")
    }
}

plugins {
    id("com.facebook.react.settings")
}

extensions.configure<com.facebook.react.ReactSettingsExtension> {
    // Defaults assume the standard `android/` layout where package.json is one level above the
    // Gradle root. Here the Gradle root *is* the repo root and package.json lives in js-runtime/.
    autolinkLibrariesFromCommand(
        workingDirectory = rootDir.resolve("js-runtime"),
        lockFiles = settings.layout.rootDirectory.files(
            "js-runtime/pnpm-lock.yaml",
            "js-runtime/package.json",
            // The CLI config cannot infer this repo's layout, so react-native.config.js states it.
            // It has to invalidate the cached autolinking output like the lockfiles do.
            "js-runtime/react-native.config.js",
        ),
    )
}

dependencyResolutionManagement {
    versionCatalogs {
        create("mihonx") {
            from(files("gradle/mihon.versions.toml"))
        }
    }

    // Relaxed from FAIL_ON_PROJECT_REPOS for the React Native Gradle plugin, which unconditionally
    // calls `repositories.mavenCentral()` on every project (DependencyUtils.kt:80). On a stable
    // release that is the *only* repository it adds, and it is strictly narrower than what is
    // declared below — it excludes `org.webkit` so JSC is never resolved from Maven Central. This
    // app uses Hermes, so ignoring that repository loses nothing. PREFER_SETTINGS warns and ignores
    // project repositories; it does not stop enforcing that dependencies resolve from here.
    @Suppress("UnstableApiUsage")
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)

    @Suppress("UnstableApiUsage")
    repositories {
        google()
        mavenCentral()
        maven(url = "https://www.jitpack.io")
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "Nekori"
include(":app")
include(":baseline-profile")
include(":core-metadata")
include(":core:archive")
include(":core:common")
include(":core:viewmodel")
include(":data")
include(":domain")
include(":i18n")
include(":i18n-novel")
include(":js-runtime")
include(":presentation-core")
include(":presentation-widget")
include(":source-api")
include(":source-local")
include(":telemetry")
include(":tts:tiktok")
