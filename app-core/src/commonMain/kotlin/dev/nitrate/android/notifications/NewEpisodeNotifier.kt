package dev.nitrate.android.notifications

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Tells this device about new episodes of shows the user follows and new seasons of shows they
 * follow or have watched before (core NewEpisodes / NewSeasons decide which). Each kind is off
 * until switched on.
 */
interface NewEpisodeNotifier {
    val enabled: Flow<Boolean>

    /** New seasons of shows followed or watched before. */
    val seasonsEnabled: Flow<Boolean>
    suspend fun setEnabled(on: Boolean)
    suspend fun setSeasonsEnabled(on: Boolean)

    /** Keeps the background check scheduled (or not) to match the settings; call at startup. */
    suspend fun ensureScheduled()

    /** Watch-together invites and watch-partner requests, each its own switch (on until switched off). */
    val invitesEnabled: Flow<Boolean> get() = MutableStateFlow(false)
    val partnerRequestsEnabled: Flow<Boolean> get() = MutableStateFlow(false)
    suspend fun setInvitesEnabled(on: Boolean) = Unit
    suspend fun setPartnerRequestsEnabled(on: Boolean) = Unit

    /** Looks for invites and partner requests not notified yet (every 15 minutes in the background). */
    suspend fun checkInvites() = Unit

    /** Watch party reminders, 15 minutes before each of the user's groups' parties (on until switched off). */
    val partyRemindersEnabled: Flow<Boolean> get() = MutableStateFlow(false)
    suspend fun setPartyRemindersEnabled(on: Boolean) = Unit

    /**
     * Sets this device's own reminders, 15 minutes before each group's next watch party
     * ([dev.nitrate.core.partners.WatchGroup.party]), replacing any set before (an empty list
     * clears them: signing out). Nothing is pushed from outside: the device wakes itself, takes one
     * quick look at the party (postponed or off) and reminds, or not.
     */
    suspend fun scheduleParties(groups: List<dev.nitrate.core.partners.WatchGroup>, nowMs: Long) = Unit

    /** The server added things: check in a moment. */
    fun checkSoon()
    suspend fun check()

    /** Never notifies (a platform without notifications yet). */
    object None : NewEpisodeNotifier {
        override val enabled: Flow<Boolean> = MutableStateFlow(false)
        override val seasonsEnabled: Flow<Boolean> = MutableStateFlow(false)
        override suspend fun setEnabled(on: Boolean) = Unit
        override suspend fun setSeasonsEnabled(on: Boolean) = Unit
        override suspend fun ensureScheduled() = Unit
        override fun checkSoon() = Unit
        override suspend fun check() = Unit
    }

    companion object {
        /** The item a tapped notification opens (an Android Intent extra). */
        const val EXTRA_OPEN_ITEM = "dev.nitrate.OPEN_ITEM"

        /** A tapped invite or request notification: the app opens and its prompt asks. */
        const val EXTRA_OPEN_INVITES = "dev.nitrate.OPEN_INVITES"

        /** A tapped watch party reminder: the app opens on that group's party ([String] group id). */
        const val EXTRA_OPEN_PARTY = "dev.nitrate.OPEN_PARTY"
    }
}
