package dev.nitrate.android.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import java.net.URL
import java.security.MessageDigest

/** Where an update stands. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: AppRelease) : UpdateState
    data class Downloading(val release: AppRelease, val progress: Float) : UpdateState

    /** Android's installer is showing (or about to show) its confirmation. */
    data class Installing(val release: AppRelease) : UpdateState

    /** Android needs permission for Nitrate to install apps; [settings] opens that switch. */
    data class NeedsPermission(val release: AppRelease, val settings: Intent) : UpdateState
    data class Failed(val message: String, val release: AppRelease? = null) : UpdateState
}

/**
 * Keeps the app up to date from the project's GitHub releases: finds a newer build for this app
 * and channel ([UpdateChannel.forAppId]), downloads it, checks it against the release's
 * SHA256SUMS, and hands it to Android's installer (which always asks the user to confirm).
 */
class AppUpdater(private val context: Context, private val app: String) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    val appId: String = context.packageName
    val channel: UpdateChannel = UpdateChannel.forAppId(appId)
    val versionCode: Int = runCatching {
        val info = context.packageManager.getPackageInfo(appId, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
    }.getOrDefault(0)
    val versionName: String = runCatching { context.packageManager.getPackageInfo(appId, 0).versionName }.getOrNull().orEmpty()

    private val _autoCheck = MutableStateFlow(prefs.getBoolean(KEY_AUTO, true))

    /** Look for updates by itself when the app opens. */
    val autoCheck: StateFlow<Boolean> = _autoCheck

    fun setAutoCheck(on: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO, on).apply()
        _autoCheck.value = on
    }

    /** On app start: checks if automatic checks are on and the last one was a while ago. */
    fun checkIfDue() {
        if (!_autoCheck.value || channel == UpdateChannel.NONE) return
        if (System.currentTimeMillis() - prefs.getLong(KEY_LAST, 0) < CHECK_EVERY_MS) return
        check(quiet = true)
    }

    /** Looks now. [quiet] leaves the state Idle (not "up to date") when there's nothing new. */
    fun check(quiet: Boolean = false) {
        if (channel == UpdateChannel.NONE) {
            _state.value = UpdateState.Failed("This is a development build; it doesn't update from releases.")
            return
        }
        if (_state.value is UpdateState.Downloading || _state.value is UpdateState.Installing) return
        _state.value = UpdateState.Checking
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { allReleases() } }
            prefs.edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
            result.onSuccess { releases ->
                val newest = Releases.newest(releases, versionCode, channel)
                _state.value = when {
                    newest == null -> if (quiet) UpdateState.Idle else UpdateState.UpToDate
                    // An update the user put off stays quiet until something newer comes.
                    quiet && prefs.getString(KEY_SKIPPED, null) == newest.tag -> UpdateState.Idle
                    else -> UpdateState.Available(newest)
                }
            }.onFailure { e ->
                _state.value = if (quiet) UpdateState.Idle else UpdateState.Failed("Couldn't check for updates: ${e.message ?: "no connection"}")
            }
        }
    }

    /** "Later": don't bring this one up again on its own. */
    fun skip(release: AppRelease) {
        prefs.edit().putString(KEY_SKIPPED, release.tag).apply()
        _state.value = UpdateState.Idle
    }

    /** Back to offering [release], e.g. while the user is off allowing installs. */
    fun offer(release: AppRelease) {
        _state.value = UpdateState.Available(release)
    }

    fun dismiss() {
        _state.value = UpdateState.Idle
    }

    /** Downloads [release], verifies it, and opens Android's installer. */
    fun install(release: AppRelease) {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$appId")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            _state.value = UpdateState.NeedsPermission(release, settings)
            return
        }
        _state.value = UpdateState.Downloading(release, 0f)
        scope.launch {
            runCatching {
                val apk = withContext(Dispatchers.IO) { download(release) }
                _state.value = UpdateState.Installing(release)
                withContext(Dispatchers.IO) { commit(apk) }
            }.onFailure { e -> _state.value = UpdateState.Failed("Update failed: ${e.message ?: e.javaClass.simpleName}", release) }
        }
    }

    private fun allReleases(): List<AppRelease> = buildList {
        for (page in 1..10) {
            val body = fetch(Releases.listUrl(page))
            addAll(Releases.parse(body, app))
            if (Releases.pageCount(body) < Releases.PAGE_SIZE) break
        }
    }

    private fun download(release: AppRelease): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, release.apkName)
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
        release.sumsUrl?.let { url ->
            val expected = Releases.checksum(fetch(url), release.apkName) ?: error("The release doesn't list a checksum for ${release.apkName}")
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != expected) {
                file.delete()
                error("The download didn't match the release (checksum mismatch)")
            }
        }
        return file
    }

    private fun commit(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(appId)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("nitrate.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
        }
    }

    /** From [InstallResultReceiver]: the installer's verdict. */
    internal fun onInstallResult(status: Int, message: String?) {
        val release = (_state.value as? UpdateState.Installing)?.release
        if (status != PackageInstaller.STATUS_SUCCESS && status != PackageInstaller.STATUS_PENDING_USER_ACTION) {
            _state.value = if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
                release?.let { UpdateState.Available(it) } ?: UpdateState.Idle
            } else {
                UpdateState.Failed("Android didn't install the update: ${message ?: "status $status"}", release)
            }
        }
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("User-Agent", "Nitrate-updater")
        instanceFollowRedirects = true
    }

    private fun fetch(url: String): String = open(url).let { c ->
        if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
        c.inputStream.use { it.readBytes().decodeToString() }
    }

    companion object {
        private const val KEY_AUTO = "auto_check"
        private const val KEY_LAST = "last_check"
        private const val KEY_SKIPPED = "skipped_tag"
        private const val CHECK_EVERY_MS = 6 * 60 * 60_000L

        /** Set by the app container so the receiver can report back. */
        @Volatile
        internal var current: AppUpdater? = null
    }
}

/** Receives Android's installer result; shows its confirmation screen when it asks for one. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT)
            confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let(context::startActivity)
        }
        AppUpdater.current?.onInstallResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
