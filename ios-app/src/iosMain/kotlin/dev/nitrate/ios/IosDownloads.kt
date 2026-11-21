package dev.nitrate.ios

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
import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundTaskInvalid

/**
 * Runs the download queue inside the app: while it's open, and for the extra time iOS gives an
 * app that's just been left (a background task, usually a few minutes). Whatever isn't finished
 * carries on the next time the app opens ([resume]). Wi-Fi only isn't told apart here: iOS gives
 * no simple way to ask, so the setting applies on Android only.
 */
class IosDownloadScheduler : DownloadScheduler {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var running: Job? = null
    private var again = false

    override fun schedule(wifiOnly: Boolean) {
        if (running?.isActive == true) {
            // Queued while a run is going: it looks again when it's done.
            again = true
            return
        }
        running = scope.launch {
            val app = UIApplication.sharedApplication
            var task = UIBackgroundTaskInvalid
            task = app.beginBackgroundTaskWithName("Nitrate downloads") {
                app.endBackgroundTask(task)
                task = UIBackgroundTaskInvalid
            }
            try {
                var retries = 0
                do {
                    again = false
                    val result = runCatching { IosApp.container.downloads.runQueue() }.getOrDefault(QueueResult.RETRY)
                    // No connection: try again for a while, then leave it for the next time the app opens.
                    if (result == QueueResult.RETRY && retries++ < RETRIES) {
                        delay(RETRY_MS)
                        again = true
                    }
                } while (again)
            } finally {
                if (task != UIBackgroundTaskInvalid) app.endBackgroundTask(task)
            }
        }
    }

    override fun scheduleSync() {
        scope.launch { runCatching { IosApp.container.downloads.syncWatchState() } }
    }

    /** When the app opens: carries on with anything still waiting or half done. */
    fun resume() {
        scope.launch {
            // Once the saved sign-in is loaded (downloads are per account).
            repeat(60) { if (IosApp.container.server.userId == null) delay(1_000) }
            val waiting = runCatching {
                IosApp.container.downloads.downloads.first().any { it.state == DownloadState.QUEUED || it.state == DownloadState.DOWNLOADING }
            }.getOrDefault(false)
            if (waiting) schedule(wifiOnly = false)
        }
    }

    private companion object {
        const val RETRIES = 20
        const val RETRY_MS = 30_000L
    }
}
