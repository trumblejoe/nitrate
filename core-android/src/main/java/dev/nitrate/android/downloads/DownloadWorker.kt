package dev.nitrate.android.downloads

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.nitrate.android.AppContainer
import dev.nitrate.core.downloads.DownloadScheduler
import dev.nitrate.core.downloads.DownloadSizing
import dev.nitrate.core.downloads.DownloadState
import dev.nitrate.core.downloads.QueueResult
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Runs the download queue, or the offline watch-state sync, as WorkManager jobs. */
class WorkManagerScheduler(private val context: Context) : DownloadScheduler {
    override fun schedule(wifiOnly: Boolean) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(Constraints(requiredNetworkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED, requiresStorageNotLow = true))
            .build()
        // Appended, so an item queued while the last one finishes still gets a run.
        WorkManager.getInstance(context).enqueueUniqueWork(QUEUE_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    override fun scheduleSync() {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(KEY_SYNC to true))
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(SYNC_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    internal companion object {
        const val QUEUE_WORK = "nitrate-downloads"
        const val SYNC_WORK = "nitrate-watch-sync"
        const val KEY_SYNC = "sync"
    }
}

class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as? AppContainer.Owner)?.container ?: return Result.failure()
        // Started by WorkManager after the app was closed: load the saved sign-in first.
        if (container.server.userId == null) container.server.restoreSession()
        val downloads = container.downloads

        if (inputData.getBoolean(WorkManagerScheduler.KEY_SYNC, false)) {
            downloads.syncWatchState()
            val left = downloads.downloads.first().count { it.pendingSync }
            return if (left > 0) Result.retry() else Result.success()
        }

        val result = coroutineScope {
            val notifications = launch {
                downloads.downloads.collectLatest { list ->
                    val active = list.firstOrNull { it.state == DownloadState.DOWNLOADING } ?: return@collectLatest
                    val queued = list.count { it.state == DownloadState.QUEUED }
                    runCatching { setForeground(foregroundInfo(active.item.displayTitle, active.item.episodeLabel, active.progress, active.bytesDownloaded, queued)) }
                    delay(1_000) // At most one notification update a second.
                }
            }
            downloads.runQueue().also { notifications.cancel() }
        }
        downloads.syncWatchState()
        return if (result == QueueResult.RETRY) Result.retry() else Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Downloading", null, null, 0, 0)

    private fun foregroundInfo(title: String, subtitle: String?, progress: Float?, bytes: Long, queued: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
        }
        val open = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)?.let {
            PendingIntent.getActivity(applicationContext, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val detail = listOfNotNull(subtitle, DownloadSizing.formatBytes(bytes), if (queued > 0) "$queued more queued" else null).joinToString(" · ")
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(detail)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setProgress(100, ((progress ?: 0f) * 100).toInt(), progress == null)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private companion object {
        const val CHANNEL = "downloads"
        const val NOTIFICATION_ID = 7359
    }
}
