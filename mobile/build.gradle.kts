// Android phone/tablet app. Screens use only :core types and multiplatform-ready Compose
// libraries (Material 3, Navigation, Lifecycle, Coil) so they can move to Compose
// Multiplatform for iOS; Android-only pieces live in the `platform` package.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.nitrate.mobile"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "dev.nitrate.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        // Screenshot tests write PNGs to build/outputs/roborazzi (see ScreenshotTest).
        unitTests.all { it.systemProperty("roborazzi.test.record", "true") }
    }
}

dependencies {
    implementation(project(":core-android"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.media3.ui)
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

// Robolectric normally downloads its Android jar at test time, with no retries. Let Gradle fetch
// it (cached, retried) and run Robolectric offline against that copy instead.
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
