package dev.nitrate.core.jellyfin

import dev.nitrate.core.account.ServerSetting
import dev.nitrate.core.account.ServerSettings
import dev.nitrate.core.account.ServerSettingsGroup
import dev.nitrate.core.account.SettingReach.APPLIES
import dev.nitrate.core.account.SettingReach.INFO
import dev.nitrate.core.account.SettingReach.JELLYFIN_APPS
import dev.nitrate.core.playback.languageName
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * The signed-in user's settings as Jellyfin keeps them (`Users/Me`: Configuration and Policy) and
 * the server's own details, in plain words, each marked with whether it applies in Nitrate.
 * [libraries] names library ids (id → name).
 */
internal object JellyfinServerSettings {
    fun from(me: JsonObject, info: JsonObject?, libraries: Map<String, String>): ServerSettings {
        val config = me["Configuration"] as? JsonObject ?: JsonObject(emptyMap())
        val policy = me["Policy"] as? JsonObject ?: JsonObject(emptyMap())
        fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        fun JsonObject.flag(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull
        fun JsonObject.list(key: String) = (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        fun onOff(v: Boolean?) = when (v) { true -> "On"; false -> "Off"; null -> "Not set" }
        fun yesNo(v: Boolean?) = when (v) { true -> "Yes"; false -> "No"; null -> "Not set" }
        fun language(code: String?) = code?.let { "${languageName(it)} ($it)" } ?: "Any: the file's default"
        fun names(ids: List<String>) = if (ids.isEmpty()) "None" else ids.joinToString(", ") { id -> libraries[normalizeId(id)] ?: id }

        val subtitleMode = config.text("SubtitleMode")
        val languages = ServerSettingsGroup(
            "Languages and subtitles", "Jellyfin → your profile → Playback and Subtitles",
            listOf(
                ServerSetting("Preferred audio language", language(config.text("AudioLanguagePreference")), APPLIES,
                    "The audio a title starts with when you haven't picked one for that show or film."),
                ServerSetting("Play default audio track", onOff(config.flag("PlayDefaultAudioTrack")), APPLIES,
                    "On: the track the file marks as default wins over your preferred language."),
                ServerSetting("Preferred subtitle language", language(config.text("SubtitleLanguagePreference")), APPLIES,
                    "Which subtitles start, by the subtitle mode below."),
                ServerSetting("Subtitle mode", subtitleModeLabel(subtitleMode), APPLIES, subtitleModeNote(subtitleMode)),
                ServerSetting("Remember audio selections", onOff(config.flag("RememberAudioSelections")), APPLIES, REMEMBER_NOTE),
                ServerSetting("Remember subtitle selections", onOff(config.flag("RememberSubtitleSelections")), APPLIES, REMEMBER_NOTE),
            ),
        )
        val bitrate = policy.text("RemoteClientBitrateLimit")?.toLongOrNull()
        val playback = ServerSettingsGroup(
            "Playback permissions", "Jellyfin dashboard → Users → you → Profile (your server's admin)",
            listOf(
                ServerSetting("Media playback allowed", yesNo(policy.flag("EnableMediaPlayback")), APPLIES),
                ServerSetting("Video can be converted", yesNo(policy.flag("EnableVideoPlaybackTranscoding")), APPLIES,
                    "Off: files this device can't play as they are won't play at all."),
                ServerSetting("Audio can be converted", yesNo(policy.flag("EnableAudioPlaybackTranscoding")), APPLIES),
                ServerSetting("Files can be repackaged (remux)", yesNo(policy.flag("EnablePlaybackRemuxing")), APPLIES),
                ServerSetting("Always convert away from home", yesNo(policy.flag("ForceRemoteSourceTranscoding")), APPLIES),
                ServerSetting("Bitrate limit away from home", if (bitrate == null || bitrate <= 0) "No limit" else "${bitrate / 1_000_000.0} Mbps".replace(".0 ", " "), APPLIES,
                    "On top of this device's own limit in Settings → Picture & sound."),
                ServerSetting("Downloads allowed", yesNo(policy.flag("EnableContentDownloading")), APPLIES,
                    "Off: Nitrate can't save titles to this device."),
            ),
        )
        val home = ServerSettingsGroup(
            "Home and libraries", "Jellyfin → your profile → Home and Display",
            listOf(
                ServerSetting("Show missing episodes", onOff(config.flag("DisplayMissingEpisodes")), APPLIES,
                    "Episodes the show has but your library doesn't, listed (not playable) in seasons."),
                ServerSetting("Hide watched in Latest", onOff(config.flag("HidePlayedInLatest")), APPLIES),
                ServerSetting("Library order", names(config.list("OrderedViews")).let { if (it == "None") "Server's order" else it }, APPLIES,
                    "The order libraries are listed in Nitrate's menus."),
                ServerSetting("Left out of My Media", names(config.list("MyMediaExcludes")), APPLIES),
                ServerSetting("Left out of Latest", names(config.list("LatestItemsExcludes")), APPLIES),
                ServerSetting("Grouped libraries", names(config.list("GroupedFolders")), JELLYFIN_APPS),
                ServerSetting("Collections view", onOff(config.flag("DisplayCollectionsView")), JELLYFIN_APPS),
                ServerSetting("Play the next episode automatically", onOff(config.flag("EnableNextEpisodeAutoPlay")), JELLYFIN_APPS,
                    "Nitrate's Up Next card works either way."),
            ),
        )
        val folders = policy.list("EnabledFolders")
        val account = ServerSettingsGroup(
            "Account", "Jellyfin dashboard → Users → you (your server's admin)",
            listOfNotNull(
                ServerSetting("Name", me.text("Name") ?: "?", INFO),
                ServerSetting("Administrator", yesNo(policy.flag("IsAdministrator")), APPLIES, "Shows Manage, Library health and server tools."),
                ServerSetting("Libraries", if (policy.flag("EnableAllFolders") != false) "All" else names(folders), APPLIES),
                ServerSetting("Highest age rating", policy.text("MaxParentalRating")?.let { "Rating level $it" } ?: "No limit", APPLIES,
                    "Titles rated above it are hidden by the server."),
                ServerSetting("Blocked tags", policy.list("BlockedTags").ifEmpty { listOf("None") }.joinToString(", "), APPLIES),
                ServerSetting("Live TV", yesNo(policy.flag("EnableLiveTvAccess")), APPLIES),
                ServerSetting("Watch parties", policy.text("SyncPlayAccess") ?: "Not set", APPLIES),
                ServerSetting("Access away from home", yesNo(policy.flag("EnableRemoteAccess")), APPLIES),
            ),
        )
        val server = info?.let { i ->
            ServerSettingsGroup(
                "Server", null,
                listOfNotNull(
                    i.text("ServerName")?.let { ServerSetting("Name", it, INFO) },
                    i.text("Version")?.let { ServerSetting("Jellyfin version", it, INFO) },
                    i.text("Id")?.let { ServerSetting("Server id", it, INFO) },
                ),
            )
        }
        return ServerSettings(listOfNotNull(languages, playback, home, account, server))
    }

    /**
     * How the server converts what a device can't play (Dashboard → Playback → Transcoding,
     * `System/Configuration/encoding`, admins only), with the settings that decide how HDR looks
     * when it's tone-mapped for a screen without it.
     */
    fun converting(enc: JsonObject): ServerSettingsGroup {
        fun text(key: String) = (enc[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        fun flag(key: String) = (enc[key] as? JsonPrimitive)?.booleanOrNull
        fun onOff(v: Boolean?) = when (v) { true -> "On"; false -> "Off"; null -> "Not set" }
        val accel = text("HardwareAccelerationType") ?: "none"
        val hardware = accel != "none"
        val decodes = (enc["HardwareDecodingCodecs"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.lowercase() }.orEmpty()
        val hevc = "hevc" in decodes
        val hevc10 = flag("EnableDecodingColorDepth10Hevc") == true
        val toneMapping = flag("EnableTonemapping") == true
        val vpp = flag("EnableVppTonemapping") == true && accel in setOf("qsv", "vaapi")
        val algorithm = text("TonemappingAlgorithm") ?: "none"
        return ServerSettingsGroup(
            "Converting on the server", "Jellyfin dashboard → Playback → Transcoding",
            listOfNotNull(
                ServerSetting("Hardware acceleration", ACCELERATION[accel] ?: accel, APPLIES,
                    if (hardware) "Converting is done by the graphics chip: fast enough for 4K HDR."
                    else "Check: the processor does all converting. A 4K HDR film converted and tone-mapped on the processor is often too slow to play smoothly."),
                if (hardware) ServerSetting("Hardware decoding", decodes.ifEmpty { listOf("none") }.joinToString(", ") { CODECS[it] ?: it }, APPLIES,
                    if (!hevc) "Check: HEVC isn't decoded by the graphics chip, so 4K HDR films are read on the processor first."
                    else null) else null,
                if (hardware && hevc) ServerSetting("10-bit HEVC decoding", onOff(hevc10), APPLIES,
                    if (!hevc10) "Check: off means every HDR film (10-bit HEVC) is decoded on the processor." else null) else null,
                if (hardware) ServerSetting("Hardware encoding", onOff(flag("EnableHardwareEncoding")), APPLIES) else null,
                ServerSetting("Tone mapping", onOff(toneMapping), APPLIES,
                    when {
                        hardware && !toneMapping -> "Check: off means HDR films sent to screens without HDR come out washed out, with grey, muted colours. Turn it on."
                        !hardware -> "Without hardware acceleration Jellyfin tone-maps on the processor, which is slow at 4K."
                        else -> "HDR films sent to screens without HDR are converted to look right in SDR."
                    }),
                if (vpp) ServerSetting("Intel VPP tone mapping", "On", APPLIES,
                    "Faster, but it only handles HDR10 and usually looks flatter than Jellyfin's own tone mapping. Try it off if converted HDR looks dull.") else null,
                if (toneMapping) ServerSetting("Tone-mapping algorithm", ALGORITHMS[algorithm] ?: algorithm, APPLIES,
                    when (algorithm) {
                        "bt2390" -> "Recommended: the most natural brightness and colour."
                        "none", "clip", "linear" -> "Check: this one crushes highlights or flattens the picture. BT.2390 is the recommended choice."
                        else -> "BT.2390 usually looks most natural."
                    }) else null,
                if (toneMapping) ServerSetting("Tone-mapping mode / range", "${text("TonemappingMode") ?: "?"} / ${text("TonemappingRange") ?: "?"}", APPLIES,
                    "Auto / Auto unless something looks wrong.") else null,
                if (toneMapping) text("TonemappingDesat")?.let { d ->
                    ServerSetting("Desaturation", d, APPLIES,
                        if ((d.toDoubleOrNull() ?: 0.0) > 0.5) "Check: high values wash colours out in bright scenes. 0 keeps them." else null)
                } else null,
                if (toneMapping) text("TonemappingPeak")?.let { ServerSetting("Target peak (nits)", it, APPLIES, "100 suits most SDR screens.") } else null,
                ServerSetting("HEVC encoding allowed", onOff(flag("AllowHevcEncoding")), APPLIES,
                    "Off: converted video is sent as H.264, which needs a higher bitrate for the same picture."),
                text("EncoderPreset")?.let { ServerSetting("Encoder preset", it, APPLIES, "Slower presets give a better picture for the same bitrate, if the server keeps up.") },
            ),
        )
    }

    private val ACCELERATION = mapOf(
        "none" to "None (processor only)", "qsv" to "Intel Quick Sync", "vaapi" to "VA-API", "nvenc" to "NVIDIA NVENC",
        "amf" to "AMD AMF", "videotoolbox" to "Apple VideoToolbox", "v4l2m2m" to "V4L2", "rkmpp" to "Rockchip MPP",
    )
    private val CODECS = mapOf("h264" to "H.264", "hevc" to "HEVC", "vp9" to "VP9", "av1" to "AV1", "mpeg2video" to "MPEG-2", "vc1" to "VC-1", "vp8" to "VP8", "mpeg4" to "MPEG-4")
    private val ALGORITHMS = mapOf(
        "bt2390" to "BT.2390", "hable" to "Hable", "reinhard" to "Reinhard", "mobius" to "Möbius",
        "gamma" to "Gamma", "linear" to "Linear", "clip" to "Clip", "none" to "None",
    )

    /** What Jellyfin's remembering does, next to Nitrate's own. */
    private const val REMEMBER_NOTE = "The server keeps your last pick for each title (by track number) and offers it next time, in any app. " +
        "Nitrate's own picks come first: per show, so the next episode follows, matched by language and track name."

    private fun subtitleModeLabel(mode: String?) = when (mode) {
        "Default" -> "Default"
        "Always" -> "Always"
        "OnlyForced" -> "Only forced"
        "None" -> "None"
        "Smart" -> "Smart"
        null -> "Not set"
        else -> mode
    }

    private fun subtitleModeNote(mode: String?) = when (mode) {
        "Default" -> "Subtitles the file marks default or forced start; otherwise off."
        "Always" -> "Subtitles in your preferred language always start."
        "OnlyForced" -> "Only forced subtitles (signs, foreign lines) start."
        "None" -> "Subtitles never start by themselves."
        "Smart" -> "Subtitles in your preferred language start when the audio isn't in your preferred audio language."
        else -> null
    }
}
