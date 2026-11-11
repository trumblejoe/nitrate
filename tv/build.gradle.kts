plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.nitrate.tv"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "dev.nitrate.tv"
        minSdk = 24
        targetSdk = 36
        // CI sets these for releases (see .github/workflows/release.yml).
        versionCode = System.getenv("NITRATE_VERSION_CODE")?.toInt() ?: 1
        versionName = System.getenv("NITRATE_VERSION_NAME") ?: "0.1.0"
    }

    signingConfigs {
        // The release key comes from CI secrets. Every release must use the same key, or
        // Android refuses to install it over the previous one.
        System.getenv("NITRATE_KEYSTORE_FILE")?.let { path ->
            create("release") {
                storeFile = file(path)
                storePassword = System.getenv("NITRATE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("NITRATE_KEY_ALIAS")
                keyPassword = System.getenv("NITRATE_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            // Without the CI key (local builds), sign with the debug key so the APK still installs.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // Lets JVM tests touch android.os.Build fields without Robolectric.
        unitTests.isReturnDefaultValues = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.tv.material)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)

    implementation(project(":core-android"))
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}

kotlin {
    compilerOptions {
        // tv-material keeps several stable-in-practice components (chips, tabs) marked experimental.
        optIn.add("androidx.tv.material3.ExperimentalTvMaterial3Api")
    }
}
