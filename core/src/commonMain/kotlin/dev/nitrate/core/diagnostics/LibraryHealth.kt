package dev.nitrate.core.diagnostics

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.pages.StreamInfo
import dev.nitrate.core.playback.CapabilityRules
import dev.nitrate.core.playback.DeviceCapabilities
import dev.nitrate.core.playback.PlaybackSettings

/** One movie or show as the health check reads it from the server. */
data class HealthTitle(
    val item: MediaItem,
    val hasOverview: Boolean,
    /** Matched to an online database (TMDB, IMDb, TVDB...). */
    val matched: Boolean,
    /** The streams of each file: a movie's own, a show's per episode (with the episode). */
    val files: List<Pair<MediaItem, StreamInfo>>,
)

/** What a health check read: titles, and episodes the server knows are missing. */
data class HealthScan(val titles: List<HealthTitle>, val missingEpisodes: List<MediaItem>)

/** What the check looks for, in the order shown. */
enum class HealthCheck(val title: String, val explain: String) {
    ALWAYS_CONVERTS("Always converted on this device", "The server has to convert these to play them here, every time."),
    TIMING("Sound and picture timing", "Files made in a way that can put sound and picture out of step in some players, or when the server converts them. Remaking the file (remuxing it) usually fixes it."),
    DUPLICATES("Duplicate copies", "The same title more than once: merged, they become versions of one title."),
    NO_ARTWORK("Missing artwork", "No poster or background picture."),
    UNMATCHED("Not identified", "Not matched to an online database, so details and artwork can't be fetched."),
    NO_DESCRIPTION("Missing description", "No summary of what it's about."),
    MISSING_EPISODES("Missing episodes", "Episodes that have aired but aren't in the library."),
}

/** What can be done about a finding (the ones changing the server need an admin). */
enum class HealthFix(val label: String, val needsAdmin: Boolean) {
    FIND_IMAGES("Find artwork", true),
    FIND_METADATA("Find details", true),
    IDENTIFY("Identify…", true),
    MERGE("Merge into one title", true),
    HOW_IT_PLAYS("How it plays", false),
    OPEN("Open", false),
}

data class HealthFinding(
    val check: HealthCheck,
    val item: MediaItem,
    val detail: String,
    /** Other items it concerns (the other copies of a duplicate). */
    val related: List<MediaItem> = emptyList(),
    val fixes: List<HealthFix> = emptyList(),
)

/**
 * The library health check: titles missing artwork, descriptions or a match, copies of one
 * title not merged as versions, files this device can never play as they are, and episodes the
 * server knows are missing. Pure: the server is read by [dev.nitrate.core.MediaServer.healthScan].
 */
object LibraryHealth {

