package dev.nitrate.android.playback

/**
 * The version (media source) chosen for a title on its details page, until the app closes; the
 * player plays that one. No choice means the server's best for this device (see resolvePlayback).
 */
object VersionChoices {
    private val chosen = mutableMapOf<String, String>()

    operator fun get(itemId: String): String? = synchronized(chosen) { chosen[itemId] }

    /** [versionId] null goes back to automatic. */
    fun set(itemId: String, versionId: String?) = synchronized(chosen) {
        if (versionId == null) chosen.remove(itemId) else chosen[itemId] = versionId
    }
}
