package dev.nitrate.core.playback

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.MediaSource

/** One film or episode and how it would play here. */
data class OverviewEntry(val item: MediaItem, val prediction: PlaybackPrediction, val source: MediaSource? = null) {
    /** "Film (2021)" or "Show · S1 E2". */
    val name: String
        get() = if (item.kind == ItemKind.EPISODE)
            listOfNotNull(item.seriesName, "S${item.seasonNumber ?: "?"} E${item.indexNumber ?: "?"}").joinToString(" · ")
        else item.name + (item.productionYear?.let { " ($it)" } ?: "")
}

/** What the overview can be narrowed to. */
enum class OverviewFilter(val label: String) {
    ALL("Everything"),
    DIRECT("Plays as it is"),
    REMUX("Repackaged"),
    SOUND("Sound converted"),
    PICTURE("Picture converted"),
    SOFTWARE("Software decoded"),
    SOUND_DECODED("Surround decoded here");

    fun matches(e: OverviewEntry): Boolean = when (this) {
        ALL -> true
        DIRECT -> e.prediction.delivery == PredictedDelivery.DIRECT
        REMUX -> e.prediction.delivery == PredictedDelivery.REMUX
        SOUND -> e.prediction.delivery == PredictedDelivery.SOUND_CONVERTED
        PICTURE -> e.prediction.delivery == PredictedDelivery.CONVERTED
        SOFTWARE -> e.prediction.decoder == DecoderTier.DEVICE_SOFTWARE || e.prediction.decoder == DecoderTier.NITRATE_SOFTWARE
        SOUND_DECODED -> e.prediction.sound == SoundRoute.DECODED && e.prediction.soundDetail?.contains(':') == true
    }
}

/**
 * Every film and episode the user can see, and how each would play on this device (see
 * [PlaybackPredictor]): for Settings → Library playback overview, so anyone can see what's
 * converted and why, and send it to the server's logs for the admin.
 */
data class PlaybackOverview(
    val entries: List<OverviewEntry>,
    /** What it was worked out for: the app, the device, and its settings that matter. */
    val app: String,
    val device: String,
    val createdAt: String,
    val settings: List<Pair<String, String>>,
    val library: String,
) {
    fun count(filter: OverviewFilter) = entries.count(filter::matches)

    /** The overview as text for the server's logs (Dashboard → Logs): settings, counts, then each title not playing as it is. */
    fun report(tests: Collection<TestPlayResult> = emptyList()): String = buildString {
        appendLine("Nitrate library playback overview")
        appendLine("App: $app")
        appendLine("Device: $device")
        appendLine("Library: $library")
        appendLine("Made: $createdAt")
        settings.forEach { (k, v) -> appendLine("$k: $v") }
        appendLine()
        appendLine("${entries.size} titles")
        OverviewFilter.entries.drop(1).forEach { f -> appendLine("  ${f.label}: ${count(f)}") }
        for (f in listOf(OverviewFilter.PICTURE, OverviewFilter.SOUND, OverviewFilter.REMUX, OverviewFilter.SOFTWARE, OverviewFilter.SOUND_DECODED)) {
            val list = entries.filter(f::matches)
            if (list.isEmpty()) continue
            appendLine()
            appendLine("== ${f.label} (${list.size}) ==")
            list.forEach { e ->
                appendLine("- ${e.name}: ${e.prediction.summary}")
                e.prediction.reasons.forEach { appendLine("    $it") }
                if (f == OverviewFilter.SOUND_DECODED) e.prediction.soundDetail?.let { appendLine("    $it") }
            }
        }
        if (tests.isNotEmpty()) {
            appendLine()
            appendLine("== Test plays (${tests.size}) ==")
            tests.sortedBy { it.name }.forEach { t ->
                appendLine("- ${t.name}: ${t.summary}")
                t.trail?.render()?.lines()?.filter { it.isNotBlank() }?.forEach { appendLine("    $it") }
            }
        }
        appendLine()
        appendLine("== Every title ==")
        entries.forEach { e -> appendLine("- ${e.name}: ${e.prediction.summary}") }
    }

    companion object {
        /** Predicts every title in [sources] with [predict] (the profile check, for this device). */
        fun build(sources: List<Pair<MediaItem, MediaSource>>, predict: (MediaItem, MediaSource) -> PlaybackPrediction): List<OverviewEntry> =
            sources.map { (item, source) -> OverviewEntry(item, predict(item, source), source) }

        /** This device's settings that decide how things play, for the report's header. */
        fun settingsSummary(s: PlaybackSettings): List<Pair<String, String>> = buildList {
            add("Bitrate limit" to formatBitrate(s.maxBitrate))
            add("Refresh rate" to s.frameRateMatch.label)
            add("Sound" to s.soundMode.label)
            add("Direct play / direct stream" to "${if (s.allowDirectPlay) "on" else "off"} / ${if (s.allowDirectStream) "on" else "off"}")
            if (s.overrides.isNotEmpty()) add("Format overrides" to s.overrides.entries.joinToString { "${capabilityLabel(it.key)} ${it.value.label}" })
            val blocked = s.learnedFailures.filterValues { it.blocksFormat }.keys
            if (blocked.isNotEmpty()) add("Formats converted after failures" to blocked.joinToString(transform = ::capabilityLabel))
            if (s.failedTitles.isNotEmpty()) add("Titles converted after failing here" to "${s.failedTitles.size}")
            add("Device check" to (s.deviceCheck?.let { "run (${it.clips.size} samples${it.refresh?.let { r -> if (r.switched) ", refresh switch tested" else "" } ?: ""})" } ?: "not run"))
        }
    }
}
