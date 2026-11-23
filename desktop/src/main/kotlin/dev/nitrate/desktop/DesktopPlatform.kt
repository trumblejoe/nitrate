package dev.nitrate.desktop

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import dev.nitrate.android.AppPlatform
import dev.nitrate.android.AppStores
import dev.nitrate.android.notifications.NewEpisodeNotifier
import dev.nitrate.android.playback.ThemeMusic
import dev.nitrate.android.storage.KeyValues
import dev.nitrate.android.update.AppUpdater
import dev.nitrate.core.MediaServer
import dev.nitrate.core.downloads.DownloadScheduler
import dev.nitrate.core.jellyfin.ClientIdentity
import dev.nitrate.core.pages.DeviceKind
import dev.nitrate.core.playback.DesktopPlayback
import dev.nitrate.core.playback.DeviceCapabilities
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import okio.Path.Companion.toPath
import java.io.File
import java.util.Properties

/** Where the desktop app keeps its things, as each system expects. */
class DesktopFolders(
    /** Settings, sign-ins, scripts: Windows %APPDATA%\Nitrate, macOS Application Support, Linux XDG data. */
    val data: File,
    /** Thrown away freely: Windows %LOCALAPPDATA%\Nitrate\cache, macOS Caches, Linux XDG cache. */
    val cache: File,
    /** Downloaded videos: the user's Videos (Movies on macOS) folder, in Nitrate. */
    val downloads: File,
) {
    companion object {
        /** The system's own places; NITRATE_HOME puts everything in one folder instead (tests, portable use). */
        fun system(env: (String) -> String? = System::getenv, os: String = System.getProperty("os.name").orEmpty()): DesktopFolders {
            val home = File(System.getProperty("user.home"))
            env("NITRATE_HOME")?.takeIf { it.isNotBlank() }?.let { root ->
                val r = File(root)
                return DesktopFolders(File(r, "data"), File(r, "cache"), File(r, "downloads"))
            }
            return when {
                os.startsWith("Windows", ignoreCase = true) -> DesktopFolders(
                    data = File(env("APPDATA") ?: "$home\\AppData\\Roaming", "Nitrate"),
                    cache = File(env("LOCALAPPDATA") ?: "$home\\AppData\\Local", "Nitrate\\cache"),
                    downloads = File(home, "Videos\\Nitrate"),
                )
                os.startsWith("Mac", ignoreCase = true) -> DesktopFolders(
                    data = File(home, "Library/Application Support/Nitrate"),
                    cache = File(home, "Library/Caches/Nitrate"),
                    downloads = File(home, "Movies/Nitrate"),
                )
                else -> DesktopFolders(
                    data = File(env("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.local/share", "nitrate"),
                    cache = File(env("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.cache", "nitrate"),
                    downloads = File(home, "Videos/Nitrate"),
                )
            }
        }
    }
}

/** Small values kept in a properties file (written whole on each change; they're few and small). */
class FileKeyValues(private val file: File) : KeyValues {
    private val values = Properties().apply { runCatching { file.inputStream().use { load(it) } } }

    @Synchronized
    override fun get(key: String): String? = values.getProperty(key)

    @Synchronized
    override fun put(key: String, value: String) {
        values.setProperty(key, value)
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.outputStream().use { values.store(it, null) }
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }
}

/**
 * The desktop's side of [dev.nitrate.android.AppContainer]: DataStore files in the user's
 * app-data folder, OkHttp, the mpv profile, downloads run in the app, updates from GitHub.
 */
class DesktopPlatform(
    val version: DesktopVersion,
    val folders: DesktopFolders = DesktopFolders.system(),
    /** What the player passes through to a receiver (the desktop player settings), read when the profile is built. */
    private val bitstream: () -> Set<String> = { emptySet() },
) : AppPlatform {
    init {
        listOf(folders.data, folders.cache).forEach { it.mkdirs() }
    }

    private val settings = FileKeyValues(File(folders.data, "device.properties"))

    override val deviceKind: DeviceKind = DeviceKind.TABLET
    override val identity: ClientIdentity = ClientIdentity(
        clientName = "Nitrate",
        version = version.name,
        deviceId = settings.get("device_id") ?: kotlin.uuid.Uuid.random().toString().also { settings.put("device_id", it) },
        deviceName = computerName(),
    )
    override val appKind = "desktop"
    override val filesDir: String = folders.data.absolutePath
    override val cacheDir: String = folders.cache.absolutePath
    override val downloadDir: String = folders.downloads.absolutePath

    private fun store(name: String): DataStore<Preferences> =
        PreferenceDataStoreFactory.createWithPath(produceFile = { File(folders.data, "datastore/$name.preferences_pb").absolutePath.toPath() })

    override val stores = AppStores(
        display = store("display_settings"),
        playback = store("playback_settings"),
        session = store("session"),
        search = store("search_history"),
        plugins = store("plugins"),
        profiles = store("profiles"),
        backup = store("device_backup"),
    )

    override val libraryKeyValues: KeyValues = FileKeyValues(File(folders.data, "libraries.properties"))

    // OkHttp allows 16 requests per server (pages ask for all their rows together).
    override fun serverEngine(): HttpClientEngine = OkHttp.create {
        config { dispatcher(okhttp3.Dispatcher().apply { maxRequestsPerHost = 16 }) }
    }

    override fun downloadEngine(): HttpClientEngine = OkHttp.create()

    override fun downloadScheduler(): DownloadScheduler = DesktopApp.downloads

    override fun probeCapabilities(): DeviceCapabilities {
        val screen = runCatching { java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.displayMode }.getOrNull()
        val os = "${System.getProperty("os.name")} ${System.getProperty("os.version")}"
        return DesktopPlayback.capabilities(
            model = "${computerName()} ($os, mpv)",
            displayWidth = screen?.width?.takeIf { it > 0 } ?: 1920,
            displayHeight = screen?.height?.takeIf { it > 0 } ?: 1080,
            bitstream = bitstream(),
        )
    }

    // Theme songs and new-episode notifications aren't on the desktop yet.
    override fun themeMusic(server: MediaServer): ThemeMusic = ThemeMusic.None
    override fun newEpisodes(server: MediaServer): NewEpisodeNotifier = NewEpisodeNotifier.None

    override fun updater(): AppUpdater = DesktopUpdater(version, File(folders.data, "updates.properties"), File(folders.cache, "updates"))

    // OkHttp re-resolves on each request; the server address is re-picked when the app opens.
    override fun watchNetwork(onChange: () -> Unit) = Unit

    override fun timestamp(): String = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", java.util.Locale.US).format(java.util.Date())

    private companion object {
        fun computerName(): String = System.getenv("COMPUTERNAME")?.takeIf { it.isNotBlank() }
            ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() && it != "localhost" }
            ?: "Nitrate desktop"
    }
}

/** This build's version and update channel (from the build: release.yml sets them). */
data class DesktopVersion(val name: String, val code: Int, val channel: dev.nitrate.core.update.UpdateChannel) {
    companion object {
        fun load(): DesktopVersion {
            val p = Properties()
            DesktopVersion::class.java.getResourceAsStream("/dev/nitrate/desktop/version.properties")?.use { p.load(it) }
            val channel = when (p.getProperty("channel")) {
                "stable" -> dev.nitrate.core.update.UpdateChannel.STABLE
                "dev" -> dev.nitrate.core.update.UpdateChannel.DEV
                else -> dev.nitrate.core.update.UpdateChannel.NONE
            }
            return DesktopVersion(p.getProperty("name") ?: "0.1.0", p.getProperty("code")?.toIntOrNull() ?: 1, channel)
        }
    }
}
