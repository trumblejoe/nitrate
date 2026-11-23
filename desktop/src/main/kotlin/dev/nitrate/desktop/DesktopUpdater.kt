package dev.nitrate.desktop

import dev.nitrate.android.update.AppUpdater
import dev.nitrate.android.update.UpdateState
import dev.nitrate.core.update.AppRelease
import dev.nitrate.core.update.Releases
import dev.nitrate.core.update.UpdateChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/**
 * Keeps the desktop app up to date from the project's GitHub releases, like Android's updater:
 * finds a newer build on this build's channel (release builds: full releases; dev builds: dev
 * pre-releases; local builds: never), downloads `nitrate-desktop-<name>.msi`, checks it against
 * the release's SHA256SUMS and runs Windows' installer (which upgrades this install and asks
 * before anything changes), then closes so the files can be replaced. A portable copy (the ZIP)
 * can't replace itself: it opens the release's page to download the new ZIP.
 */
class DesktopUpdater(
    private val version: DesktopVersion,
    private val prefsFile: File,
    private val downloadDir: File,
    /** Where the running app was installed from (jpackage's launcher says); null when run from Gradle. */
    private val appDir: File? = System.getProperty("jpackage.app-path")?.let { File(it).parentFile },
    private val fetch: (String) -> String = ::fetchText,
) : AppUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs = FileKeyValues(prefsFile)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    override val state: StateFlow<UpdateState> = _state
    override val channel: UpdateChannel = version.channel
    override val versionName: String = version.name

    private val _autoCheck = MutableStateFlow(prefs.get(KEY_AUTO) != "false")
    override val autoCheck: StateFlow<Boolean> = _autoCheck

    /** A portable copy (unzipped by hand, with the marker the ZIP carries) rather than an installed one. */
    val portable: Boolean get() = appDir?.let { File(it.parentFile ?: it, PORTABLE_MARKER).exists() || File(it, PORTABLE_MARKER).exists() } ?: false

    private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    override fun setAutoCheck(on: Boolean) {
        prefs.put(KEY_AUTO, on.toString())
        _autoCheck.value = on
    }

    override fun checkIfDue() {
        if (!_autoCheck.value || channel == UpdateChannel.NONE) return
        val last = prefs.get(KEY_LAST)?.toLongOrNull() ?: 0
        if (System.currentTimeMillis() - last < CHECK_EVERY_MS) return
        check(quiet = true)
    }

    override fun check(quiet: Boolean) {
        if (channel == UpdateChannel.NONE) {
            _state.value = UpdateState.Failed("This is a development build; it doesn't update from releases.")
            return
        }
        if (_state.value is UpdateState.Downloading || _state.value is UpdateState.Installing) return
        _state.value = UpdateState.Checking
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { allReleases() } }
            prefs.put(KEY_LAST, System.currentTimeMillis().toString())
            result.onSuccess { releases ->
                val newest = Releases.newest(releases, version.code, channel)
                _state.value = when {
                    newest == null -> if (quiet) UpdateState.Idle else UpdateState.UpToDate
                    quiet && prefs.get(KEY_SKIPPED) == newest.tag -> UpdateState.Idle
                    else -> UpdateState.Available(newest)
                }
            }.onFailure { e ->
                _state.value = if (quiet) UpdateState.Idle else UpdateState.Failed("Couldn't check for updates: ${e.message ?: "no connection"}")
            }
        }
    }

    override fun skip(release: AppRelease) {
        prefs.put(KEY_SKIPPED, release.tag)
        _state.value = UpdateState.Idle
    }

    override fun offer(release: AppRelease) {
        _state.value = UpdateState.Available(release)
    }

    override fun dismiss() {
        _state.value = UpdateState.Idle
    }

    override fun install(release: AppRelease) {
        if (portable || !windows || appDir == null) {
            // Nothing here can replace itself: the release's page has the new copy.
            dev.nitrate.mobile.platform.openInBrowser(releasePage(release))
            _state.value = UpdateState.Idle
            return
        }
        _state.value = UpdateState.Downloading(release, 0f)
        scope.launch {
            runCatching {
                val msi = withContext(Dispatchers.IO) { download(release) }
                _state.value = UpdateState.Installing(release)
                // Windows' installer upgrades this install (same upgrade code) once Nitrate has closed.
                withContext(Dispatchers.IO) { ProcessBuilder("msiexec", "/i", msi.absolutePath).start() }
                kotlinx.coroutines.delay(500)
                kotlin.system.exitProcess(0)
            }.onFailure { e -> _state.value = UpdateState.Failed("Update failed: ${e.message ?: e.javaClass.simpleName}", release) }
        }
    }

    private fun releasePage(release: AppRelease) = "https://github.com/${Releases.REPO}/releases/tag/${release.tag}"

    private fun allReleases(): List<AppRelease> = buildList {
        for (page in 1..10) {
            val body = fetch(Releases.listUrl(page))
            addAll(Releases.parse(body, APP, ".msi"))
            if (Releases.pageCount(body) < Releases.PAGE_SIZE) break
        }
    }

    private fun download(release: AppRelease): File {
        downloadDir.mkdirs()
        downloadDir.listFiles()?.forEach { it.delete() }
        val file = File(downloadDir, release.apkName)
        val digest = MessageDigest.getInstance("SHA-256")
        val connection = open(release.apkUrl)
        val total = connection.contentLengthLong.takeIf { it > 0 }
        connection.inputStream.use { input ->
            file.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                var read = 0L
                var lastShown = 0f
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    digest.update(buffer, 0, n)
                    read += n
                    if (total != null) {
                        val p = read.toFloat() / total
                        if (p - lastShown > 0.01f) {
                            lastShown = p
                            _state.value = UpdateState.Downloading(release, p)
                        }
                    }
                }
            }
        }
        // Only install what the release says it published.
        val sums = release.sumsUrl ?: error("The release has no checksums")
        val expected = Releases.checksum(fetch(sums), release.apkName) ?: error("The release doesn't list a checksum for ${release.apkName}")
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            file.delete()
            error("The download didn't match the release (checksum mismatch)")
        }
        return file
    }

    companion object {
        const val APP = "desktop"

        /** The file the portable ZIP carries beside the app, so it knows not to run the installer. */
        const val PORTABLE_MARKER = "portable.txt"
        private const val KEY_AUTO = "auto_check"
        private const val KEY_LAST = "last_check"
        private const val KEY_SKIPPED = "skipped_tag"
        private const val CHECK_EVERY_MS = 6 * 60 * 60_000L

        private fun open(url: String): HttpURLConnection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Nitrate-updater")
            instanceFollowRedirects = true
        }

        fun fetchText(url: String): String = open(url).let { c ->
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            c.inputStream.use { it.readBytes().decodeToString() }
        }
    }
}
