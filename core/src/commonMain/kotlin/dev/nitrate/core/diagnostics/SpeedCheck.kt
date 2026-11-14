package dev.nitrate.core.diagnostics

/** How a step did: within what's normal, a bit slow, or slow enough to feel. */
enum class Rating(val label: String) { GOOD("Good"), OK("OK"), SLOW("Slow"), FAILED("Failed") }

/**
 * One timed part of a server speed check, repeated [samplesMs].size times; the middle value is
 * what's judged, so one hiccup doesn't decide it.
 */
data class SpeedStep(
    val id: String,
    val label: String,
    /** What the step measures, in words. */
    val detail: String,
    val samplesMs: List<Long> = emptyList(),
    /** Extra facts, e.g. "38 items" or "23.4 Mbps". */
    val extra: String? = null,
    /** Download speed in megabits per second (the download step only). */
    val mbps: Double? = null,
    val error: String? = null,
) {
    val medianMs: Long? get() = samplesMs.sorted().takeIf { it.isNotEmpty() }?.let { it[it.size / 2] }

    val rating: Rating get() {
        if (error != null) return Rating.FAILED
        mbps?.let { return if (it >= 40) Rating.GOOD else if (it >= 10) Rating.OK else Rating.SLOW }
        val m = medianMs ?: return Rating.FAILED
        val (good, ok) = SpeedCheck.LIMITS[id] ?: (500L to 1500L)
        return if (m <= good) Rating.GOOD else if (m <= ok) Rating.OK else Rating.SLOW
    }

    /** "42 ms (38–55)" or the speed for the download step. */
    val result: String get() = when {
        error != null -> "Failed: $error"
        mbps != null -> "${(mbps * 10).toLong() / 10.0} Mbps"
        else -> medianMs?.let { m -> "$m ms" + if (samplesMs.size > 1) " (${samplesMs.min()}–${samplesMs.max()})" else "" } ?: "—"
    }
}

/** A finished check: every step and what they add up to. */
data class SpeedReport(
    val app: String,
    val device: String,
    val server: String,
    val createdAt: String,
    val steps: List<SpeedStep>,
) {
    /** Plain-English findings: what is slow and why, or that all is well. */
    val findings: List<String> get() = SpeedCheck.findings(steps)

    fun render(): String = buildString {
        append("$app server speed check, $createdAt\n")
        append("Device: $device\nServer: $server\n\n")
        append("== Findings ==\n")
        findings.forEach { append("- ").append(it).append('\n') }
        append("\n== Steps ==\n")
        steps.forEach { s ->
            append("${s.label}: ${s.result} [${s.rating.label}]")
            s.extra?.let { append(" — ").append(it) }
            append('\n')
            append("  ").append(s.detail).append('\n')
            if (s.samplesMs.size > 1) append("  samples: ${s.samplesMs.joinToString(", ")} ms\n")
        }
    }
}

object SpeedCheck {
    const val PING = "ping"
    const val SIGNED_IN = "me"
    const val LIBRARIES = "views"
    const val SEARCH = "search"
    const val HOME = "home"
    const val DOWNLOAD = "download"
    const val PARSE = "parse"

    /** Per step: (good up to, OK up to) in milliseconds. */
    val LIMITS = mapOf(
        PING to (60L to 150L),
        SIGNED_IN to (150L to 400L),
        LIBRARIES to (200L to 500L),
        SEARCH to (500L to 1500L),
        HOME to (1200L to 3000L),
        PARSE to (80L to 250L),
    )

    /** What the steps say together: the network, the server or this device. */
    fun findings(steps: List<SpeedStep>): List<String> {
        fun med(id: String) = steps.firstOrNull { it.id == id }?.takeIf { it.error == null }?.medianMs
        val out = mutableListOf<String>()
        steps.filter { it.error != null }.forEach { out += "${it.label} failed: ${it.error}" }
        val ping = med(PING)
        if (ping != null && ping > 150) out += "The network is slow: every request spends about $ping ms just reaching the server and back (a VPN, remote access or a weak connection). Pages make several requests, so this adds up."
        val search = med(SEARCH)
        if (search != null && search - (ping ?: 0) > 700) out += "The server is slow at searching the library: about ${search - (ping ?: 0)} ms of work on the server for one search. That's the server's disk or processor, or a very large library."
        val parse = med(PARSE)
        if (parse != null && parse > 250) out += "This device is slow at reading the server's answers: $parse ms to read one large response. Lighter requests from Nitrate will help most here."
        val home = med(HOME)
        if (home != null && home > 3000) out += "Home takes ${home / 1000.0} s to load in full."
        steps.firstOrNull { it.id == DOWNLOAD }?.mbps?.let { if (it < 10) out += "Download speed is about ${(it * 10).toLong() / 10.0} Mbps, which limits video quality and makes pictures load slowly." }
        if (out.isEmpty()) out += "Everything is within normal speeds from this device."
        return out
    }
}
