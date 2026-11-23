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
import kotlinx.coroutines.launch
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
    private val partyKey = booleanPreferencesKey("party_reminders")

    /** Watch party reminders: on until switched off (they're for you). */
    override val partyRemindersEnabled: Flow<Boolean> = context.notificationPrefs.data.map { it[partyKey] ?: true }

    override suspend fun setPartyRemindersEnabled(on: Boolean) {
        context.notificationPrefs.edit { it[partyKey] = on }
        if (!on) runCatching { WorkManager.getInstance(context).cancelAllWorkByTag(PARTY_TAG) }
        scheduleInvites()
    }

    /**
     * One wake-up per group, 15 minutes before its next watch party (WorkManager: inexact, no
     * exact-alarm permission, kept across restarts of the phone), replacing those set before.
     */
    override suspend fun scheduleParties(groups: List<dev.nitrate.core.partners.WatchGroup>, nowMs: Long) {
        val work = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
        work.cancelAllWorkByTag(PARTY_TAG)
        if (!partyRemindersEnabled.first()) return
        groups.forEach { g ->
            val party = g.party ?: return@forEach
            val at = party.nextReminderAt(nowMs) ?: return@forEach
            scheduleParty(work, g.id, g.seriesName, dev.nitrate.core.partners.PartyPrompt.names(g.others.map { it.name }), at - nowMs)
        }
    }

    private fun scheduleParty(work: WorkManager, groupId: String, title: String, with: String, delayMs: Long) {
        val request = OneTimeWorkRequestBuilder<PartyReminderWorker>()
            .setInitialDelay(delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(androidx.work.workDataOf(PARTY_GROUP to groupId, PARTY_TITLE to title, PARTY_WITH to with))
            .addTag(PARTY_TAG)
            .build()
        work.enqueueUniqueWork("party-$groupId", ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * A party reminder came due: one quick look at the party as it stands (postponed: wake again
     * 15 minutes before the new time; off, or the user can't make it: nothing), then the
     * notification "Group Watch Session with Tommy & Suzy · 8:00 · S1:E4" and the next party's wake-up.
     */
    suspend fun remindParty(groupId: String, title: String, with: String) {
        if (!partyRemindersEnabled.first()) return
        val party = server.watchParty(groupId) ?: return
        val work = runCatching { WorkManager.getInstance(context) }.getOrNull()
        val now = System.currentTimeMillis()
        if (party.going && party.reminderAtMs > now + 2 * 60_000L) {
            // Pushed back since this wake-up was set: wake again in time for the new start.
            work?.let { scheduleParty(it, groupId, title, with, party.reminderAtMs - now) }
            return
        }
        if (party.going && !party.started && now < party.startsAtMs + 10 * 60_000L) {
            val prompt = dev.nitrate.core.partners.PartyPrompt.reminder(groupId, with, title, party)
            postParty(groupId, title, prompt.text.lineSequence().first(), party)
        }
        party.nextReminderAt(maxOf(now, party.startsAtMs))?.let { at -> work?.let { scheduleParty(it, groupId, title, with, at - now) } }
    }

    private fun partyAction(groupId: String, action: String, requestId: String? = null, yes: Boolean = false): PendingIntent {
        val intent = android.content.Intent(context, PartyActionReceiver::class.java).apply {
            this.action = action
            putExtra(PARTY_GROUP, groupId)
            putExtra(PARTY_REQUEST, requestId)
            putExtra(PARTY_YES, yes)
        }
        return PendingIntent.getBroadcast(context, (groupId + action + yes).hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** The reminder: Join (opens the app on the party), +15 min, Not tonight. */
    private fun postParty(groupId: String, title: String, text: String, party: dev.nitrate.core.partners.WatchParty) {
        if (!canNotify()) return
        channel(PARTY_CHANNEL, "Group Watch Session reminders")
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            putExtra(NewEpisodeNotifier.EXTRA_OPEN_PARTY, groupId)
            addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val joinIntent = open?.let { PendingIntent.getActivity(context, ("party:" + groupId).hashCode(), it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        val notification = NotificationCompat.Builder(context, PARTY_CHANNEL)
            .setSmallIcon(dev.nitrate.android.R.drawable.ic_stat_new_episode)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .apply {
                if (joinIntent != null) {
                    setContentIntent(joinIntent)
                    addAction(0, "Join", joinIntent)
                }
                if (party.canPushBack) addAction(0, "+15 min", partyAction(groupId, PartyActionReceiver.LATER))
                if (party.ask == null) addAction(0, "Not tonight", partyAction(groupId, PartyActionReceiver.SKIP))
            }
            .build()
        NotificationManagerCompat.from(context).notify(partyId(groupId), notification)
    }

    /** A postponement or "Not tonight" waiting for the user's yes or no (with the app closed: from the 15-minute check). */
    private fun postPartyAsk(group: dev.nitrate.core.partners.WatchGroup, ask: dev.nitrate.core.partners.PartyAsk) {
        if (!canNotify()) return
        channel(PARTY_CHANNEL, "Group Watch Session reminders")
        val text = "${ask.text}. Agree?"
        val notification = NotificationCompat.Builder(context, PARTY_CHANNEL)
            .setSmallIcon(dev.nitrate.android.R.drawable.ic_stat_new_episode)
            .setContentTitle("Group Watch Session with ${dev.nitrate.core.partners.PartyPrompt.names(group.others.map { it.name })}")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .addAction(0, "Yes", partyAction(group.id, PartyActionReceiver.VOTE, ask.id, yes = true))
            .addAction(0, "No", partyAction(group.id, PartyActionReceiver.VOTE, ask.id, yes = false))
            .build()
        NotificationManagerCompat.from(context).notify(partyId(group.id), notification)
    }

    /** Someone in a group asked to change its schedule: Yes / No without opening the app; a tap opens the app on the question. */
    private fun postScheduleAsk(ask: dev.nitrate.core.partners.ScheduleNews) {
        if (!canNotify()) return
        channel(REQUESTS_CHANNEL, REQUESTS_NAME)
        val prompt = dev.nitrate.core.partners.SchedulePrompt.of(ask) ?: return
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            putExtra(NewEpisodeNotifier.EXTRA_OPEN_SCHEDULE, ask.groupId)
            addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val notification = NotificationCompat.Builder(context, REQUESTS_CHANNEL)
            .setSmallIcon(dev.nitrate.android.R.drawable.ic_stat_new_episode)
            .setContentTitle(prompt.title)
            .setContentText(prompt.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(prompt.text))
            .setAutoCancel(true)
            .apply { if (open != null) setContentIntent(PendingIntent.getActivity(context, scheduleId(ask.groupId), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)) }
            .addAction(0, "Yes", scheduleVote(ask, yes = true))
            .addAction(0, "No", scheduleVote(ask, yes = false))
            .build()
        NotificationManagerCompat.from(context).notify(scheduleId(ask.groupId), notification)
    }

    private fun scheduleVote(ask: dev.nitrate.core.partners.ScheduleNews, yes: Boolean): PendingIntent {
        val intent = android.content.Intent(context, PartyActionReceiver::class.java).apply {
            action = PartyActionReceiver.SCHEDULE_VOTE
            putExtra(PARTY_GROUP, ask.groupId)
            putExtra(PARTY_REQUEST, ask.changeId)
            putExtra(PARTY_YES, yes)
        }
        return PendingIntent.getBroadcast(context, (ask.groupId + PartyActionReceiver.SCHEDULE_VOTE + yes).hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun canNotify() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun channel(id: String, name: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(id) == null) {
            manager.createNotificationChannel(NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH))
        }
    }

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
        if (!invitesEnabled.first() && !partnerRequestsEnabled.first() && !partyRemindersEnabled.first()) {
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
        val partyOn = partyRemindersEnabled.first()
        if (!invitesOn && !requestsOn && !partyOn) return
        val account = server.currentAccountKey ?: return
        val notified = context.notificationPrefs.data.first()[notifiedKey(account)].orEmpty()
        val fresh = mutableSetOf<String>()
        if (invitesOn) runCatching { server.watchInvites() }.getOrDefault(emptyList()).forEach { inv ->
            fresh += "i:" + inv.id
            if ("i:" + inv.id !in notified) postSocial("i:" + inv.id, "Watch now together", inv.text, INVITES_CHANNEL, "Watch now together invites")
        }
        // One look at the groups for both: invites into them, and watch party requests waiting for a yes or no.
        val partners = if (requestsOn || partyOn) runCatching { server.partners() }.getOrNull() else null
        if (requestsOn) partners?.incoming.orEmpty().forEach { r ->
            fresh += "r:" + r.id
            if ("r:" + r.id !in notified) postSocial("r:" + r.id, "Watch ${r.seriesName} together?", "${r.fromName} would like to watch ${r.seriesName} with you.", REQUESTS_CHANNEL, REQUESTS_NAME)
        }
        // Schedule changes waiting for the user's yes or no (never their own: the asker counts as a yes).
        if (requestsOn) partners?.groups.orEmpty().forEach { g ->
            val ask = dev.nitrate.core.partners.ScheduleNews.askOf(g) ?: return@forEach
            fresh += "s:" + ask.changeId
            if ("s:" + ask.changeId !in notified) postScheduleAsk(ask)
        }
        if (partyOn) partners?.groups.orEmpty().forEach { g ->
            val ask = g.party?.ask?.takeIf { !it.voted && !it.mine } ?: return@forEach
            fresh += "p:" + ask.id
            if ("p:" + ask.id !in notified) postPartyAsk(g, ask)
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
        // The id is kept (Android keeps the user's settings for it); the name says what it holds now.
        private const val REQUESTS_CHANNEL = "partner_requests"
        private const val REQUESTS_NAME = "Watch group requests"
        private const val PARTY_CHANNEL = "watch_party"
        private const val PARTY_TAG = "watch-party"
        internal const val PARTY_GROUP = "group"
        internal const val PARTY_TITLE = "title"
        internal const val PARTY_WITH = "with"
        internal const val PARTY_REQUEST = "request"
        internal const val PARTY_YES = "yes"

        /** One notification per group's party: a later one replaces it. */
        internal fun partyId(groupId: String) = ("party:" + groupId).hashCode()

        /** One notification per group's schedule change: a later one replaces it. */
        internal fun scheduleId(groupId: String) = ("schedule:" + groupId).hashCode()
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

/** Wakes 15 minutes before a group's watch party ([AndroidNewEpisodeNotifier.remindParty]). */
class PartyReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as? AppContainer.Owner)?.container ?: return Result.failure()
        val notifier = container.newEpisodes as? AndroidNewEpisodeNotifier ?: return Result.success()
        val groupId = inputData.getString(AndroidNewEpisodeNotifier.PARTY_GROUP) ?: return Result.failure()
        if (container.server.userId == null) container.server.restoreSession()
        return runCatching {
            notifier.remindParty(groupId, inputData.getString(AndroidNewEpisodeNotifier.PARTY_TITLE).orEmpty(), inputData.getString(AndroidNewEpisodeNotifier.PARTY_WITH).orEmpty())
        }.fold({ Result.success() }, { Result.retry() })
    }
}

/**
 * A watch party notification's buttons, answered without opening the app: +15 min, Not tonight,
 * and Yes/No to someone else's postponement; and Yes/No to someone's schedule change. The
 * notification then says what happened.
 */
class PartyActionReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: android.content.Intent) {
        val container = (context.applicationContext as? AppContainer.Owner)?.container ?: return
        val groupId = intent.getStringExtra(AndroidNewEpisodeNotifier.PARTY_GROUP) ?: return
        val action = intent.action
        val pending = goAsync()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val said = runCatching {
                if (container.server.userId == null) container.server.restoreSession()
                when (action) {
                    LATER -> container.server.partyLater(groupId, 15)?.let { "Pushed back to ${it.clock}. Everyone's told." }
                    SKIP -> container.server.partySkip(groupId)?.let { if (it.skipped) "Tonight's Group Watch Session is off." else "Asked the group to skip tonight." }
                    SCHEDULE_VOTE -> {
                        val change = intent.getStringExtra(AndroidNewEpisodeNotifier.PARTY_REQUEST) ?: return@runCatching null
                        val yes = intent.getBooleanExtra(AndroidNewEpisodeNotifier.PARTY_YES, false)
                        container.server.voteSchedule(groupId, change, yes)
                        if (yes) "Thanks: it changes once most of the group agrees." else "Thanks: the others will see your answer."
                    }
                    VOTE -> {
                        val request = intent.getStringExtra(AndroidNewEpisodeNotifier.PARTY_REQUEST) ?: return@runCatching null
                        container.server.partyVote(groupId, request, intent.getBooleanExtra(AndroidNewEpisodeNotifier.PARTY_YES, false))
                        "Thanks: the others will see your answer."
                    }
                    else -> null
                }
            }.getOrElse { it.message ?: "That didn't work" }
            val manager = NotificationManagerCompat.from(context)
            val schedule = action == SCHEDULE_VOTE
            val id = if (schedule) AndroidNewEpisodeNotifier.scheduleId(groupId) else AndroidNewEpisodeNotifier.partyId(groupId)
            manager.cancel(id)
            if (said != null && (Build.VERSION.SDK_INT < 33 ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            ) {
                manager.notify(
                    id,
                    NotificationCompat.Builder(context, if (schedule) "partner_requests" else "watch_party")
                        .setSmallIcon(dev.nitrate.android.R.drawable.ic_stat_new_episode)
                        .setContentTitle(if (schedule) "Watch group schedule" else "Group Watch Session")
                        .setContentText(said)
                        .setAutoCancel(true)
                        .setTimeoutAfter(60_000)
                        .build(),
                )
            }
            pending.finish()
        }
    }

    companion object {
        const val LATER = "dev.nitrate.PARTY_LATER"
        const val SKIP = "dev.nitrate.PARTY_SKIP"
        const val VOTE = "dev.nitrate.PARTY_VOTE"
        const val SCHEDULE_VOTE = "dev.nitrate.SCHEDULE_VOTE"
    }
}
