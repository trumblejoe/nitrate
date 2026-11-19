package dev.nitrate.core.playback

import dev.nitrate.core.model.MediaSource
import dev.nitrate.core.model.StreamKind
import kotlinx.serialization.Serializable

/** A short real playback of one title, through the normal path, and the trail it left. */
@Serializable
data class TestPlayResult(
    val itemId: String,
    val name: String,
    val atMs: Long,
    val trail: PlaybackTrail?,
    val droppedFrames: Int = 0,
    val playedMs: Long = 0,
    /** Why it didn't play, when it didn't. */
    val error: String? = null,
    /** What the user saw at the TV and receiver ([TestPlay.questions] ids), which the app can't see. */
    val answers: Map<String, Answer> = emptyMap(),
) {
    val ok: Boolean get() = error == null && trail != null && trail.differences.isEmpty() && droppedFrames <= TestPlay.DROPPED_OK

    /** One line: "Played as predicted", "2 things differed", "Didn't play: …". */
    val summary: String
        get() = when {
            error != null -> "Didn't play: $error"
            trail == null -> "No result"
            else -> listOfNotNull(
                trail.differences.size.let { if (it == 0) "Played as predicted" else "$it thing${if (it == 1) "" else "s"} not as predicted" },
                droppedFrames.takeIf { it > TestPlay.DROPPED_OK }?.let { "$it dropped frames" },
            ).joinToString(" · ")
        }
}

/** An answer about what the TV or receiver showed. */
@Serializable
enum class Answer(val label: String) { YES("Yes"), NO("No"), UNSURE("Not sure") }

/** A question for the user after a test play: what the app can't see on its own. */
data class TestQuestion(val id: String, val text: String)

/** Something to do about a test play: a setting to change (with [change]), or advice to follow. */
data class TestAdvice(val text: String, val change: SettingsChange? = null, val changeLabel: String? = null)

object TestPlay {
    const val TV = "tv"
    const val RECEIVER = "receiver"

    private val HDR_WORDS = listOf("HDR10", "HDR10+", "HLG", "Dolby Vision")

    /** The file's HDR as the trail names it ("Dolby Vision", "HDR10"…), or null for SDR. */
    fun fileHdr(result: TestPlayResult): String? {
        val file = result.trail?.trails?.firstOrNull { it.title == "Picture" }?.steps?.firstOrNull()?.what ?: return null
        return HDR_WORDS.sortedByDescending { it.length }.firstOrNull { it in file }
    }

    /** What to ask after [result]: whether the TV switched to HDR (for an HDR file), and what the receiver showed. */
    fun questions(result: TestPlayResult): List<TestQuestion> = buildList {
        val trail = result.trail ?: return@buildList
        fileHdr(result)?.let { hdr ->
            add(TestQuestion(TV, "Did the TV switch to HDR while it played? (an HDR or Dolby Vision badge, or its HDR picture mode; the file is $hdr)"))
        }
        val receiver = trail.trails.firstOrNull { it.title == "Sound" }?.steps?.firstOrNull { it.step == "At the receiver" }?.what?.removePrefix("Expect ")?.trim()
        val out = trail.trails.firstOrNull { it.title == "Sound" }?.steps?.firstOrNull { it.step == "Went out" }?.what
        if (receiver != null && out != null && !out.contains("stereo") && out != "Not yet") add(TestQuestion(RECEIVER, "Did the receiver show $receiver?"))
    }

