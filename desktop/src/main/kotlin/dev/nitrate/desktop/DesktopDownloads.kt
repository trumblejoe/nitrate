package dev.nitrate.desktop

import dev.nitrate.core.downloads.DownloadScheduler
import dev.nitrate.core.downloads.DownloadState
import dev.nitrate.core.downloads.QueueResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Runs the download queue inside the app while it's open (a PC has no background scheduler for
 * Nitrate); whatever isn't finished carries on the next time it opens ([resume]). Without a
 * connection it tries again every half minute for a while. Wi-Fi only applies on Android.
 */
class DesktopDownloadScheduler(private val container: () -> dev.nitrate.android.AppContainer) : DownloadScheduler {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var running: Job? = null
    @Volatile private var again = false
    private var smart: Job? = null

    override fun schedule(wifiOnly: Boolean) {
        if (running?.isActive == true) {
            // Queued while a run is going: it looks again when it's done.
            again = true
            return
        }
        running = scope.launch {
            var retries = 0
            do {
                again = false
                val result = runCatching { container().downloads.runQueue() }.getOrDefault(QueueResult.RETRY)
                runCatching { container().trips.afterDownloads() }
                if (result == QueueResult.RETRY && retries++ < RETRIES) {
                    delay(RETRY_MS)
                    again = true
                }
            } while (again)
        }
    }

    override fun scheduleSync() {
        scope.launch { runCatching { container().downloads.syncWatchState() } }
    }

    /** Smart downloads topped up every few hours while the app is open. */
    override fun scheduleSmartRefresh(enabled: Boolean, wifiOnly: Boolean) {
        smart?.cancel()
        if (!enabled) return
        smart = scope.launch {
            while (true) {
                delay(SMART_EVERY_MS)
                runCatching { container().downloads.refreshSmart() }
                runCatching { container().trips.pack() }
            }
        }
    }

    /** When the app opens: carries on with anything still waiting or half done. */
    fun resume() {
        scope.launch {
            // Once the saved sign-in is loaded (downloads are per account).
            repeat(60) { if (container().server.userId == null) delay(1_000) }
            val waiting = runCatching {
                container().downloads.downloads.first().any { it.state == DownloadState.QUEUED || it.state == DownloadState.DOWNLOADING }
            }.getOrDefault(false)
            if (waiting) schedule(wifiOnly = false)
        }
    }

    private companion object {
        const val RETRIES = 20
        const val RETRY_MS = 30_000L
        const val SMART_EVERY_MS = 4 * 60 * 60_000L
    }
}
