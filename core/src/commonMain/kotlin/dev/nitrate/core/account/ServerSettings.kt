package dev.nitrate.core.account

/** Where a server-side setting matters. */
enum class SettingReach(val label: String) {
    /** The server applies it to what Nitrate asks for, or Nitrate reads it. */
    APPLIES("Applies in Nitrate"),
    /** Only Jellyfin's own apps (web, its Android app) use it; Nitrate has its own way. */
    JELLYFIN_APPS("Jellyfin's own apps only"),
    /** About the account or server, for reference. */
    INFO("For reference"),
}

/** One setting kept on the Jellyfin server, as the user's account has it. */
data class ServerSetting(val label: String, val value: String, val reach: SettingReach, val note: String? = null)

data class ServerSettingsGroup(val title: String, val where: String?, val settings: List<ServerSetting>)

/** Everything about the signed-in account that's set on the Jellyfin server rather than on this device. */
data class ServerSettings(val groups: List<ServerSettingsGroup>) {
    /** As plain text, for copying. */
    fun render(): String = buildString {
        groups.forEach { g ->
            append("== ${g.title} ==\n")
            g.where?.let { append("(Change in: $it)\n") }
            g.settings.forEach { s ->
                append("${s.label}: ${s.value}  [${s.reach.label}]\n")
                s.note?.let { append("  $it\n") }
            }
            append('\n')
        }
    }.trimEnd() + "\n"
}
