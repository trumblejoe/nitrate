package dev.nitrate.core.channels

import dev.nitrate.core.livetv.TvProgram
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.plugins.NitratePlugin

/** What a lineup entry plays. [wire] is the Companion plugin's name for it. */
enum class SourceKind(val wire: String, val label: String) {
    SERIES("Series", "Show"),
    ITEM("Item", "Title"),
    COLLECTION("Collection", "Collection"),
    PLAYLIST("Playlist", "Playlist"),
    LIBRARY("Library", "Library"),
    ;

    companion object {
        fun of(wire: String) = entries.firstOrNull { it.wire.equals(wire, ignoreCase = true) } ?: ITEM

        /** The kind for something picked from the library. */
        fun forItem(item: MediaItem): SourceKind = when (item.kind) {
            ItemKind.SERIES -> SERIES
            ItemKind.BOX_SET -> COLLECTION
            ItemKind.PLAYLIST -> PLAYLIST
            ItemKind.LIBRARY -> LIBRARY
            else -> ITEM
        }
    }
}

/** How a lineup's sources take turns. */
enum class ChannelMix(val wire: String, val label: String) {
    ROTATE("Rotate", "One from each in turn"),
    SEQUENTIAL("Sequential", "Each in full, one after another"),
    SHUFFLE("Shuffle", "Mixed at random"),
    ;

    companion object {
        fun of(wire: String) = entries.firstOrNull { it.wire.equals(wire, ignoreCase = true) } ?: ROTATE
    }
}

/** One entry in a lineup; [shuffle] plays its own titles in a new random order each round. */
data class ChannelSource(val kind: SourceKind, val id: String, val name: String, val shuffle: Boolean = false)

/**
 * A time slot with its own lineup. [days] 0 = Sunday … 6 = Saturday; minutes after midnight in
 * the server's time; an end at or before the start runs past midnight.
 */
data class ChannelBlock(
    val name: String,
    val days: List<Int>,
    val startMinute: Int,
    val endMinute: Int,
    val sources: List<ChannelSource>,
    val mix: ChannelMix = ChannelMix.ROTATE,
)

/**
 * One of the household's channels (Nitrate Companion): an on-demand TV schedule made from the
 * library. [users] (who it's granted to, besides admins) is only filled in for admins. An empty
 * [id] is a channel not saved yet.
 */
data class HouseChannel(
    val id: String = "",
    val name: String = "",
    val number: Int = 1,
    val sources: List<ChannelSource> = emptyList(),
    val mix: ChannelMix = ChannelMix.ROTATE,
    val alignMinutes: Int = 30,
    val blocks: List<ChannelBlock> = emptyList(),
    val everyone: Boolean = true,
    val users: List<String> = emptyList(),
)

/** A program on a channel. [available]: this viewer may watch it (else it's skipped when tuning in). */
data class ChannelProgram(
    val itemId: String,
    val startMs: Long,
    val endMs: Long,
    val title: String,
    val subtitle: String?,
    val kind: String,
    val block: String?,
    val available: Boolean,
) {
    fun isOn(nowMs: Long) = nowMs in startMs until endMs
}

/** A channel and its programs in a stretch of time. */
data class ChannelGuide(val channel: HouseChannel, val programs: List<ChannelProgram>)

/** The channels plugin: switched on per device in Settings → Plugins; needs the Companion plugin's "channels". */
object ChannelsPlugin : NitratePlugin {
    override val id = "channels"
    override val name = "Channels"
    override val description = "TV channels made from your library: a guide everyone in the house shares, " +
        "watched on demand. Tuning in starts what's on from its beginning and carries on in order; nothing " +
        "plays unless you're watching. Needs the Nitrate Companion server plugin."
    override val enabledByDefault = true

    /** The Companion feature it needs. */
    const val FEATURE = "channels"
}

/** Guide and editor helpers for channels. */
object Channels {
    /** How many programs a tune-in queues. */
    const val TUNE_COUNT = 20

    private val DAY_NAMES = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

    /** "7:30" / "19:30"-style time for minutes after midnight. */
    fun time(minute: Int): String {
        val m = ((minute % 1440) + 1440) % 1440
        return "${m / 60}:${(m % 60).toString().padStart(2, '0')}"
    }

    /** "Sat 8:00–10:00", "Weekdays 18:00–20:00", "Every day 23:00–1:00". */
    fun describe(block: ChannelBlock): String {
        val days = block.days.toSortedSet()
        val dayText = when (days) {
            (0..6).toSet() -> "Every day"
            (1..5).toSet() -> "Weekdays"
            setOf(0, 6) -> "Weekends"
            else -> days.joinToString(", ") { DAY_NAMES[it] }
        }
        return "$dayText ${time(block.startMinute)}–${time(block.endMinute)}"
    }

    fun dayName(day: Int) = DAY_NAMES[day]

    /** The guide's programs as Live TV-style programs, so the guide layout ([dev.nitrate.core.livetv.Guide]) works on them. */
    fun asTvPrograms(guide: ChannelGuide): List<TvProgram> = guide.programs.map { p ->
        TvProgram(
            id = "${guide.channel.id}:${p.startMs}",
            channelId = guide.channel.id,
            title = p.title,
            startMs = p.startMs,
            endMs = p.endMs,
            episodeTitle = p.subtitle,
            isMovie = p.kind == "Movie",
            isSeries = p.kind == "Episode",
        )
    }

    /** The program on [channel] at [nowMs], or the next one (between programs). */
    fun onNow(programs: List<ChannelProgram>, nowMs: Long): ChannelProgram? =
        programs.firstOrNull { it.isOn(nowMs) } ?: programs.filter { it.startMs >= nowMs }.minByOrNull { it.startMs }

    /** Adds [item] to a lineup (once). */
    fun add(sources: List<ChannelSource>, item: MediaItem): List<ChannelSource> =
        if (sources.any { it.id == item.id }) sources else sources + ChannelSource(SourceKind.forItem(item), item.id, item.name)

    /** Moves the source at [from] by [by] places. */
    fun move(sources: List<ChannelSource>, from: Int, by: Int): List<ChannelSource> {
        val to = (from + by).coerceIn(0, sources.lastIndex)
        if (from == to) return sources
        return sources.toMutableList().apply { add(to, removeAt(from)) }
    }

    /** What's wrong with [channel] before saving, or null. */
    fun problem(channel: HouseChannel): String? = when {
        channel.name.isBlank() -> "Give the channel a name."
        channel.sources.isEmpty() && channel.blocks.all { it.sources.isEmpty() } -> "Add something for the channel to play."
        channel.blocks.any { it.days.isEmpty() } -> "Each time slot needs at least one day."
        !channel.everyone && channel.users.isEmpty() -> "Choose who can watch it, or let everyone."
        else -> null
    }

    /** The next free channel number. */
    fun nextNumber(channels: List<HouseChannel>) = (channels.maxOfOrNull { it.number } ?: 0) + 1
}
