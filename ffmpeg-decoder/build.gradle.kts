// Media3's FFmpeg audio decoder (the official extension's Java and JNI, Apache 2.0) over an FFmpeg
// built LGPL and audio-only by tools/build-ffmpeg.sh. Without that build the module still compiles
// and simply reports itself unavailable, so the apps fall back to the server converting audio.
plugins {
    alias(libs.plugins.android.library)
}

/** FFmpeg's static libraries, per ABI, from tools/build-ffmpeg.sh. */
val ffmpegBuilt = file("src/main/jni/ffmpeg/android-libs").exists()

android {
    namespace = "androidx.media3.decoder.ffmpeg"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    ndkVersion = "27.0.12077973"
    defaultConfig {
        minSdk = 24
        consumerProguardFiles("proguard-rules.txt")
        // ARM covers Fire TV, Android TV boxes and phones.
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
        // Ship it without debug symbols (the apps don't pin an NDK, so they wouldn't strip it).
        externalNativeBuild { cmake { arguments += "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,--strip-all" } }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    if (ffmpegBuilt) {
        externalNativeBuild {
            cmake {
                path = file("src/main/jni/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }
}

dependencies {
    api(libs.media3.decoder)
    implementation(libs.media3.exoplayer)
    implementation("androidx.annotation:annotation:1.9.1")
    compileOnly("org.checkerframework:checker-qual:3.49.0")
}