    /**
     * What to do about [result]: settings that would change what happened (from [settings], the
     * device's [caps] and the user's answers), and advice for what no setting here can fix.
     */
    fun advice(result: TestPlayResult, settings: PlaybackSettings, caps: DeviceCapabilities): List<TestAdvice> = buildList {
        val hdr = fileHdr(result)
        val formats = when (hdr) {
            null -> emptyList()
            "Dolby Vision" -> listOf("DOVI", "HDR10")
            "HDR10+" -> listOf("HDR10Plus", "HDR10")
            else -> listOf(hdr)
        }
        val tv = result.answers[TV]
        // The TV showed HDR though Android doesn't report it: those formats are offered Always (below) instead.
        val sawHdr = hdr != null && tv == Answer.YES
        formats.forEach { f ->
            val id = PlaybackSettings.hdrId(f)
            val name = when (f) { "DOVI" -> "Dolby Vision"; "HDR10Plus" -> "HDR10+"; else -> f }
            when {
                sawHdr && f !in caps.hdr -> Unit
                settings.overrides[id] == Override.NEVER ->
                    add(TestAdvice("$name is set to Never on this device, so the server tone-maps it.", SettingsChange.ClearOverride(id), "Set $name back to Automatic"))
                settings.overrides[id] == null && settings.learnedFailures[id]?.blocksFormat == true ->
                    add(TestAdvice("$name was turned off here after failures.", SettingsChange.ForgetFailure(id), "Try $name again"))
            }
        }
        if (settings.convertsTitle(result.itemId)) add(TestAdvice("This title failed here before, so it's always converted.", SettingsChange.ForgetTitle(result.itemId), "Try it as it is again"))
        if (sawHdr) {
            // The TV shows HDR though Android doesn't say so: let the server send it as it is.
            formats.filter { it !in caps.hdr && settings.overrides[PlaybackSettings.hdrId(it)] != Override.ALWAYS }.forEach { f ->
                val name = when (f) { "DOVI" -> "Dolby Vision"; "HDR10Plus" -> "HDR10+"; else -> f }
                add(TestAdvice("Your TV showed HDR, but Android doesn't report $name on this display.", SettingsChange.ForceAllow(PlaybackSettings.hdrId(f)), "Set $name to Always"))
            }
        }
        if (hdr != null && tv == Answer.NO) add(TestAdvice(
            if (caps.hdr.isEmpty()) "The TV didn't switch to HDR, and Android reports no HDR here: the HDMI chain isn't passing it. Check the receiver's 4K signal setting (Enhanced on Denon), the TV's enhanced/deep-colour mode for that input, and an 18 Gbps cable."
            else "The TV didn't switch to HDR though the display reports it: check the TV's HDR setting for that input, and the device's own display settings (Dynamic range: Adaptive).",
        ))
        if (result.answers[RECEIVER] == Answer.NO) add(TestAdvice(
            "The receiver didn't show what was sent. Check its input's decode mode is Auto, and the device's surround setting (Fire TV: Display & Sounds → Audio → Surround Sound: Best available).",
        ))
        val trail = result.trail
        val server = trail?.trails?.firstOrNull { it.title == "Picture" }?.steps?.firstOrNull { it.step == "Server reported" }
        if (server?.differs != null) add(TestAdvice("The server converts too slowly to keep up: use hardware acceleration (an admin can see it in Server-side settings → Converting on the server)."))
        val smooth = trail?.trails?.firstOrNull { it.title == "Smoothness" }?.steps.orEmpty()
        if (smooth.any { it.step == "Stalls" && it.differs != null } && smooth.any { it.step == "Network" && it.why != null }) add(TestAdvice(
            "The network is slower than this file needs, so it stops to buffer: a wired connection or 5 GHz Wi-Fi helps. A bitrate limit would make the server convert it smaller instead.",
        ))
        if (smooth.any { it.step == "Seek" && it.differs?.contains("surround") == true }) add(TestAdvice(
            "Surround went from untouched to decoded after jumping. Send the playback log (player Info) so it can be looked into.",
        ))
    }

    /** How long a test plays. */
    const val PLAY_MS = 30_000L

    /** Dropped frames a test may have and still count as smooth (a few at the start are normal). */
    const val DROPPED_OK = 10

    /** After this long it jumps ahead ([SEEK_TO_MS] from the start) to check seeking, then plays to [END_MS]. */
    const val SEEK_AT_MS = 10_000L
    const val SEEK_TO_MS = 70_000L
    const val END_MS = 90_000L

    /** Where a test starts: a few minutes in, past studio logos, leaving room for the jump. */
    fun startAt(runtimeMs: Long?): Long = runtimeMs?.takeIf { it > 0 }?.let { (minOf(300_000L, it / 2 - END_MS / 2)).coerceAtLeast(0) } ?: 0L

    /** Whether a title is long enough for the jump. */
    fun seeks(runtimeMs: Long?): Boolean = runtimeMs == null || runtimeMs > END_MS + 10_000

    /**
     * What makes two files play alike here: container, video codec, size class, bit depth, HDR,
     * the default sound's codec and channels, and picture subtitles (which may be burned in).
     */
    fun signature(source: MediaSource): String {
        val v = source.streams.firstOrNull { it.kind == StreamKind.VIDEO }
        val a = PlaybackPredictor.audio(source)
        val size = v?.height?.let { h -> when { h >= 2000 -> "4K"; h >= 1000 -> "1080"; h >= 700 -> "720"; else -> "SD" } }
        val range = v?.rangeType?.let { r -> when { r.startsWith("DOVIWithEL") -> "DV7"; r.startsWith("DOVI") -> "DV"; else -> r } }
        val pictureSubs = source.streams.any { it.kind == StreamKind.SUBTITLE && it.codec?.lowercase() in setOf("pgssub", "pgs", "dvdsub", "dvbsub", "vobsub") }
        return listOf(
            source.container?.lowercase()?.substringBefore(','), v?.codec?.lowercase(), size, v?.bitDepth?.toString(), range,
            if (v?.isInterlaced == true) "i" else null, a?.codec?.lowercase(), a?.channels?.toString(), if (pictureSubs) "picsubs" else null,
        ).joinToString("|") { it ?: "-" }
    }

    /** One title for each kind of file, so a few test plays cover the whole library. */
    fun sample(entries: List<OverviewEntry>): List<OverviewEntry> =
        entries.filter { it.source != null }.groupBy { signature(it.source!!) }.values.map { group -> group.first() }
            .sortedWith(compareBy({ it.prediction.delivery.ordinal }, { it.name }))

    /** A short name for a kind of file, for the sample list: "MKV · HEVC 4K 10-bit Dolby Vision · Dolby TrueHD 7.1". */
    fun describe(source: MediaSource): String {
        val v = source.streams.firstOrNull { it.kind == StreamKind.VIDEO }
        val a = PlaybackPredictor.audio(source)
        return listOfNotNull(
            source.container?.substringBefore(',')?.uppercase(),
            v?.let { PlaybackPredictor.describe(it.copy(frameRate = null)) },
            a?.let { listOfNotNull(codecLabel(it.codec), PlaybackPredictor.channels(it.channels)).joinToString(" ") },
        ).joinToString(" · ")
    }
}
