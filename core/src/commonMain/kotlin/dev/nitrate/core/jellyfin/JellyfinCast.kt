package dev.nitrate.core.jellyfin

import dev.nitrate.core.cast.CastCommand
import dev.nitrate.core.cast.CastPlay
import dev.nitrate.core.cast.CastStatus
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.MediaKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Who the receiver plays as: it fetches and reports with the user's own sign-in, as Jellyfin's apps do. */
internal data class CastSignIn(val userId: String, val deviceId: String, val accessToken: String, val serverAddress: String, val serverId: String?)

/**
 * Messages for Jellyfin's Chromecast receiver (`urn:x-cast:com.connectsdk`): a command, its options,
 * and the sign-in the receiver uses to reach the server; and the receiver's own status messages
 * (`playbackstart`/`playbackprogress`/`playbackstop`, carrying a play state and the item).
 */
internal object JellyfinCast {
    private fun ticks(ms: Long) = ms * 10_000

    private fun typeName(item: MediaItem) = itemTypeName(item.kind) ?: when (item.kind) {
        ItemKind.TV_CHANNEL -> "TvChannel"
        else -> "Video"
    }

    private fun mediaType(item: MediaItem) =
        if (item.kind == ItemKind.AUDIO || item.kind == ItemKind.AUDIOBOOK || item.mediaKind == MediaKind.AUDIO) "Audio" else "Video"

    private fun message(signIn: CastSignIn, command: String, options: JsonObject?) = buildJsonObject {
        options?.let { put("options", it) }
        put("command", command)
        put("userId", signIn.userId.replace("-", ""))
        put("deviceId", signIn.deviceId)
        put("accessToken", signIn.accessToken)
        put("serverAddress", signIn.serverAddress.trimEnd('/'))
        signIn.serverId?.let { put("serverId", it) }
        put("receiverName", "Nitrate")
    }.toString()

    fun play(signIn: CastSignIn, play: CastPlay): String {
        val options = buildJsonObject {
            put("items", buildJsonArray {
                play.items.forEach { item ->
                    add(buildJsonObject {
                        put("Id", item.id.replace("-", ""))
                        put("Name", item.name)
                        put("Type", typeName(item))
                        put("MediaType", mediaType(item))
                        item.runtimeMs?.let { put("RunTimeTicks", ticks(it)) }
                        item.albumId?.let { put("AlbumId", it.replace("-", "")) }
                        item.seriesId?.let { put("SeriesId", it.replace("-", "")) }
                        item.seriesName?.let { put("SeriesName", it) }
                    })
                }
            })
            put("startIndex", play.startIndex)
            put("startPositionTicks", ticks(play.startMs.coerceAtLeast(0)))
            play.mediaSourceId?.let { put("mediaSourceId", it.replace("-", "")) }
            play.audioIndex?.let { put("audioStreamIndex", it) }
            play.subtitleIndex?.let { put("subtitleStreamIndex", it) }
            put("ids", buildJsonArray { play.items.forEach { add(JsonPrimitive(it.id.replace("-", ""))) } })
        }
        return message(signIn, "PlayNow", options)
    }

    fun command(signIn: CastSignIn, command: CastCommand, value: Long?): String = when (command) {
        CastCommand.PAUSE -> message(signIn, "Pause", null)
        CastCommand.UNPAUSE -> message(signIn, "Unpause", null)
        CastCommand.STOP -> message(signIn, "Stop", null)
        CastCommand.NEXT -> message(signIn, "NextTrack", null)
        CastCommand.PREVIOUS -> message(signIn, "PreviousTrack", null)
        CastCommand.SEEK -> message(signIn, "Seek", buildJsonObject { put("position", ticks(value ?: 0)) })
        CastCommand.SET_VOLUME -> message(signIn, "SetVolume", buildJsonObject { put("volume", (value ?: 0).coerceIn(0, 100)) })
        CastCommand.VOLUME_UP -> message(signIn, "VolumeUp", null)
        CastCommand.VOLUME_DOWN -> message(signIn, "VolumeDown", null)
        CastCommand.TOGGLE_MUTE -> message(signIn, "ToggleMute", null)
        CastCommand.AUDIO_TRACK -> message(signIn, "SetAudioStreamIndex", buildJsonObject { put("index", value ?: 0) })
        CastCommand.SUBTITLE_TRACK -> message(signIn, "SetSubtitleStreamIndex", buildJsonObject { put("index", value ?: -1) })
    }

    /** A receiver message, or null when it isn't about playback. */
    fun status(json: String): CastStatus? {
        val root = runCatching { JellyfinJson.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
        val type = root.str("type")?.lowercase() ?: return null
        val data = root["data"] as? JsonObject ?: JsonObject(emptyMap())
        if (type == "playbackstop") return CastStatus(itemId = data.str("ItemId")?.let(::normalizeId), stopped = true)
        if (type != "playbackstart" && type != "playbackprogress") return null
        val state = data["PlayState"] as? JsonObject ?: data
        val item = data["NowPlayingItem"] as? JsonObject
        val id = (item?.str("Id") ?: data.str("ItemId"))?.let(::normalizeId)
        val name = item?.let { i ->
            val title = i.str("Name")
            val series = i.str("SeriesName")
            if (series != null && title != null) "$series · $title" else title
        }
        return CastStatus(
            itemId = id,
            title = name,
            positionMs = (state.long("PositionTicks") ?: 0) / 10_000,
            durationMs = item?.long("RunTimeTicks")?.let { it / 10_000 },
            paused = state.bool("IsPaused") ?: false,
            audioIndex = state.long("AudioStreamIndex")?.toInt(),
            subtitleIndex = state.long("SubtitleStreamIndex")?.toInt(),
        )
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull
}
