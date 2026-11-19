package dev.nitrate.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class PlayMethod { DIRECT_PLAY, DIRECT_STREAM, TRANSCODE }

@Serializable
enum class StreamKind { VIDEO, AUDIO, SUBTITLE, OTHER }

/** How a subtitle reaches the player: in the file, as its own file, in the HLS stream, or burned in. */
@Serializable
enum class SubtitleDelivery { EMBED, EXTERNAL, HLS, ENCODE }

/** One audio, video or subtitle stream in a media source. */
@Serializable
data class MediaStream(
    /** Server stream index, used to select it. */
    val index: Int,
    val kind: StreamKind,
    val codec: String? = null,
    val profile: String? = null,
    val language: String? = null,
    /** Human-readable title from the server, e.g. "English - Dolby TrueHD 7.1 - Default". */
    val title: String? = null,
    val isDefault: Boolean = false,
    val isForced: Boolean = false,
    val isExternal: Boolean = false,
    val delivery: SubtitleDelivery? = null,
    val deliveryUrl: String? = null,
    val channels: Int? = null,
    val channelLayout: String? = null,
    /** Audio sample rate in Hz. */
    val sampleRate: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val bitDepth: Int? = null,
    /** Video range type as the server names it: SDR, HDR10, HLG, DOVI, DOVIWithHDR10, ... */
    val rangeType: String? = null,
    /** Frames per second (video), e.g. 23.976. */
    val frameRate: Float? = null,
    /** Interlaced video (made for old TVs; software decoders don't deinterlace). */
    val isInterlaced: Boolean = false,
    /** Dolby Vision profile (5, 7, 8...), for Dolby Vision video. */
    val dvProfile: Int? = null,
)

@Serializable
data class MediaSource(
    val id: String?,
    val container: String?,
    val bitrate: Int?,
    val streams: List<MediaStream>,
    /** File size in bytes, when the server knows it. */
    val size: Long? = null,
    /** The version's name when a title has several ("4K", "Director's cut"...). */
    val name: String? = null,
    /**
     * The audio and subtitle streams the server picks for this user from their language settings
     * (subtitle -1: off), when [defaultsKnown]. Downloads keep them so they play the same offline.
     */
    val defaultAudioIndex: Int? = null,
    val defaultSubtitleIndex: Int? = null,
    val defaultsKnown: Boolean = false,
    /** Files carried inside the media file, such as the fonts styled subtitles use. */
    val attachments: List<MediaAttachment> = emptyList(),
)

/** A file carried inside a media file (MKV attachments: fonts for ASS subtitles, covers...). */
@Serializable
data class MediaAttachment(val index: Int, val fileName: String?, val mimeType: String?) {
    /** A font libass can use (TrueType, OpenType, collections). */
    val isFont: Boolean
        get() = mimeType?.lowercase()?.let { "font" in it || "truetype" in it || "opentype" in it } == true ||
            fileName?.lowercase()?.let { it.endsWith(".ttf") || it.endsWith(".otf") || it.endsWith(".ttc") } == true
}

/** Mime type the players use for a text subtitle codec, or null for image formats (PGS, VobSub). */
fun subtitleMimeType(codec: String?): String? = when (codec?.lowercase()) {
    "srt", "subrip" -> "application/x-subrip"
    "vtt", "webvtt" -> "text/vtt"
    "ass", "ssa" -> "text/x-ssa"
    "ttml" -> "application/ttml+xml"
    else -> null
}

/** A subtitle file served separately from the video, side-loaded into the player. */
data class ExternalSubtitle(val streamIndex: Int, val url: String, val mimeType: String, val language: String?, val label: String)

/** How an item will be played, as negotiated with the server. */
data class PlaybackPlan(
    val itemId: String,
    val url: String,
    val method: PlayMethod,
    val playSessionId: String?,
    val source: MediaSource,
    /** True when the URL is an HLS playlist (remux or transcode). */
    val isHls: Boolean,
    /** Server stream index of the selected audio track, if any. */
    val audioIndex: Int?,
    /** Server stream index of the selected subtitle track, or -1 for none. */
    val subtitleIndex: Int,
    val externalSubtitles: List<ExternalSubtitle>,
    /** The server's reasons for transcoding, e.g. VideoCodecNotSupported. */
    val transcodeReasons: List<String>,
    /** Every version of the item and how each would play here (one entry when there's only one). */
    val versions: List<VersionOption> = emptyList(),
    /** A download's saved ASS/SSA scripts (stream index → local path), for drawing them with libass offline. */
    val assScripts: Map<Int, String> = emptyMap(),
    /** A download's saved fonts (local paths), for those scripts. */
    val fontFiles: List<String> = emptyList(),
) {
    val mediaSourceId get() = source.id

    /** The selected subtitle carries its own styling (ASS/SSA). */
    val styledSubtitle: Boolean
        get() = source.streams.any { it.kind == StreamKind.SUBTITLE && it.index == subtitleIndex && dev.nitrate.core.playback.isStyledSubtitle(it.codec) }
}

/** One version of an item as the server offered it to this device. */
data class VersionOption(val id: String, val name: String?, val directPlay: Boolean, val directStream: Boolean, val bitrate: Int? = null)

/** A user's state for one item on the server, with when it was last played (epoch ms). */
data class RemoteUserData(val played: Boolean, val positionMs: Long, val lastPlayedMs: Long?)
