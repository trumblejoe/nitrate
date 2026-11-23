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
import dev.nitrate.core.notifications.NewSeasons
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

private val Context.notificationPrefs by preferencesDataStore(name = "notifications")

/**
 * Tells this device about new episodes of shows the signed-in user follows (favourites, or
 * partway through), and about new seasons of shows they follow or have watched before. Checks
 * every few hours in the background, and straight away when the server says items were added.
 * Each kind is off until switched on.
 */
class AndroidNewEpisodeNotifier(private val context: Context, private val server: MediaServer) : NewEpisodeNotifier {
    private val enabledKey = booleanPreferencesKey("new_episodes")
    private val seasonsKey = booleanPreferencesKey("new_seasons")
    private fun seenKey(account: String) = longPreferencesKey("seen:$account")
    private val invitesKey = booleanPreferencesKey("watch_invites")
    private val requestsKey = booleanPreferencesKey("partner_requests")
    private fun notifiedKey(account: String) = androidx.datastore.preferences.core.stringSetPreferencesKey("notified:$account")

    /** Watch-together invites and watch-partner requests: on until switched off (they're for you). */
    override val invitesEnabled: Flow<Boolean> = context.notificationPrefs.data.map { it[invitesKey] ?: true }
    override val partnerRequestsEnabled: Flow<Boolean> = context.notificationPrefs.data.map { it[requestsKey] ?: true }

    override suspend fun setInvitesEnabled(on: Boolean) {
        context.notificationPrefs.edit { it[invitesKey] = on }
        scheduleInvites()
    }

    override suspend fun setPartnerRequestsEnabled(on: Boolean) {
        context.notificationPrefs.edit { it[requestsKey] = on }
        scheduleInvites()
    }

