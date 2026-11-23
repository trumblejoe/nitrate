package dev.nitrate.core.companion

/**
 * Where the Nitrate Companion plugin's own updates stand (admins only; Companion feature `updates`).
 * A new version is installed beside the running one and only starts when the server restarts.
 */
data class CompanionUpdates(
    /** The version running now. */
    val running: String,
    /** A newer version installed and waiting for a restart, if any. */
    val waiting: String?,
    /** Whether the server needs a restart to run [waiting]. */
    val restartNeeded: Boolean,
    /** How many sessions are playing something now (a restart would stop them). */
    val playing: Int,
    /** When the plugin last asked its repositories (since the server started), if it has. */
    val lastCheckedAtMs: Long?,
    /** What that look found, in words. */
    val lastCheck: String?,
    /** Whether the plugin installs new versions by itself. */
    val autoUpdate: Boolean,
) {
    /** The line for Settings: what's waiting, or that it's up to date. */
    val summary: String get() = when {
        restartNeeded && waiting != null -> "Version $waiting is installed and starts after the server restarts" +
            if (playing > 0) ". Something is playing now: restart when it ends." else "."
        lastCheck != null -> "Version $running. $lastCheck"
        else -> "Version $running."
    }

    /** Whether "Restart now" may be offered: an update is waiting and nothing is playing. */
    val canRestart: Boolean get() = restartNeeded && waiting != null && playing == 0

    companion object {
        const val FEATURE = "updates"
    }
}

/** Why the server wasn't restarted for the Companion's update. */
class RestartRefused(message: String) : Exception(message)
