package dev.nitrate.ios

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import dev.nitrate.android.notifications.NewEpisodeNotifier
import dev.nitrate.core.MediaServer
import dev.nitrate.core.notifications.NewEpisodes
import dev.nitrate.core.notifications.NewSeasons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNUserNotificationCenter

/**
 * New episodes and new seasons on iOS, decided exactly as on Android (core NewEpisodes and
 * NewSeasons; the first check only notes where things stand). Checks happen when iOS gives the
 * app background time (BGAppRefreshTask, scheduled by the Swift app) and soon after the server
 * says things were added; a notification's tap opens its item ([IosApp.openItem]).
 */
class IosNewEpisodeNotifier(private val server: MediaServer, private val store: DataStore<Preferences>) : NewEpisodeNotifier {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val enabledKey = booleanPreferencesKey("new_episodes")
    private val seasonsKey = booleanPreferencesKey("new_seasons")
    private fun seenKey(account: String) = longPreferencesKey("seen:$account")

    override val enabled: Flow<Boolean> = store.data.map { it[enabledKey] ?: false }
    override val seasonsEnabled: Flow<Boolean> = store.data.map { it[seasonsKey] ?: false }

    override suspend fun setEnabled(on: Boolean) {
        store.edit { it[enabledKey] = on }
    }

    override suspend fun setSeasonsEnabled(on: Boolean) {
        store.edit { it[seasonsKey] = on }
    }

    // Background refresh is asked for by the Swift app whenever it goes to the background.
    override suspend fun ensureScheduled() = Unit

    private val partyKey = booleanPreferencesKey("party_reminders")

    /** Watch party reminders: on until switched off. */
    override val partyRemindersEnabled: Flow<Boolean> = store.data.map { it[partyKey] ?: true }

    override suspend fun setPartyRemindersEnabled(on: Boolean) {
        store.edit { it[partyKey] = on }
        if (!on) clearParties()
    }

    /**
     * One local notification per group, 15 minutes before its next watch party, on a calendar
     * trigger (no push service). iOS gives the app no time to look again when it fires, so the
     * text is what the group's party said when the groups last loaded; each load (opening the app,
     * the background refresh, a postponement heard while open) sets them again, and a party that's
     * off or that the user can't make sets none.
     */
    override suspend fun scheduleParties(groups: List<dev.nitrate.core.partners.WatchGroup>, nowMs: Long) {
        clearParties()
        if (!partyRemindersEnabled.first()) return
        val center = UNUserNotificationCenter.currentNotificationCenter()
        groups.forEach { g ->
            val party = g.party ?: return@forEach
            val at = party.nextReminderAt(nowMs) ?: return@forEach
            // This party's own reminder knows its time and part; the one after it only its time.
            val shown = if (at == party.reminderAtMs) party else party.copy(atMs = party.nextAtMs, startsAtMs = party.nextAtMs, delays = emptyList(), ask = null)
            val with = dev.nitrate.core.partners.PartyPrompt.names(g.others.map { it.name })
            val content = UNMutableNotificationContent().apply {
                setTitle(g.seriesName)
                setBody(listOfNotNull("Group Watch Session with $with", shown.clock, shown.partLabel.takeIf { at == party.reminderAtMs }).joinToString(" · "))
                setUserInfo(mapOf<Any?, Any?>(PARTY to g.id))
            }
            val date = platform.Foundation.NSDate(timeIntervalSinceReferenceDate = at / 1000.0 - 978_307_200.0)
            val parts = platform.Foundation.NSCalendar.currentCalendar.components(
                platform.Foundation.NSCalendarUnitYear or platform.Foundation.NSCalendarUnitMonth or platform.Foundation.NSCalendarUnitDay or
                    platform.Foundation.NSCalendarUnitHour or platform.Foundation.NSCalendarUnitMinute,
                fromDate = date,
            )
            val trigger = platform.UserNotifications.UNCalendarNotificationTrigger.triggerWithDateMatchingComponents(parts, repeats = false)
            center.addNotificationRequest(UNNotificationRequest.requestWithIdentifier(PARTY_PREFIX + g.id, content, trigger), null)
        }
    }

    private suspend fun clearParties() {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        val ids = kotlinx.coroutines.suspendCancellableCoroutine<List<String>> { done ->
            center.getPendingNotificationRequestsWithCompletionHandler { list ->
                done.resumeWith(Result.success(list.orEmpty().mapNotNull { (it as? UNNotificationRequest)?.identifier?.takeIf { id -> id.startsWith(PARTY_PREFIX) } }))
            }
        }
        if (ids.isNotEmpty()) center.removePendingNotificationRequestsWithIdentifiers(ids)
    }

    override fun checkSoon() {
        scope.launch {
            delay(30_000)
            runCatching { check() }
        }
    }

    override suspend fun check() {
        // The background refresh also sets the watch party reminders again from the groups as they stand.
        if (partyRemindersEnabled.first()) runCatching { scheduleParties(server.partners().groups, kotlin.time.Clock.System.now().toEpochMilliseconds()) }
        val episodesOn = enabled.first()
        val seasonsOn = seasonsEnabled.first()
        if (!episodesOn && !seasonsOn) return
        val account = server.currentAccountKey ?: return
        val recent = server.recentEpisodes(RECENT)
        val seen = store.data.first()[seenKey(account)]
        val newest = NewEpisodes.newestAdded(recent, seen ?: 0)
        if (seen != null) {
            val followed = server.followedSeries()
            val seasons = if (seasonsOn) NewSeasons.alerts(recent, followed + server.watchedSeries(), seen) else emptyList()
            seasons.forEach { post(it.seriesId, it.title, it.text, it.openItemId) }
            val announced = seasons.map { it.seriesId }.toSet()
            if (episodesOn) {
                NewEpisodes.alerts(recent, followed, seen).filter { it.seriesId !in announced }
                    .forEach { post(it.seriesId, it.title, it.text, it.openItemId) }
            }
        }
        store.edit { it[seenKey(account)] = newest }
    }

    override fun tripNews(tripId: String, title: String, text: String) {
        val content = UNMutableNotificationContent().apply {
            setTitle(title)
            setBody(text)
        }
        UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(UNNotificationRequest.requestWithIdentifier("trip-$tripId", content, null), null)
    }

    private fun post(seriesId: String, title: String, text: String, openItemId: String) {
        val content = UNMutableNotificationContent().apply {
            setTitle(title)
            setBody(text)
            setUserInfo(mapOf<Any?, Any?>(ITEM to openItemId))
        }
        // One per show: a later check replaces it.
        val request = UNNotificationRequest.requestWithIdentifier("show-$seriesId", content, null)
        UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(request, null)
    }

    companion object {
        /** The item a notification opens, in its user info. */
        const val ITEM = "nitrateItem"

        /** The group whose watch party a reminder is for, in its user info. */
        const val PARTY = "nitrateParty"
        private const val PARTY_PREFIX = "party-"
        private const val RECENT = 150
    }
}
