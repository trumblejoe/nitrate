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
    }
}
