// The app's own logic over :core that both platforms share: on-device stores, view models and,
// step by step, the app container. Android wires it up in :core-android, iOS in :ios-app.
// (Packages keep their dev.nitrate.android.* names from before the move.)
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    android {
        namespace = "dev.nitrate.app"
        compileSdk = 37
        minSdk = 24
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    iosArm64()
    iosSimulatorArm64()

    compilerOptions {
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
        optIn.add("kotlinx.cinterop.ExperimentalForeignApi")
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            api(libs.androidx.datastore.preferences.core)
            implementation(libs.kotlinx.serialization.json)
            api(libs.androidx.lifecycle.viewmodel)
            implementation(libs.atomicfu)
        }
    }
}
