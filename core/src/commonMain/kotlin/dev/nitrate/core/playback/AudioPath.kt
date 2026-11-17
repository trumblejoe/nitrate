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
    /** Nitrate's sound processing switched on (night mode, dialogue boost). */
    val processing: List<String> = emptyList(),
) {
    /** Rows for the Info panel, after the general ones. */
    fun rows(): List<Pair<String, String>> = buildList {
        val file = listOfNotNull(inCodec?.uppercase(), channels(inChannels), rate(inRateHz), inBits?.let { "$it-bit" })
        if (file.isNotEmpty()) add("Sound in file" to file.joinToString(" · "))
        when (delivery) {
            AudioDelivery.BITSTREAM -> add("Handling" to "Sent untouched for the TV or receiver to decode")
            AudioDelivery.DECODED -> add("Handling" to "Decoded on this device" + if (processing.isNotEmpty()) " · ${processing.joinToString(", ")}" else "")
            null -> Unit
        }
        val out = listOfNotNull(outFormat, channels(outChannels), rate(outRateHz))
        if (out.isNotEmpty()) add("Sound out" to out.joinToString(" · "))
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
}
