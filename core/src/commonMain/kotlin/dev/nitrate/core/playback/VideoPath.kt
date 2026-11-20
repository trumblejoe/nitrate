package dev.nitrate.core.playback

import dev.nitrate.core.model.formatOneDecimal
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The picture's path while playing, for the player's Info panel: what the file has, how this
 * device decodes it, what the display is set to (and takes), how smoothly it's going, and how
 * subtitles are drawn. Each part is null until the player knows it.
 */
data class VideoPath(
    /** The video the decoder gets (from the player, else the server's stream info). */
    val inCodec: String? = null,
    val inWidth: Int? = null,
    val inHeight: Int? = null,
    val inFps: Float? = null,
    /** HDR as the decoder is told: "Dolby Vision", "HDR10", "HLG" or "SDR". */
    val inRange: String? = null,
    /** The file's own range when it differs (e.g. Dolby Vision profile 7 sent on as 8.1 or HDR10). */
    val fileRange: String? = null,
    val profile7: Profile7Playback? = null,
    val software: Boolean = false,
    val tunneled: Boolean = false,
    /** The display's current mode and the HDR kinds it takes ("HDR10", "HLG", "Dolby Vision", "HDR10+"). */
    val displayWidth: Int? = null,
    val displayHeight: Int? = null,
    val displayHz: Float? = null,
    val displayHdr: Set<String>? = null,
    /** Smoothness: frames dropped since this title started, seconds ready ahead, network speed. */
    val droppedFrames: Int = 0,
    val bufferedMs: Long? = null,
    val networkBps: Long? = null,
    val fileBps: Long? = null,
    /** How subtitles are drawn, and the second one (dual subtitles). */
    val subtitles: String? = null,
    val secondSubtitle: String? = null,
    /** What the server converts to, when it does ("H.264 · 1920 px wide · 8 Mbps"). */
    val convertingTo: String? = null,
    /** The TV check's answers say the TV showed an HDR clip (`TvCheck.showedHdr`). */
    val tvShowedHdr: Boolean = false,
    /** An Amazon Fire TV, whose Dynamic Range setting decides what goes out. */
    val fireTv: Boolean = false,
) {
    fun rows(): List<Pair<String, String>> = buildList {
        val shown = listOfNotNull(inCodec?.uppercase(), size(inWidth, inHeight), inFps?.takeIf { it > 0 }?.let { "${fps(it)} fps" }, inRange)
        if (shown.isNotEmpty()) add("Picture" to shown.joinToString(" · "))
        hdrPath()?.let { add("HDR" to it) }
        val decoding = listOfNotNull(if (software) "software (Nitrate's decoder)" else null, "tunneled".takeIf { tunneled })
        if (decoding.isNotEmpty()) add("Decoding" to decoding.joinToString(" · "))
        convertingTo?.let { add("Converted to" to it) }
        val display = listOfNotNull(size(displayWidth, displayHeight), displayHz?.takeIf { it > 0 }?.let { "${fps(it)} Hz" })
        if (display.isNotEmpty()) add("Display" to display.joinToString(" @ ") + (displayHdr?.let { if (it.isEmpty()) " · no HDR" else " · ${it.sorted().joinToString(", ")}" } ?: ""))
        add("Smoothness" to listOfNotNull(
            "$droppedFrames dropped frame${if (droppedFrames == 1) "" else "s"}",
            bufferedMs?.let { "${formatOneDecimal(it / 1000.0)} s ready" },
            networkBps?.takeIf { it > 0 }?.let { "network ${mbps(it)}" + (fileBps?.let { f -> " (video needs ${mbps(f)})" } ?: "") },
        ).joinToString(" · "))
        subtitles?.let { add("Subtitles" to it + (secondSubtitle?.let { s -> " · second: $s" } ?: "")) }
        notes().forEach { add("Note" to it) }
    }

    /** The file's HDR, how it's sent to the decoder, and whether the display shows it as such. */
    fun hdrPath(): String? {
        val range = inRange ?: return null
        if (range == "SDR" && fileRange == null) return null
        val from = fileRange?.takeIf { it != range }?.let { "$it → " }.orEmpty()
        val how = when (profile7) {
            Profile7Playback.AS_PROFILE_8 -> " (converted to profile 8.1 here)"
            Profile7Playback.BASE_LAYER -> " (its base layer)"
            else -> ""
        }
        val shown = when {
            range == "SDR" -> ""
            displayHdr == null -> ""
            range in displayHdr -> ", shown as $range"
            range == "Dolby Vision" && "HDR10" in displayHdr -> ", shown as HDR10 (the display doesn't take Dolby Vision)"
            // Nitrate sends it on as HDR; what the device does with it Android doesn't say.
            else -> ", " + hdrUnreported(displayHdr, tvShowedHdr, fireTv).replaceFirstChar { it.lowercase() }
        }
        return "$from$range$how$shown"
    }

    /** What's worth fixing, plainly. */
    fun notes(): List<String> = buildList {
        val content = inFps?.takeIf { it > 0 }
        val display = displayHz?.takeIf { it > 0 }
        if (content != null && display != null && !cadenceFits(content, display)) {
            add("${fps(content)} fps on a ${fps(display)} Hz display: motion stutters slightly (judder). Refresh rate in the player's More → Settings (or Match frame rate in Settings → Picture & sound) switches the display to suit.")
        }
        if (droppedFrames >= DROPPED_WARN) add("Dropped frames: this device is struggling with this video. A lower quality or another version may play smoothly.")
        val need = fileBps
        if (need != null && networkBps != null && networkBps in 1 until need && (bufferedMs ?: Long.MAX_VALUE) < LOW_BUFFER_MS) {
            add("The network is slower than this video needs: expect pauses to load. A lower quality (or a wired connection) would help.")
        }
    }

    companion object {
        const val DROPPED_WARN = 30

        /**
         * HDR sent on to a display Android says doesn't take it. Nitrate can't see the HDMI signal
         * and Android doesn't say whether the device converts it to SDR (Fire TV's Adaptive) or sends
         * it as HDR anyway (Always HDR), so it says what it knows, the TV check's answer, and the tip.
         */
        fun hdrUnreported(displayHdr: Set<String>, tvShowedHdr: Boolean, fireTv: Boolean): String {
            val reports = if (displayHdr.isEmpty()) "no HDR" else "only ${displayHdr.sorted().joinToString(", ")}"
            val tip = "if the picture looks dim, try setting " + (if (fireTv) "Fire TV to Always HDR" else "this device to always send HDR")
            return if (tvShowedHdr) "Sent as HDR. Android reports $reports, but your TV showed it in the TV check ($tip)."
            else "Sent as HDR. Android reports $reports on this display ($tip)."
        }
        const val LOW_BUFFER_MS = 5_000L

        /**
         * Whether each frame shows for a whole number of refreshes: the display's rate is a whole
         * multiple of the film's (24 on 24, 48 or 120; 25 on 50; 29.97 on 59.94), within 0.05% (24 on 23.976 still slips a frame every 40 s).
         */
        fun cadenceFits(fps: Float, hz: Float): Boolean {
            val ratio = hz / fps
            val whole = ratio.roundToInt()
            return whole >= 1 && abs(ratio - whole) / whole < 0.0005f
        }

        /**
         * What a server conversion makes, from the stream address it hands out (its VideoCodec,
         * MaxWidth/MaxHeight and VideoBitrate). Null when it isn't converting the video.
         */
        fun convertingTo(url: String): String? {
            fun param(name: String) = Regex("[?&]$name=([^&]+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)
            val codec = param("VideoCodec")?.split("%2C", ",", ignoreCase = true)?.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
            if (url.contains("static=true", ignoreCase = true)) return null
            val name = when (codec.lowercase()) {
                "h264" -> "H.264"
                "hevc", "h265" -> "HEVC"
                "av1" -> "AV1"
                "vp9" -> "VP9"
                else -> codec.uppercase()
            }
            val size = param("MaxHeight")?.let { "up to ${it}p" } ?: param("MaxWidth")?.let { "up to $it px wide" }
            val rate = param("VideoBitrate")?.toLongOrNull()?.let { mbps(it) }
            return listOfNotNull(name, size, rate).joinToString(" · ")
        }

        private fun mbps(bps: Long) = "${formatOneDecimal(bps / 1_000_000.0)} Mbps"
    }

    private fun size(w: Int?, h: Int?) = if (w != null && h != null && w > 0 && h > 0) "$w×$h" else null

    private fun fps(v: Float): String {
        val r = (v * 1000).roundToInt() / 1000.0
        return if (r == r.toInt().toDouble()) r.toInt().toString() else r.toString().trimEnd('0')
    }
}
