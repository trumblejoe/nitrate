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
            // Dev builds signed with the public dev key get their own ID (see signing/README.md).
            System.getenv("NITRATE_APP_ID_SUFFIX")?.let { applicationIdSuffix = it }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Test fixtures shared with :mobile (fake server, sample artwork).
    sourceSets["test"].kotlin.srcDir("../testing/src")
    sourceSets["test"].resources.srcDir("../testing/resources")

    testOptions {
        // Lets JVM tests touch android.os.Build fields without Robolectric.
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        // Screenshot tests write PNGs to build/outputs/roborazzi (see ScreenshotTest).
        unitTests.all { it.systemProperty("roborazzi.test.record", "true") }
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
    implementation(libs.androidx.tvprovider)
    implementation(libs.androidx.work)

    implementation(project(":core-android"))
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

// Robolectric's Android jar comes through Gradle (cached, retried); Robolectric then runs offline.
val robolectricAndroid: Configuration by configurations.creating
dependencies {
    robolectricAndroid("org.robolectric:android-all-instrumented:15-robolectric-13954326-i7")
}
val robolectricDeps = layout.buildDirectory.dir("robolectric-deps")
val copyRobolectricDeps by tasks.registering(Copy::class) {
    from(robolectricAndroid)
    into(robolectricDeps)
}
tasks.withType<Test>().configureEach {
    dependsOn(copyRobolectricDeps)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", robolectricDeps.get().asFile.absolutePath)
}

kotlin {
    compilerOptions {
        // tv-material keeps several stable-in-practice components (chips, tabs) marked experimental.
        optIn.add("androidx.tv.material3.ExperimentalTvMaterial3Api")
    }
}
