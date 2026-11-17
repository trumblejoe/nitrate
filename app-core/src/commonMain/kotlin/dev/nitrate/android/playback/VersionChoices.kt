package dev.nitrate.android.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * The version (media source) chosen for a title on its details page, until the app closes; the
 * player plays that one. No choice means the server's best for this device (see resolvePlayback).
 */
object VersionChoices {
    private val chosen = MutableStateFlow(emptyMap<String, String>())

    operator fun get(itemId: String): String? = chosen.value[itemId]

    /** [versionId] null goes back to automatic. */
    fun set(itemId: String, versionId: String?) = chosen.update { if (versionId == null) it - itemId else it + (itemId to versionId) }
}
