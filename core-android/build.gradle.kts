// Android implementation of :core: the Jellyfin server (via the Jellyfin Kotlin SDK), on-device
// storage, the decoder/display probe, and shared app wiring. Used by both :tv and :mobile.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.nitrate.android"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    api(project(":core"))
    implementation(libs.jellyfin.core)
    implementation(libs.androidx.datastore.preferences)
    api(libs.media3.exoplayer)
    api(libs.androidx.lifecycle.viewmodel.compose)
    testImplementation(libs.junit)
}
