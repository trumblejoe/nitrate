package dev.nitrate.core.playback

import kotlinx.serialization.Serializable

@Serializable
data class SummaryLine(val label: String, val value: String)

@Serializable
data class SummaryTitle(val itemId: String, val name: String, val delivery: String, val reason: String? = null)

@Serializable
data class SummaryTest(
    val itemId: String,
    val name: String,
    val summary: String,
    val ok: Boolean,
    val answers: List<SummaryLine> = emptyList(),
    val differences: List<String> = emptyList(),
)

/**
 * One device's playback summary for its household's admin (Companion feature `devices`): how its
 * library plays there (counts and the titles converted), its settings that decide it, what Android
 * reports for HDR, its test plays and what its user saw at the TV and receiver. Each device sends
 * its own; only admins read them all ([HouseholdDevice]).
 */
@Serializable
data class DeviceSummary(
    val deviceName: String,
    val model: String,
    val app: String,
    val atMs: Long,
    val library: String? = null,
    val settings: List<SummaryLine> = emptyList(),
    val displayHdr: List<String> = emptyList(),
    val counts: List<SummaryLine> = emptyList(),
    val converted: List<SummaryTitle> = emptyList(),
    val tests: List<SummaryTest> = emptyList(),
    val seen: List<SummaryLine> = emptyList(),
) {
    companion object {
        /** Most converted titles kept, so the summary stays small. */
        const val MAX_TITLES = 400

        fun build(
            deviceName: String,
            model: String,
            app: String,
            atMs: Long,
            caps: DeviceCapabilities,
            settings: PlaybackSettings,
            overview: PlaybackOverview?,
            tests: Collection<TestPlayResult>,
        ): DeviceSummary = DeviceSummary(
            deviceName = deviceName,
            model = model,
            app = app,
            atMs = atMs,
            library = overview?.library,
            settings = PlaybackOverview.settingsSummary(settings).map { (k, v) -> SummaryLine(k, v) },
            displayHdr = caps.hdr.sorted(),
            counts = overview?.let { o -> OverviewFilter.entries.map { SummaryLine(it.label, o.count(it).toString()) } }.orEmpty(),
            converted = overview?.entries.orEmpty()
                .filter { it.prediction.delivery == PredictedDelivery.CONVERTED || it.prediction.delivery == PredictedDelivery.SOUND_CONVERTED }
                .take(MAX_TITLES)
                .map { SummaryTitle(it.item.id, it.name, it.prediction.delivery.label, it.prediction.reasons.firstOrNull()) },
            tests = tests.sortedByDescending { it.atMs }.take(50).map { t ->
                SummaryTest(
                    t.itemId, t.name, t.summary, t.ok,
                    answers = TestPlay.questions(t).mapNotNull { q -> t.answers[q.id]?.let { SummaryLine(q.text.substringBefore("?") + "?", it.label) } },
                    differences = t.trail?.differences.orEmpty(),
                )
            },
            seen = settings.deviceCheck?.seen.orEmpty().values.sortedBy { it.title }.map { s ->
                SummaryLine(s.title, s.answer.label + (s.passthrough?.let { if (it) " (sent untouched)" else " (decoded on the device)" } ?: ""))
            },
        )
    }
}

/** A household device's latest summary, as the server keeps it. */
data class HouseholdDevice(
    val userId: String,
    val userName: String,
    val deviceId: String,
    val deviceName: String,
    val atMs: Long,
    /** Null when it came from an app that sends something this one can't read. */
    val summary: DeviceSummary?,
) {
    /** Picture or sound converted: the count from its overview, when it read one. */
    val convertedCount: Int?
        get() = summary?.counts?.filter { it.label == OverviewFilter.PICTURE.label || it.label == OverviewFilter.SOUND.label }
            ?.takeIf { it.isNotEmpty() }?.sumOf { it.value.toIntOrNull() ?: 0 }

    /** What stands out: tests not as predicted, things not seen at the TV or receiver. */
    val problems: List<String>
        get() = buildList {
            val s = summary ?: return@buildList
            s.tests.filter { !it.ok }.forEach { add("${it.name}: ${it.summary}") }
            s.seen.filter { it.value.startsWith(Answer.NO.label) }.forEach { add("${it.label}: not seen at the TV or receiver") }
        }
}

object Household {
    /** Titles converted on more than one device, most first: worth a version every device plays as it is. */
    fun mostConverted(devices: List<HouseholdDevice>): List<Pair<SummaryTitle, Int>> =
        devices.flatMap { d -> d.summary?.converted.orEmpty().map { d.deviceId to it } }
            .groupBy { it.second.itemId }
            .map { (_, list) -> list.first().second to list.map { it.first }.distinct().size }
            .filter { it.second > 1 }
            .sortedWith(compareByDescending<Pair<SummaryTitle, Int>> { it.second }.thenBy { it.first.name })
}

/** "just now", "5 min ago", "3 h ago", "2 days ago". */
fun agoLabel(atMs: Long, nowMs: Long): String {
    val s = ((nowMs - atMs) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86_400 -> "${s / 3600} h ago"
        else -> "${s / 86_400} day${if (s / 86_400 == 1L) "" else "s"} ago"
    }
}
