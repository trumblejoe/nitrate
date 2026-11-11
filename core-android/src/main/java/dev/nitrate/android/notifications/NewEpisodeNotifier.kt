package dev.nitrate.android.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.nitrate.android.AppContainer
import dev.nitrate.core.MediaServer
import dev.nitrate.core.notifications.NewEpisodes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

private val Context.notificationPrefs by preferencesDataStore(name = "notifications")

/**
 * Tells this device about new episodes of shows the signed-in user follows (favourites, or
 * partway through). Checks every few hours in the background, and straight away when the server
 * says items were added. Off until switched on.
 */
class NewEpisodeNotifier(private val context: Context, private val server: MediaServer) {
    private val enabledKey = booleanPreferencesKey("new_episodes")
    private fun seenKey(account: String) = longPreferencesKey("seen:$account")

    val enabled: Flow<Boolean> = context.notificationPrefs.data.map { it[enabledKey] ?: false }

    suspend fun setEnabled(on: Boolean) {
        context.notificationPrefs.edit { it[enabledKey] = on }
        schedule(on)
    }

    /** Keeps the background check scheduled (or not) to match the setting; call at startup. */
    suspend fun ensureScheduled() = schedule(enabled.first())

    private fun schedule(on: Boolean) {
        // WorkManager isn't there in some test environments; the setting still saves.
        val work = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
        if (!on) {
            work.cancelUniqueWork(PERIODIC)
            return
        }
        val request = PeriodicWorkRequestBuilder<NewEpisodeWorker>(3, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Checks soon (after a library change), once even if asked several times. */
    fun checkSoon() {
        val request = OneTimeWorkRequestBuilder<NewEpisodeWorker>().setInitialDelay(30, TimeUnit.SECONDS).build()
        runCatching { WorkManager.getInstance(context).enqueueUniqueWork(SOON, ExistingWorkPolicy.KEEP, request) }
    }

    /** Looks for new episodes and posts a notification per show. */
    suspend fun check() {
        if (!enabled.first()) return
        val account = server.currentAccountKey ?: return
        val recent = server.recentEpisodes()
        val seen = context.notificationPrefs.data.first()[seenKey(account)]
        val newest = NewEpisodes.newestAdded(recent, seen ?: 0)
        // The first check only notes where things stand: no flood of old episodes.
        if (seen != null) {
            val alerts = NewEpisodes.alerts(recent, server.followedSeries(), seen)
            alerts.forEach(::post)
        }
        context.notificationPrefs.edit { it[seenKey(account)] = newest }
    }

    private fun post(alert: dev.nitrate.core.notifications.NewEpisodeAlert) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "New episodes", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            putExtra(EXTRA_OPEN_ITEM, alert.openItemId)
            addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(dev.nitrate.android.R.drawable.ic_stat_new_episode)
            .setContentTitle(alert.title)
            .setContentText(alert.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.text))
            .setAutoCancel(true)
            .apply {
                if (open != null) {
                    setContentIntent(PendingIntent.getActivity(context, alert.seriesId.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                }
            }
            .build()
        // One per show: a later check replaces it with the up-to-date count.
        NotificationManagerCompat.from(context).notify(alert.seriesId.hashCode(), notification)
    }

    companion object {
        const val EXTRA_OPEN_ITEM = "dev.nitrate.OPEN_ITEM"
        private const val CHANNEL = "new_episodes"
        private const val PERIODIC = "new-episodes"
        private const val SOON = "new-episodes-soon"
    }
}

class NewEpisodeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as? AppContainer.Owner)?.container ?: return Result.failure()
        // Started by WorkManager after the app was closed: load the saved sign-in first.
        if (container.server.userId == null) container.server.restoreSession()
        return runCatching { container.newEpisodes.check() }.fold({ Result.success() }, { Result.retry() })
    }
}
