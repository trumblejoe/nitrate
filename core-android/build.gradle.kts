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
    api(project(":app-core"))
    // Software audio decoding for DTS / TrueHD / Dolby when the device can't (LGPL FFmpeg).
    api(project(":ffmpeg-decoder"))
    api(project(":ass-renderer"))
    implementation(project(":dovi-converter"))
    implementation(libs.ktor.client.okhttp)
    implementation(libs.androidx.work)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    api(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    // 3D films shown in 2D: one eye's view cropped and stretched back (only for those).
    implementation(libs.media3.effect)
    api(libs.androidx.lifecycle.viewmodel.compose)
    // Drawing helpers shared by both apps' decorative row styles (paths and DrawScope only).
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    // EPUB books (Readium, BSD-3-Clause): opening, laying out and paging them.
    implementation(libs.readium.shared)
    implementation(libs.readium.streamer)
    implementation(libs.readium.navigator)
    // Readium's reader is a fragment in an AppCompat screen.
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.appcompat)
    testImplementation(libs.junit)
    testImplementation(libs.ktor.client.mock)
}
