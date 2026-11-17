package dev.nitrate.core.playback

import kotlinx.serialization.Serializable

/**
 * The speakers on a sound output. Android can't tell (a receiver reports the most channels it
 * takes, not the speakers it has), so the user says once per output.
 */
@Serializable
enum class SpeakerLayout(val label: String, val detail: String, val upmixChannels: Int?) {
    STEREO("2.0", "Two speakers (or a TV)", null),
    STEREO_SUB("2.1", "Two speakers and a subwoofer", null),
    THREE_ONE("3.1", "Left, centre, right and a subwoofer (many soundbars)", 6),
    FIVE_ONE("5.1", "Front, centre, two surrounds and a subwoofer", 6),
    SEVEN_ONE("7.1", "Front, centre, side and back surrounds and a subwoofer", 8),
    /** More than two speakers, but stereo stays stereo here (e.g. the receiver upmixes it itself). */
    KEEP_STEREO("Keep Stereo (Use Receiver's Upmixers)", "Stereo goes to the receiver as it is, for its own Dolby Surround or DTS Neural:X", null),
}

/** What a sound output is: its kind, the name its device gives, and the most channels it takes. */
data class SoundOutput(
    val kind: OutputKind,
    /** The connected device's own name, e.g. "DENON-AVR" (empty when it gives none). */
    val name: String,
    /** The most PCM channels it takes (null: it doesn't say). */
    val maxChannels: Int?,
) {
    /** How it's remembered: kind and name. */
    val key: String get() = "${kind.name}:${name.trim()}"

    /** Only stereo can come out of it: nothing to upmix to, and nothing to ask. */
    val stereoOnly: Boolean get() = kind.stereoOnly || (maxChannels != null && maxChannels <= 2)

    /** "DENON-AVR (HDMI)". */
    val label: String get() = if (name.isBlank()) kind.label else "${name.trim()} (${kind.label})"
}

enum class OutputKind(val label: String, val stereoOnly: Boolean) {
    HDMI("HDMI", false),
    HDMI_ARC("HDMI ARC", false),
    HDMI_EARC("HDMI eARC", false),
    USB("USB", false),
    SPEAKER("built-in speakers", true),
    HEADPHONES("headphones", true),
    BLUETOOTH("Bluetooth", true),
    OTHER("sound output", false),
}

/** Speaker layouts per output, and whether (and how) stereo is upmixed. */
object Speakers {
    /** The layout in use on [output]: stereo when it can only take stereo, else what the user said (null: not asked yet). */
    fun layout(output: SoundOutput?, known: Map<String, SpeakerLayout>): SpeakerLayout? = when {
        output == null -> null
        output.stereoOnly -> SpeakerLayout.STEREO
        else -> known[output.key]
    }

    /** "Later" holds the question back this long (remembered, so new episodes and restarts don't bring it back sooner). */
    const val LATER_MS = 24 * 60 * 60_000L

    /**
     * Ask about this output? Once per output that can take more than stereo, unless "Later" was
     * chosen for it in the last day ([later]: output key → when it may be asked again).
     */
    fun shouldAsk(output: SoundOutput?, known: Map<String, SpeakerLayout>, later: Map<String, Long> = emptyMap(), nowMs: Long = 0): Boolean =
        output != null && !output.stereoOnly && output.key !in known && (later[output.key] ?: 0) <= nowMs

    /** [later] with [output] held back for a day from [nowMs] (expired ones dropped). */
    fun askLater(later: Map<String, Long>, output: SoundOutput, nowMs: Long): Map<String, Long> =
        later.filterValues { it > nowMs } + (output.key to nowMs + LATER_MS)

    /**
     * How many channels to upmix stereo to, or null to leave it stereo: upmixing is on, the sound
     * is stereo, it's a film or episode, and the output's speakers have a centre or surrounds.
     */
    fun upmixTo(on: Boolean, inChannels: Int, isVideo: Boolean, output: SoundOutput?, known: Map<String, SpeakerLayout>): Int? {
        if (!on || inChannels != 2 || !isVideo) return null
        val layout = layout(output, known) ?: return null
        val channels = layout.upmixChannels ?: return null
        return channels.takeIf { output?.maxChannels == null || it <= output.maxChannels }
    }
}
