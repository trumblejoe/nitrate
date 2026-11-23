// The desktop app (Windows first; Linux and macOS run it too): a window showing the phone/tablet
// screens from :mobile-ui (Compose for Desktop on the JVM), playing in libmpv. Packaged as an MSI
// and a portable ZIP with its own Java runtime (release.yml's "windows" job).
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
    }
}

// Release builds set these (see .github/workflows/release.yml); local builds are "0.1.0" and never update.
val versionName: String = System.getenv("NITRATE_VERSION_NAME") ?: "0.1.0"
val versionCode: String = System.getenv("NITRATE_VERSION_CODE") ?: "1"
val channel: String = System.getenv("NITRATE_DESKTOP_CHANNEL") ?: "none"
val devBuild = channel == "dev"

// The version and update channel, read by the app at run time.
val versionFile = tasks.register("desktopVersion") {
    val out = layout.buildDirectory.dir("generated/version")
    val values = mapOf("name" to versionName, "code" to versionCode, "channel" to channel)
    inputs.property("values", values)
    outputs.dir(out)
    doLast {
        val dir = out.get().asFile.resolve("dev/nitrate/desktop").apply { mkdirs() }
        dir.resolve("version.properties").writeText(values.entries.joinToString("\n") { "${it.key}=${it.value}" } + "\n")
    }
}

sourceSets {
    main {
        resources.srcDir(versionFile)
        // The licence texts the Open-source page shows, shared with the Android apps.
        resources.srcDir("../core-android/src/main/assets")
    }
    // Test fixtures shared with the phone and TV apps (fake server, sample artwork).
    test {
        kotlin.srcDir("../testing/src")
        resources.srcDir("../testing/resources")
    }
}

dependencies {
    implementation(project(":mobile-ui"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.cmp.material.icons.core)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.ktor3)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.jna)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
    testImplementation(compose.uiTest)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.jb.navigation.compose)
}

compose.desktop {
    application {
        mainClass = "dev.nitrate.desktop.MainKt"
        jvmArgs += listOf("-Xmx1g", "-Dfile.encoding=UTF-8")

        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = if (devBuild) "Nitrate Dev" else "Nitrate"
            // MSI versions are three numbers (the first at most 255): a release's own, a dev build's run number.
            packageVersion = Regex("""^\d+\.\d+\.\d+$""").find(versionName)?.value ?: "0.0.${versionCode.toInt() % 65_536}"
            description = "Jellyfin client"
            vendor = "Nitrate"
            copyright = "Nitrate contributors"
            // libmpv and its DLLs (tools/build-libmpv-windows.sh puts them in resources/windows-x64).
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
            // What the app uses of the JDK, plus the Java Access Bridge so screen readers reach it.
            modules("java.instrument", "java.management", "java.naming", "java.net.http", "java.prefs", "java.sql", "jdk.unsupported", "jdk.crypto.ec", "jdk.accessibility")
            windows {
                iconFile.set(project.file("icons/nitrate.ico"))
                menuGroup = "Nitrate"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                // Fixed for good: Windows upgrades installs by it (dev builds are a separate app, as on Android).
                upgradeUuid = if (devBuild) "6f0c4f2e-3a8b-4c1e-9d55-1e0b7a2f6c41" else "b7d3a1f0-5c2e-4e8a-a6f1-2d9c4b8e7a13"
            }
            linux { iconFile.set(project.file("icons/nitrate.png")) }
        }
    }
}

tasks.test {
    // Compose's tests draw offscreen with Skia; no display needed.
    systemProperty("java.awt.headless", "true")
    systemProperty("skiko.renderApi", "SOFTWARE")
}
