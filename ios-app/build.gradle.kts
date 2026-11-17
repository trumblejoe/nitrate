// The iPhone/iPad app's Kotlin side: Compose Multiplatform screens over :core, built into the
// NitrateKit framework that the Xcode project in iosApp/ hosts. Linking needs macOS (Actions);
// on Linux the Kotlin compiles for iOS (compileKotlinIosArm64).
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "NitrateKit"
            isStatic = true
        }
    }

    compilerOptions {
        optIn.add("kotlinx.cinterop.ExperimentalForeignApi")
        optIn.add("kotlinx.cinterop.BetaInteropApi")
    }

    sourceSets {
        iosMain.dependencies {
            implementation(project(":core"))
            implementation(project(":app-core"))
            implementation(project(":mobile-ui"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(compose.material3)
            implementation("org.jetbrains.compose.material:material-icons-core:1.7.3")
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor3)
            implementation(libs.ktor.client.darwin)
        }
    }
}
