buildscript {
    dependencies {
        classpath(libs.kotlin.gradle)
    }
}

plugins {
    id("com.facebook.react") apply false
    alias(libs.plugins.aboutLibraries) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.androidx.baselineProfile) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.kotlin.compose.compiler) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.moko.resources) apply false
    alias(libs.plugins.sqldelight) apply false

    alias(mihonx.plugins.spotless)
}

// Native npm libraries read these root properties instead of the app's Android DSL.
extra["ndkVersion"] = mihonx.versions.android.ndk.get()
extra["compileSdkVersion"] = mihonx.versions.android.sdk.compile.get().toInt()
extra["minSdkVersion"] = mihonx.versions.android.sdk.min.get().toInt()

// React Native ships against OkHttp 4.x while the rest of the app is on 5.x. OkHttp 5 keeps the
// `okhttp3` package and stays binary compatible, so pin the whole group to one version rather than
// letting Gradle's conflict resolution pick per-configuration. LNReader does the same thing for the
// same reason (`lnreader/android/build.gradle:26-33`).
allprojects {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "com.squareup.okhttp3") {
                useVersion(libs.versions.okhttp.get())
                because("React Native pulls OkHttp 4.x; the app is on 5.x")
            }
        }
    }
}

val buildLogic: IncludedBuild = gradle.includedBuild("build-logic")
tasks {
    listOf("clean", "spotlessApply", "spotlessCheck").forEach { task ->
        named(task) {
            dependsOn(buildLogic.task(":$task"))
        }
    }
}
