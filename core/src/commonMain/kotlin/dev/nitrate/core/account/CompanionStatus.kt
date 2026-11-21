package dev.nitrate.core.account

import dev.nitrate.core.companion.CompanionInfo

/**
 * The Nitrate Companion plugin as server-side settings show it: whether it's installed and
 * answering, its version, which of its features are on, and how much spoken-line search has
 * indexed (which tells the nightly task is running).
 */
object CompanionStatus {
    /** Every feature Nitrate knows, with what it's called and what it does. */
    val FEATURES = listOf(
        Triple("chat", "Watch-together chat", "Messages and reactions in a watch-together group"),
        Triple("scripts", "Shared screenplays", "Writing with others, and syncing your own scripts"),
        Triple("channels", "Channels", "The household's own TV channels"),
        Triple("partners", "Watch partners", "Shows watched with someone, and private accounts"),
        Triple("sso", "Single sign-on", "Signing in with the identity provider"),
        Triple("lines", "Search what people say", "Lines from subtitles in search"),
        Triple("diary", "Watch diary", "Every watch kept, with ratings and notes"),
        Triple("together", "Watch-together invites", "Asking people to join a watch-together group"),
        Triple("devices", "Household devices", "Each device's playback summary, for admins"),
        Triple("testclips", "TV & receiver check", "Clips to check what the TV and receiver show"),
        Triple("credits", "Scenes in the credits", "Found nightly by \"Find scenes in the credits\", with the final credits given to Jellyfin as an Outro"),
        Triple("intros", "Intros and recaps", "Found nightly by \"Find intros\" and given to Jellyfin, so Skip Intro and Skip Recap work in every app"),
    )

    /**
     * [info]: the plugin's answer (null: not installed, the server doesn't know its address) or
     * the failure asking; [indexed]: titles and lines spoken-line search has so far, when known.
     */
    fun group(info: Result<CompanionInfo?>, indexed: Pair<Int, Long>? = null): ServerSettingsGroup {
        val where = "Jellyfin Dashboard → Plugins → Nitrate Companion"
        val companion = info.getOrElse { e ->
            return ServerSettingsGroup(
                "Nitrate Companion", where,
                listOf(ServerSetting("Plugin", "Not answering", SettingReach.APPLIES, "Couldn't ask the server: ${e.message ?: "no answer"}. Its features are hidden until it answers.")),
            )
        } ?: return ServerSettingsGroup(
            "Nitrate Companion", where,
            listOf(
                ServerSetting(
                    "Plugin", "Not installed", SettingReach.APPLIES,
                    "Chat, shared screenplays, channels, watch partners, single sign-on, search what people say and the watch diary need it. " +
                        "An admin adds Nitrate's plugin repository under Dashboard → Plugins → Repositories, installs Nitrate Companion, then restarts Jellyfin.",
                ),
            ),
        )
        val known = FEATURES.map { it.first }.toSet()
        val features = FEATURES.map { (id, label, what) ->
            val on = id in companion.features
            val note = when {
                on && id == "lines" -> indexed?.let { (titles, lines) ->
                    if (titles == 0) "Nothing indexed yet: the \"Index spoken lines\" task runs nightly (Dashboard → Scheduled tasks to run it now)."
                    else "$titles titles, $lines lines indexed."
                }
                on -> what
                else -> "$what. Off: turned off in the plugin's settings" + if (id == "sso") ", or not set up" else ", or the plugin is older than this app"
            }
            ServerSetting(label, if (on) "On" else "Off", SettingReach.APPLIES, note)
        }
        val unknown = (companion.features - known).sorted()
        return ServerSettingsGroup(
            "Nitrate Companion", where,
            listOf(ServerSetting("Plugin", "Installed and answering", SettingReach.APPLIES), ServerSetting("Version", companion.version, SettingReach.INFO)) +
                features +
                listOfNotNull(unknown.takeIf { it.isNotEmpty() }?.let { ServerSetting("Newer features", it.joinToString(), SettingReach.INFO, "This plugin offers more than this version of Nitrate uses: update the app.") }),
        )
    }
}
