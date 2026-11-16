// libass (ISC) with FreeType, HarfBuzz and FriBidi, built by tools/build-libass.sh, behind a small
// JNI bridge that renders ASS/SSA subtitles into a bitmap for Nitrate's subtitle layer. Without
// that build the module still compiles and reports itself unavailable, so styled subtitles fall
// back to Media3's simpler rendering.
plugins {
    alias(libs.plugins.android.library)
}

/** The static libraries, per ABI, from tools/build-libass.sh. */
val libassBuilt = file("src/main/jni/libass/android-libs").exists()

android {
    namespace = "dev.nitrate.ass"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    ndkVersion = "27.0.12077973"
    defaultConfig {
        minSdk = 24
        consumerProguardFiles("proguard-rules.txt")
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
        externalNativeBuild { cmake { arguments += listOf("-DCMAKE_SHARED_LINKER_FLAGS=-Wl,--strip-all", "-DANDROID_STL=c++_static") } }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    if (libassBuilt) {
        externalNativeBuild {
            cmake {
                path = file("src/main/jni/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }
}
