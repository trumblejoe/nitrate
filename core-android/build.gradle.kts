// Android implementation of :core: the HTTP engine for core's Jellyfin client, on-device
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
    // Software audio decoding for DTS / TrueHD / Dolby when the device can't (LGPL FFmpeg).
    api(project(":ffmpeg-decoder"))
    implementation(libs.ktor.client.okhttp)
    implementation(libs.androidx.work)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    api(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    api(libs.androidx.lifecycle.viewmodel.compose)
    // Drawing helpers shared by both apps' decorative row styles (paths and DrawScope only).
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    testImplementation(libs.junit)
}
