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

    override fun checkSoon() {
        scope.launch {
            delay(30_000)
            runCatching { check() }
        }
    }

    override suspend fun check() {
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
        private const val RECENT = 150
    }
}
