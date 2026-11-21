// The phone/tablet app's screens, as a Kotlin Multiplatform library: :mobile (Android) and
// :ios-app (iPhone/iPad) both show them. Screens move from androidMain into commonMain as the
// pieces under them become multiplatform; Android-only parts stay in androidMain (`platform`).
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    android {
        namespace = "dev.nitrate.mobile.ui"
        compileSdk { version = release(37) { minorApiLevel = 2 } }
        minSdk = 24
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    iosArm64()
    iosSimulatorArm64()

    compilerOptions {
        optIn.add("kotlinx.cinterop.ExperimentalForeignApi")
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            api(project(":app-core"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(compose.material3)
            implementation(libs.cmp.material.icons.core)
            implementation(libs.jb.navigation.compose)
            implementation(libs.jb.lifecycle.viewmodel.compose)
            implementation(libs.jb.lifecycle.runtime.compose)
            implementation(libs.coil.compose)
            implementation(libs.kotlinx.serialization.json)
        }
        iosMain.dependencies {
            implementation(libs.coil.network.ktor3)
            implementation(libs.ktor.client.darwin)
        }
        androidMain.dependencies {
            api(project(":core-android"))
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.navigation.compose)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(project.dependencies.platform(libs.compose.bom))
            implementation(libs.compose.ui)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.material.icons)
            implementation(libs.compose.ui.tooling.preview)
            implementation(libs.media3.ui)
            // Casting to Chromecast through Jellyfin's receiver app.
            implementation(libs.play.services.cast.framework)
            implementation(libs.androidx.mediarouter)
            implementation(libs.media3.session)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.okhttp)
        }
    }
}
