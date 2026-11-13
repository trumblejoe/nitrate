package dev.nitrate.core.backup

import dev.nitrate.core.playback.PlaybackSettings
import dev.nitrate.core.playback.SubtitleStyle
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The settings that belong to one device rather than to the user: playback and plugins. */
@Serializable
data class DeviceSettings(
    val playback: PlaybackSettings = PlaybackSettings(),
    /** Plugins switched on or off here (absent: the plugin's default). */
    val pluginsEnabled: Map<String, Boolean> = emptyMap(),
    /** Each plugin's settings, as the plugin's own JSON. */
    val pluginSettings: Map<String, String> = emptyMap(),
)

/**
 * A copy of one device's [DeviceSettings], kept in the user's Jellyfin data so a new or reset
 * device can be set up the same way. Never applied without the user choosing it. PINs and the
 * "who's watching" choice are never in it; scripts have their own backup.
 */
@Serializable
data class DeviceBackup(
    val deviceId: String,
    val deviceName: String,
    /** Manufacturer and model, to tell whether hardware test results apply. */
    val model: String,
    /** "tv" or "mobile". */
    val app: String,
    val appVersion: String = "",
    val savedAtMs: Long,
    val settings: DeviceSettings,
) {
    val summary get() = BackupSummary(deviceId, deviceName, model, app, savedAtMs)
}

/** What the list of backups shows before the whole backup is fetched. */
@Serializable
data class BackupSummary(val deviceId: String, val deviceName: String, val model: String, val app: String, val savedAtMs: Long) {
    val appLabel get() = if (app == "tv") "TV" else "Phone or tablet"
}

/** One line of the Differences list: [setting] goes from [from] to [to]. */
data class Difference(val section: String, val setting: String, val from: String, val to: String)

object DeviceBackups {
    /** Display-preferences id holding the list of the user's backed-up devices. */
    const val INDEX_PREFS = "nitrate-devices"
    /** Key of the backup inside a device's own display preferences. */
    const val BACKUP_KEY = "backup"
    /** Suffix for the copy kept when a reinstalled device starts backing up again. */
    const val EARLIER_SUFFIX = "-earlier"

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    /** Display-preferences id for one device's backup. */
    fun prefsId(deviceId: String) = "nitrate-device-" + deviceId.filter { it.isLetterOrDigit() || it == '-' }.take(64)

    /** Whether hardware results (codec tests, learned failures, codec overrides) carry over. */
    fun sameModel(backup: BackupSummary, model: String) = backup.model.isNotBlank() && backup.model.equals(model, ignoreCase = true)

    /**
     * This device's settings after restoring [backup]: every preference from the backup; the
     * hardware results only when the backup came from the same model, otherwise this device keeps
     * its own. Plugins the backup doesn't mention keep their settings here.
     */
    fun restored(current: DeviceSettings, backup: DeviceSettings, sameModel: Boolean): DeviceSettings {
        val b = backup.playback
        val playback = if (sameModel) b else b.copy(
            overrides = current.playback.overrides,
            learnedFailures = current.playback.learnedFailures,
            codecMaxHeight = current.playback.codecMaxHeight,
            eightBitOnly = current.playback.eightBitOnly,
            tenBitAllowed = current.playback.tenBitAllowed,
        )
        return DeviceSettings(
            playback = playback,
            pluginsEnabled = current.pluginsEnabled + backup.pluginsEnabled,
            pluginSettings = current.pluginSettings + backup.pluginSettings,
        )
    }

