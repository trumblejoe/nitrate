// libdovi (quietvoid/dovi_tool's dolby_vision crate, MIT), built by tools/build-libdovi.sh, behind a
// small JNI bridge that rewrites dual-layer Dolby Vision (profile 7) metadata as profile 8.1.
// Without that build the module still compiles and reports itself unavailable, so profile 7 plays
// as its HDR10 base layer.
plugins {
    alias(libs.plugins.android.library)
}

/** The static libraries, per ABI, from tools/build-libdovi.sh. */
val libdoviBuilt = file("src/main/jni/libdovi/android-libs").exists()

android {
    namespace = "dev.nitrate.dovi"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    ndkVersion = "27.0.12077973"
    defaultConfig {
        minSdk = 24
        consumerProguardFiles("proguard-rules.txt")
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
        externalNativeBuild { cmake { arguments += listOf("-DCMAKE_SHARED_LINKER_FLAGS=-Wl,--strip-all") } }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    if (libdoviBuilt) {
        externalNativeBuild {
            cmake {
                path = file("src/main/jni/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }
}
