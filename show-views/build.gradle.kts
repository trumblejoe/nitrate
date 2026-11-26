// Show views every app draws the same way (library All views such as Vortex, Volumes and Collage):
// Compose Multiplatform over :core, used by :tv (Android TV) and :mobile-ui (phone, iOS, desktop).
// Input is the app's: keys (D-pad, arrows) and touch are both handled here; Back is handed in.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    android {
        namespace = "dev.nitrate.showviews"
        compileSdk { version = release(37) { minorApiLevel = 2 } }
        minSdk = 24
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    iosArm64()
    iosSimulatorArm64()
    jvm("desktop") {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(libs.coil.compose)
            // Pins the lifecycle libraries Compose pulls in to the versions the apps use.
            implementation(libs.jb.lifecycle.runtime.compose)
        }
        androidMain.dependencies {
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(project.dependencies.platform(libs.compose.bom))
            implementation(libs.compose.ui)
            implementation(libs.compose.foundation)
        }
    }
}