    /**
     * What changes going from [current] to [next], in words. [pluginNames] names plugins by id;
     * [pluginDefaults] says whether a plugin is on when nobody has chosen.
     */
    fun differences(
        current: DeviceSettings,
        next: DeviceSettings,
        pluginNames: Map<String, String> = emptyMap(),
        pluginDefaults: Map<String, Boolean> = emptyMap(),
    ): List<Difference> = buildList {
        val a = current.playback
        val n = next.playback
        fun playback(setting: String, from: String, to: String) { if (from != to) add(Difference("Playback", setting, from, to)) }
        fun hardware(setting: String, from: String, to: String) { if (from != to) add(Difference("This device's hardware results", setting, from, to)) }

        playback("Maximum bitrate", mbps(a.maxBitrate), mbps(n.maxBitrate))
        playback("Maximum resolution", a.maxResolution.label, n.maxResolution.label)
        playback("Direct play", onOff(a.allowDirectPlay), onOff(n.allowDirectPlay))
        playback("Direct stream", onOff(a.allowDirectStream), onOff(n.allowDirectStream))
        playback("Avoid formats that failed before", onOff(a.useLearnedFailures), onOff(n.useLearnedFailures))
        playback("Night mode", onOff(a.nightMode), onOff(n.nightMode))
        playback("Picture-in-picture", onOff(a.pictureInPicture), onOff(n.pictureInPicture))
        playback("Keep playing in the background", onOff(a.backgroundAudio), onOff(n.backgroundAudio))
        playback("Writing pane split", a.writingSplit.label, n.writingSplit.label)
        subtitles(a.subtitleStyle, n.subtitleStyle).forEach { (s, f, t) -> add(Difference("Subtitles", s, f, t)) }

        hardware("Codec overrides", a.overrides.entries.sortedBy { it.key }.joinToString(", ") { "${it.key} ${it.value.label}" }.ifEmpty { "None" },
            n.overrides.entries.sortedBy { it.key }.joinToString(", ") { "${it.key} ${it.value.label}" }.ifEmpty { "None" })
        hardware("Formats that failed", list(a.learnedFailures.keys), list(n.learnedFailures.keys))
        hardware("Height limits", a.codecMaxHeight.entries.sortedBy { it.key }.joinToString(", ") { "${it.key} ${it.value}p" }.ifEmpty { "None" },
            n.codecMaxHeight.entries.sortedBy { it.key }.joinToString(", ") { "${it.key} ${it.value}p" }.ifEmpty { "None" })
        hardware("8-bit only", list(a.eightBitOnly), list(n.eightBitOnly))
        hardware("10-bit allowed by test", list(a.tenBitAllowed), list(n.tenBitAllowed))

        val plugins = (current.pluginsEnabled.keys + next.pluginsEnabled.keys + current.pluginSettings.keys + next.pluginSettings.keys).sorted()
        for (id in plugins) {
            val name = pluginNames[id] ?: id
            val default = pluginDefaults[id] ?: false
            val wasOn = current.pluginsEnabled[id] ?: default
            val isOn = next.pluginsEnabled[id] ?: default
            if (wasOn != isOn) add(Difference("Plugins", name, onOff(wasOn), onOff(isOn)))
            val before = objectOf(current.pluginSettings[id])
            val after = objectOf(next.pluginSettings[id])
            for (key in (before.keys + after.keys).sorted()) {
                val f = before[key]?.let(::show) ?: "Default"
                val t = after[key]?.let(::show) ?: "Default"
                if (f != t) add(Difference("Plugins", "$name: ${words(key)}", f, t))
            }
        }
    }

    private fun subtitles(a: SubtitleStyle, n: SubtitleStyle): List<Triple<String, String, String>> = listOf(
        Triple("Size", "${(a.scale * 100).toInt()}%", "${(n.scale * 100).toInt()}%"),
        Triple("Colour", colour(a.textColor), colour(n.textColor)),
        Triple("Background", a.background.label, n.background.label),
        Triple("Edge", a.edge.label, n.edge.label),
        Triple("Height from the bottom", "${(a.bottomMargin * 100).toInt()}%", "${(n.bottomMargin * 100).toInt()}%"),
        Triple("Use the file's own styles", onOff(a.useFileStyles), onOff(n.useFileStyles)),
    ).filter { it.second != it.third }

    private fun colour(argb: Long) = SubtitleStyle.COLORS.firstOrNull { it.first == argb }?.second ?: "#" + (argb and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()
    private fun mbps(bps: Int) = "${bps / 1_000_000} Mbps"
    private fun onOff(on: Boolean) = if (on) "On" else "Off"
    private fun list(items: Collection<String>) = items.sorted().joinToString(", ").ifEmpty { "None" }

    private fun objectOf(raw: String?): Map<String, JsonElement> =
        raw?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }.orEmpty()

    private fun show(e: JsonElement): String = when {
        e is JsonPrimitive && e.content == "true" -> "On"
        e is JsonPrimitive && e.content == "false" -> "Off"
        e is JsonPrimitive -> e.content
        else -> e.toString()
    }

    /** "backupToServer" → "Backup to server". */
    private fun words(key: String): String =
        key.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").lowercase().replaceFirstChar { it.uppercase() }
}