    fun findings(scan: HealthScan, caps: DeviceCapabilities, settings: PlaybackSettings, timing: List<FileTiming> = emptyList()): Map<HealthCheck, List<HealthFinding>> {
        val out = mutableMapOf<HealthCheck, MutableList<HealthFinding>>()
        fun add(f: HealthFinding) { out.getOrPut(f.check) { mutableListOf() } += f }

        // The server's timing check, for the files of this scan (a film, or each of a show's episodes).
        if (timing.isNotEmpty()) {
            val byId = timing.associateBy { it.itemId }
            for (t in scan.titles) {
                val files = if (t.files.isEmpty()) listOf(t.item) else t.files.map { it.first }
                for (file in files.distinctBy { it.id }) {
                    val found = byId[file.id] ?: continue
                    add(HealthFinding(HealthCheck.TIMING, file, found.issues.joinToString(" ") { it.text }, fixes = listOf(HealthFix.HOW_IT_PLAYS, HealthFix.OPEN)))
                }
            }
        }

        for (t in scan.titles) {
            val i = t.item
            val noPoster = i.images.primary == null
            val noBackdrop = i.images.backdrops.isEmpty()
            if (noPoster || noBackdrop) add(HealthFinding(HealthCheck.NO_ARTWORK, i, when {
                noPoster && noBackdrop -> "No poster or background"
                noPoster -> "No poster"
                else -> "No background"
            }, fixes = listOf(HealthFix.FIND_IMAGES)))
            if (!t.matched) add(HealthFinding(HealthCheck.UNMATCHED, i, "No TMDB, IMDb or TVDB match", fixes = listOf(HealthFix.IDENTIFY)))
            else if (!t.hasOverview) add(HealthFinding(HealthCheck.NO_DESCRIPTION, i, "No description", fixes = listOf(HealthFix.FIND_METADATA)))

            // A device reporting no video decoders at all hasn't been read properly: say nothing rather than everything.
            val converting = if (caps.video.isEmpty()) emptyList() else t.files.mapNotNull { (file, streams) -> whyConverts(streams, caps, settings)?.let { file to it } }
            if (converting.isNotEmpty()) {
                val reasons = converting.map { it.second }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.joinToString("; ") { it.key }
                val detail = if (i.kind == ItemKind.SERIES) "${converting.size} of ${t.files.size} episodes: $reasons" else reasons
                add(HealthFinding(HealthCheck.ALWAYS_CONVERTS, i, detail, fixes = listOf(HealthFix.HOW_IT_PLAYS)))
            }
        }

        // The same movie as separate titles (not versions of one): same name and year.
        scan.titles.filter { it.item.kind == ItemKind.MOVIE }
            .groupBy { key(it.item.name) to it.item.productionYear }
            .values.filter { it.size > 1 }
            .forEach { copies ->
                val first = copies.first().item
                add(HealthFinding(
                    HealthCheck.DUPLICATES, first,
                    "${copies.size} copies" + (first.productionYear?.let { " ($it)" } ?: ""),
                    related = copies.drop(1).map { it.item },
                    fixes = listOf(HealthFix.MERGE),
                ))
            }

        scan.missingEpisodes.filter { it.seriesId != null }.groupBy { it.seriesId!! }.forEach { (seriesId, episodes) ->
            val sorted = episodes.sortedWith(compareBy({ it.seasonNumber ?: 0 }, { it.indexNumber ?: 0 }))
            val labels = sorted.take(6).joinToString(", ") { "S${it.seasonNumber ?: 0} E${it.indexNumber ?: 0}" } + if (sorted.size > 6) " and ${sorted.size - 6} more" else ""
            val series = MediaItem(seriesId, sorted.first().seriesName ?: "A show", ItemKind.SERIES)
            add(HealthFinding(HealthCheck.MISSING_EPISODES, series, "${episodes.size} missing: $labels", fixes = listOf(HealthFix.OPEN)))
        }

        return HealthCheck.entries.mapNotNull { c -> out[c]?.sortedBy { it.item.name.lowercase() }?.let { c to it } }.toMap()
    }

    private val RANGE_TYPES = mapOf("sdr" to "SDR", "hdr10" to "HDR10", "hdr10plus" to "HDR10Plus", "hlg" to "HLG", "dovi" to "DOVI",
        "hdr10base" to "HDR10", "hlgbase" to "HLG", "sdrbase" to "SDR")
    private val RANGE_NAMES = mapOf("dovi" to "Dolby Vision", "hdr10plus" to "HDR10+", "hdr10" to "HDR10", "hlg" to "HLG")

    private fun key(name: String) = name.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Why a file can never play as it is on this device (null: it can, at least sometimes):
     * a video codec, bit depth or HDR format this device doesn't take, or no audio track it plays.
     */
    fun whyConverts(streams: StreamInfo, caps: DeviceCapabilities, settings: PlaybackSettings): String? {
        val video = streams.video.firstOrNull()
        if (video != null) {
            val codec = video.codec
            val limit = CapabilityRules.videoLimits(caps, settings).firstOrNull { it.codec == codec }
            if (codec != null && limit == null) return "${codecName(codec)} video"
            if (limit != null && (video.bitDepth ?: 8) > 8 && !limit.tenBit) return "10-bit ${codecName(codec)}"
            val ranges = CapabilityRules.allowedRangeTypes(caps, settings).toSet()
            // Shown as is when any of its forms (Dolby Vision's HDR10 base layer, say) is one the display takes.
            if (video.ranges.isNotEmpty() && video.ranges.none { RANGE_TYPES[it] in ranges }) {
                val best = listOf("dovi", "hdr10plus", "hdr10", "hlg").firstOrNull { it in video.ranges }
                return "${RANGE_NAMES[best] ?: "HDR"} on this display"
            }
        }
        if (streams.audio.isNotEmpty()) {
            val allowed = CapabilityRules.audioCodecs(caps, settings).filter { it.allowed }.map { it.id }.toSet()
            if (streams.audio.none { it in allowed }) return "${streams.audio.joinToString("/") { codecName(it) }} sound"
        }
        return null
    }

    private fun codecName(codec: String?) = when (codec) {
        "hevc" -> "HEVC"
        "h264" -> "H.264"
        "av1" -> "AV1"
        "vp9" -> "VP9"
        "mpeg2video" -> "MPEG-2"
        "mpeg4" -> "MPEG-4"
        "vc1" -> "VC-1"
        "truehd" -> "TrueHD"
        "dts" -> "DTS"
        "eac3" -> "Dolby Digital Plus"
        "ac3" -> "Dolby Digital"
        null -> "Unknown"
        else -> codec.uppercase()
    }
}
