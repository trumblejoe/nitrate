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
    KEEP_STEREO("Keep it stereo", "Don't spread stereo here (e.g. the receiver does it itself)", null),
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

    /** Ask about this output? Once per output that can take more than stereo. */
    fun shouldAsk(output: SoundOutput?, known: Map<String, SpeakerLayout>): Boolean =
        output != null && !output.stereoOnly && output.key !in known

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
