package dev.nitrate.core.playback

/** Where this device is, for the quality limit: at home (on the server's network), away, or on mobile data. */
enum class NetworkPlace(val label: String) {
    HOME("At home"),
    AWAY("Away"),
    MOBILE_DATA("On mobile data"),
}

/**
 * The quality limit for one playback: where the device is, the limit (bps) and whether it came from
 * a speed test ([auto], [measuredBps]); [lowered] when it was stepped down because it kept buffering.
 */
data class QualityDecision(
    val place: NetworkPlace,
    val limitBps: Int,
    val auto: Boolean = false,
    val measuredBps: Long? = null,
    val lowered: Boolean = false,
) {
    /** "Away · measured 18 Mbps · limit Auto → 12 Mbps", for the player's Info and reports. */
    fun describe(): String = listOfNotNull(
        place.label,
        measuredBps?.let { "measured ${formatBitrate(it.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())}" },
        "limit " + (if (auto) "Auto → " else "") + formatBitrate(limitBps) + (if (lowered) " (lowered: it kept buffering)" else ""),
    ).joinToString(" · ")
}

/**
 * Quality for travelling: at home every file plays as the home limit says (no limit by default);
 * away (not on the server's network, a VPN included) and on mobile data, a set limit or Auto, which
 * asks for [SHARE] of what a quick speed test to the server measured. Conversions are a single fixed
 * quality, so when playback keeps buffering ([StallCounter]) the quality steps down ([stepDown]),
 * offered with one press or done by itself ([PlaybackSettings.autoLowerQuality]); it goes back up only
 * with the next title, when Auto measures again.
 */
object TravelQuality {
    /** Of the measured speed, what Auto asks for: room for the network's dips. */
    const val SHARE = 0.7
    const val MAX_AUTO = 120_000_000
    /** Lower than this is no longer worth watching: the floor for Auto and for stepping down. */
    const val MIN = 1_500_000
    /** Auto with no measurement (the test failed): a cautious limit that plays on most connections. */
    const val UNMEASURED = 8_000_000

    /**
     * Where the device is: on mobile data first (phones), else away when the server says the device
     * isn't on its network ([inServerNetwork] false), else at home (also when the server didn't say).
     */
    fun place(inServerNetwork: Boolean?, mobileData: Boolean): NetworkPlace = when {
        mobileData -> NetworkPlace.MOBILE_DATA
        inServerNetwork == false -> NetworkPlace.AWAY
        else -> NetworkPlace.HOME
    }

    /** Whether [place] needs a speed test with [settings] (its limit is Auto). */
    fun needsMeasuring(settings: PlaybackSettings, place: NetworkPlace): Boolean = when (place) {
        NetworkPlace.HOME -> false
        NetworkPlace.AWAY -> settings.awayMaxBitrate == PlaybackSettings.AUTO_BITRATE
        NetworkPlace.MOBILE_DATA -> settings.mobileMaxBitrate == PlaybackSettings.AUTO_BITRATE
    }

    /** The limit for [place] with [settings], Auto worked out from [measuredBps]. */
    fun decide(settings: PlaybackSettings, place: NetworkPlace, measuredBps: Long?): QualityDecision {
        val set = when (place) {
            NetworkPlace.HOME -> settings.maxBitrate
            NetworkPlace.AWAY -> settings.awayMaxBitrate
            NetworkPlace.MOBILE_DATA -> settings.mobileMaxBitrate
        }
        return if (set == PlaybackSettings.AUTO_BITRATE) QualityDecision(place, autoLimit(measuredBps), auto = true, measuredBps = measuredBps)
        else QualityDecision(place, set)
    }

    /** Auto's limit: [SHARE] of [measuredBps], between [MIN] and [MAX_AUTO]; [UNMEASURED] without a measurement. */
    fun autoLimit(measuredBps: Long?): Int {
        val bps = measuredBps ?: return UNMEASURED
        return (bps * SHARE).toLong().coerceIn(MIN.toLong(), MAX_AUTO.toLong()).toInt()
    }

    /** What [plan] streams at: the rate the server converts to (its URL's VideoBitrate), else the file's own. */
    fun streamBps(plan: dev.nitrate.core.model.PlaybackPlan): Int? =
        Regex("[?&]VideoBitrate=(\\d+)", RegexOption.IGNORE_CASE).find(plan.url)?.groupValues?.get(1)?.toLongOrNull()
            ?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()?.takeIf { it > 0 }
            ?: plan.source.bitrate

    /**
     * One step down from [limitBps] for a file of [fileBitrate]: half of what's playing (the limit, or
     * the file's own rate when that's lower), never under [MIN]; null when already there.
     */
    fun stepDown(limitBps: Int, fileBitrate: Int?): Int? {
        val playing = minOf(limitBps, fileBitrate ?: limitBps)
        val next = (playing / 2).coerceAtLeast(MIN)
        return next.takeIf { it < playing }
    }
}

/**
 * Buffering while playing (not at the start or just after a seek, which the caller leaves out):
 * [COUNT] stalls within [WINDOW_MS] and it's time to lower the quality. Feed it from one thread.
 */
class StallCounter {
    private val stalls = ArrayDeque<Long>()

    /** A stall at [atMs]; true when it's the [COUNT]th within [WINDOW_MS] (then it starts counting afresh). */
    fun stall(atMs: Long): Boolean {
        stalls.addLast(atMs)
        while (stalls.isNotEmpty() && atMs - stalls.first() > WINDOW_MS) stalls.removeFirst()
        if (stalls.size < COUNT) return false
        stalls.clear()
        return true
    }

    fun reset() = stalls.clear()

    companion object {
        const val COUNT = 2
        const val WINDOW_MS = 60_000L
    }
}

/**
 * Buffering as a player reports it (mpv's paused-for-cache, Apple's player's stalls), turned into
 * stalls that count: not before it first played, nor within [SEEK_GRACE_MS] of a seek ([seeked]).
 */
class BufferingWatch {
    private val counter = StallCounter()
    private var started = false
    private var buffering = false
    private var seekedAt = Long.MIN_VALUE / 2

    /** A new stream (a title, a track change, a lower quality): its start doesn't count. */
    fun restart() {
        started = false
        buffering = false
        counter.reset()
    }

    fun seeked(atMs: Long) {
        seekedAt = atMs
    }

    /** One look at the player; true when this is the stall that makes it time to lower the quality. */
    fun update(buffering: Boolean, playing: Boolean, atMs: Long): Boolean {
        if (!buffering && playing) started = true
        val began = buffering && !this.buffering
        this.buffering = buffering
        return began && started && atMs - seekedAt > SEEK_GRACE_MS && counter.stall(atMs)
    }

    /** A stall reported as it happens (Apple's player's "stalled" notification). */
    fun stalled(atMs: Long): Boolean = started && atMs - seekedAt > SEEK_GRACE_MS && counter.stall(atMs)

    companion object {
        const val SEEK_GRACE_MS = 3_000L
    }
}