    /** Every 15 minutes (Android's shortest) while either is on; the open app checks every 30 s itself. */
    private suspend fun scheduleInvites() {
        val work = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
        if (!invitesEnabled.first() && !partnerRequestsEnabled.first()) {
            work.cancelUniqueWork(INVITES)
            return
        }
        val request = PeriodicWorkRequestBuilder<InviteWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        work.enqueueUniquePeriodicWork(INVITES, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Posts each invite and request once (remembered per account, so a later check doesn't repeat it). */
    override suspend fun checkInvites() {
        val invitesOn = invitesEnabled.first()
        val requestsOn = partnerRequestsEnabled.first()
        if (!invitesOn && !requestsOn) return
        val account = server.currentAccountKey ?: return
        val notified = context.notificationPrefs.data.first()[notifiedKey(account)].orEmpty()
        val fresh = mutableSetOf<String>()
        if (invitesOn) runCatching { server.watchInvites() }.getOrDefault(emptyList()).forEach { inv ->
            fresh += "i:" + inv.id
            if ("i:" + inv.id !in notified) postSocial("i:" + inv.id, "Watch party", inv.text, INVITES_CHANNEL, "Watch party invites")
        }
        if (requestsOn) runCatching { server.partners().incoming }.getOrDefault(emptyList()).forEach { r ->
            fresh += "r:" + r.id
            if ("r:" + r.id !in notified) postSocial("r:" + r.id, "Watch ${r.seriesName} together?", "${r.fromName} would like to watch ${r.seriesName} with you.", REQUESTS_CHANNEL, "Watch partner requests")
        }
        // Keep only what's still open, so the list doesn't grow.
        context.notificationPrefs.edit { it[notifiedKey(account)] = fresh }
    }

    private fun postSocial(key: String, title: String, text: String, channel: String, channelName: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(channel) == null) {
            manager.createNotificationChannel(NotificationChannel(channel, channelName, NotificationManager.IMPORTANCE_HIGH))
        }
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            putExtra(NewEpisodeNotifier.EXTRA_OPEN_INVITES, true)
            addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(dev.nitrate.android.R.drawable.ic_stat_new_episode)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .apply { if (open != null) setContentIntent(PendingIntent.getActivity(context, key.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)) }
            .build()
        NotificationManagerCompat.from(context).notify(key.hashCode(), notification)
    }

    override val enabled: Flow<Boolean> = context.notificationPrefs.data.map { it[enabledKey] ?: false }

    /** New seasons of shows followed or watched before. */
    override val seasonsEnabled: Flow<Boolean> = context.notificationPrefs.data.map { it[seasonsKey] ?: false }

    private val anyEnabled: Flow<Boolean> = context.notificationPrefs.data.map { (it[enabledKey] ?: false) || (it[seasonsKey] ?: false) }

    override suspend fun setEnabled(on: Boolean) {
        context.notificationPrefs.edit { it[enabledKey] = on }
        schedule(anyEnabled.first())
    }

    override suspend fun setSeasonsEnabled(on: Boolean) {
        context.notificationPrefs.edit { it[seasonsKey] = on }
        schedule(anyEnabled.first())
    }

    /** Keeps the background check scheduled (or not) to match the settings; call at startup. */
    override suspend fun ensureScheduled() {
        schedule(anyEnabled.first())
        scheduleInvites()
    }

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
    override fun checkSoon() {
        val request = OneTimeWorkRequestBuilder<NewEpisodeWorker>().setInitialDelay(30, TimeUnit.SECONDS).build()
        runCatching { WorkManager.getInstance(context).enqueueUniqueWork(SOON, ExistingWorkPolicy.KEEP, request) }
    }

    /** Looks for new seasons and new episodes and posts a notification per show. */
    override suspend fun check() {
        val episodesOn = enabled.first()
        val seasonsOn = seasonsEnabled.first()
        if (!episodesOn && !seasonsOn) return
        val account = server.currentAccountKey ?: return
        // A whole season added at once is many episodes: look far enough back to see its first.
        val recent = server.recentEpisodes(RECENT)
        val seen = context.notificationPrefs.data.first()[seenKey(account)]
        val newest = NewEpisodes.newestAdded(recent, seen ?: 0)
        // The first check only notes where things stand: no flood of old episodes.
        if (seen != null) {
            val followed = server.followedSeries()
            val seasons = if (seasonsOn) NewSeasons.alerts(recent, followed + server.watchedSeries(), seen) else emptyList()
            seasons.forEach { post(it.seriesId, it.title, it.text, it.openItemId, SEASONS_CHANNEL, "New seasons") }
            // A show that just started a season gets that notification, not one for its episodes.
            val announced = seasons.map { it.seriesId }.toSet()
            if (episodesOn) {
                NewEpisodes.alerts(recent, followed, seen).filter { it.seriesId !in announced }
                    .forEach { post(it.seriesId, it.title, it.text, it.openItemId, CHANNEL, "New episodes") }
            }
        }
        context.notificationPrefs.edit { it[seenKey(account)] = newest }
    }

    private fun post(seriesId: String, title: String, text: String, openItemId: String, channel: String, channelName: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(channel) == null) {
            manager.createNotificationChannel(NotificationChannel(channel, channelName, NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            putExtra(NewEpisodeNotifier.EXTRA_OPEN_ITEM, openItemId)
            addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(dev.nitrate.android.R.drawable.ic_stat_new_episode)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .apply {
                if (open != null) {
                    setContentIntent(PendingIntent.getActivity(context, seriesId.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                }
            }
            .build()
        // One per show: a later check replaces it with the up-to-date news.
        NotificationManagerCompat.from(context).notify(seriesId.hashCode(), notification)
    }

    companion object {
        private const val CHANNEL = "new_episodes"
        private const val SEASONS_CHANNEL = "new_seasons"
        private const val RECENT = 150
        private const val PERIODIC = "new-episodes"
        private const val SOON = "new-episodes-soon"
        private const val INVITES = "watch-invites"
        private const val INVITES_CHANNEL = "watch_invites"
        private const val REQUESTS_CHANNEL = "partner_requests"
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

class InviteWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as? AppContainer.Owner)?.container ?: return Result.failure()
        if (container.server.userId == null) container.server.restoreSession()
        return runCatching { container.newEpisodes.checkInvites() }.fold({ Result.success() }, { Result.retry() })
    }
}
