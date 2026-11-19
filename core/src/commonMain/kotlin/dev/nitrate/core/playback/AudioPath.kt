package dev.nitrate.core.playback

import dev.nitrate.core.model.formatOneDecimal

/** How the sound leaves the device: as plain decoded PCM, or untouched for a receiver to decode. */
enum class AudioDelivery { DECODED, BITSTREAM }

/**
 * The sound's path while playing, for the player's Info panel: what the file has, how this device
 * handles it, and what it sends to the TV or receiver. Each part is null until the player knows it.
 */
data class AudioPath(
    /** The file's audio (from the player, else the server's stream info). */
    val inCodec: String? = null,
    val inChannels: Int? = null,
    val inRateHz: Int? = null,
    val inBits: Int? = null,
    /** What goes to Android's audio output. */
    val delivery: AudioDelivery? = null,
    /** "PCM 16-bit", "PCM float", "Dolby Digital Plus"... */
    val outFormat: String? = null,
    val outChannels: Int? = null,
    val outRateHz: Int? = null,
    /** Android's own mixer rate: decoded sound is usually converted to this before it leaves. */
    val mixerRateHz: Int? = null,
    /** Where it goes ("DENON-AVR (HDMI) · speakers 5.1"). */
    val output: String? = null,
    /** Stereo spread over the speakers here ("5.1"), or null. */
    val upmixedTo: String? = null,
    /** Nitrate's sound processing switched on (night mode, dialogue boost). */
    val processing: List<String> = emptyList(),
    /** For surround formats a receiver could take untouched but that were decoded here: why. */
    val decodedWhy: String? = null,
) {
    /** Rows for the Info panel, after the general ones. */
    fun rows(): List<Pair<String, String>> = buildList {
        val file = listOfNotNull(inCodec?.uppercase(), channels(inChannels), rate(inRateHz), inBits?.let { "$it-bit" })
        if (file.isNotEmpty()) add("Sound in file" to file.joinToString(" · "))
        when (delivery) {
            AudioDelivery.BITSTREAM -> add("Handling" to "Sent untouched for the TV or receiver to decode")
            AudioDelivery.DECODED -> add("Handling" to listOfNotNull("Decoded on this device", upmixedTo?.let { "upmixed stereo → $it" }).plus(processing).joinToString(" · "))
            null -> Unit
        }
        val out = listOfNotNull(outFormat, channels(outChannels), rate(outRateHz))
        if (out.isNotEmpty()) add("Sound out" to out.joinToString(" · "))
        output?.let { add("Output" to it) }
        decodedWhy?.takeIf { delivery == AudioDelivery.DECODED }?.let { add("Why decoded" to it) }
        if (delivery == AudioDelivery.DECODED && mixerRateHz != null && outRateHz != null && mixerRateHz != outRateHz) {
            add("Android mixer" to "${rate(mixerRateHz)} (converted to this unless the device sends audio directly)")
        }
        hint()?.let { add("Note" to it) }
    }

    /** What a receiver will make of it, when that's worth saying. */
    fun hint(): String? {
        val sent = if (delivery == AudioDelivery.DECODED) (mixerRateHz?.takeIf { outRateHz == null } ?: outRateHz) else null
        return when {
            delivery == AudioDelivery.DECODED && outChannels == 2 && (sent ?: 0) > 48_000 ->
                "Stereo above 48 kHz: many receivers (Denon, Marantz) won't upmix it to surround, offering only Stereo or Direct."
            delivery == AudioDelivery.DECODED && outChannels == 2 ->
                "Stereo PCM: a receiver can upmix it to all your speakers (e.g. Dolby Surround or DTS Neural:X) if its sound mode isn't Direct or Stereo."
            else -> null
        }
    }

    private fun channels(n: Int?) = when (n) {
        null, 0 -> null
        1 -> "mono"
        2 -> "stereo"
        6 -> "5.1"
        8 -> "7.1"
        else -> "$n ch"
    }

    private fun rate(hz: Int?) = hz?.takeIf { it > 0 }?.let { if (it % 1000 == 0) "${it / 1000} kHz" else "${formatOneDecimal(it / 1000.0)} kHz" }

    companion object {
        /** Formats a TV or receiver can take untouched (bitstream), by the server's codec names. */
        val BITSTREAM_CODECS = setOf("ac3", "eac3", "dts", "truehd", "mlp")

        /**
         * Why [codec] sound was decoded here instead of sent untouched: Nitrate's own processing (which
         * needs decoded sound), a playback speed other than 1×, or Android saying the output doesn't
         * take it ([outputAccepts]; null when unknown). Null for formats that are always decoded.
         */
        fun decodeReason(codec: String?, processing: List<String>, speed: Float, outputAccepts: Boolean?): String? {
            val c = codec?.lowercase() ?: return null
            if (c !in BITSTREAM_CODECS) return null
            val name = when (c) { "ac3" -> "Dolby Digital"; "eac3" -> "Dolby Digital Plus"; "truehd", "mlp" -> "Dolby TrueHD"; else -> "DTS" }
            return when {
                processing.isNotEmpty() -> "${processing.joinToString(" and ").replaceFirstChar { it.uppercase() }} is on, and works only on decoded sound; Sound: Normal sends $name untouched."
                speed != 1f -> "Playing at ${formatOneDecimal(speed.toDouble())}×: sound at another speed has to be decoded."
                outputAccepts == false -> "Android says this output won't take $name untouched: check the device's own surround setting (Fire TV: Display & Sounds → Audio → Surround Sound, Best Available or Dolby Digital Plus) and that the TV or receiver supports it (over ARC some TVs pass only Dolby Digital)."
                outputAccepts == true -> "The output takes $name untouched, but the player decoded it; Send log would show why."
                else -> null
            }
        }
    }
}
