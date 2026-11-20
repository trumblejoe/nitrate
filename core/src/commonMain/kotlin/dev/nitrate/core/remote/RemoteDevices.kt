package dev.nitrate.core.remote

import dev.nitrate.core.admin.ServerSession

/** The user's other devices that this one can play on and control (TVs first, playing first). */
object RemoteDevices {
    /**
     * Sessions that take remote playback, other than [thisDevice], newest first per device: the
     * ones playing something first, then Nitrate's own apps (which act on every command), then by name.
     */
    fun controllable(sessions: List<ServerSession>, thisDevice: String?, userId: String? = null): List<ServerSession> =
        sessions.filter { it.supportsMediaControl && it.deviceId != thisDevice }
            // Only the signed-in account's own: an admin may control everyone's sessions, but another
            // person's playing isn't this account's to show (or take over) from here.
            .filter { userId == null || it.userId?.equals(userId, ignoreCase = true) == true }
            // A device can have an old session left over: keep its most recent (ISO times sort as text).
            .sortedByDescending { it.lastActivity.orEmpty() }
            .distinctBy { it.deviceId ?: it.id }
            .sortedWith(
                compareBy<ServerSession>({ it.nowPlaying == null }, { it.client?.startsWith("Nitrate") != true }, { name(it).lowercase() }),
            )

    /** Devices playing something right now (for "Playing on…" while browsing). */
    fun playing(sessions: List<ServerSession>, thisDevice: String?, userId: String? = null): List<ServerSession> =
        controllable(sessions, thisDevice, userId).filter { it.nowPlaying != null }

    fun name(s: ServerSession): String = s.deviceName ?: s.client ?: "Device"
}
