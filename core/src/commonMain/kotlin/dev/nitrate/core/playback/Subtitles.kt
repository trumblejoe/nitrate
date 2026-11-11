package dev.nitrate.core.playback

import kotlinx.serialization.Serializable

/** How subtitles are drawn, per device. Colours are ARGB. */
@Serializable
data class SubtitleStyle(
    /** 1.0 is the player's default size. */
    val scale: Float = 1f,
    val textColor: Long = 0xFFFFFFFF,
    val background: SubtitleBackground = SubtitleBackground.NONE,
    val edge: SubtitleEdge = SubtitleEdge.OUTLINE,
    /** Distance from the bottom, as a fraction of the picture height. */
    val bottomMargin: Float = 0.08f,
    /** Use colours and positions from the subtitle file (ASS/SSA styling) instead of these. */
    val useFileStyles: Boolean = false,
) {
    companion object {
        val COLORS = listOf(0xFFFFFFFF to "White", 0xFFFFE680 to "Yellow", 0xFF9EE7FF to "Cyan", 0xFFB7F5B0 to "Green")
        val SCALES = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
    }
}

@Serializable
enum class SubtitleBackground(val label: String) { NONE("None"), SHADED("Shaded box"), SOLID("Black box") }

@Serializable
enum class SubtitleEdge(val label: String) { NONE("None"), OUTLINE("Outline"), SHADOW("Drop shadow") }

/** A subtitle the server can download from a provider (OpenSubtitles...). */
data class RemoteSubtitle(
    val id: String,
    val name: String,
    val language: String?,
    val provider: String?,
    val format: String?,
    val downloads: Int?,
    val rating: Float?,
    val hashMatch: Boolean,
    val forced: Boolean,
    val hearingImpaired: Boolean,
    val machineTranslated: Boolean,
)

/** Moves every timestamp in an SRT or WebVTT file by [offsetMs] (negative shows them earlier). */
object SubtitleShift {
    private val srtTime = Regex("""(\d{1,2}):(\d{2}):(\d{2})[,.](\d{3})""")

    fun shift(text: String, offsetMs: Long): String {
        if (offsetMs == 0L) return text
        val vtt = text.trimStart().startsWith("WEBVTT")
        return text.lines().joinToString("\n") { line ->
            if ("-->" !in line) return@joinToString line
            srtTime.replace(line) { m ->
                val (h, mi, s, ms) = m.destructured
                val total = (h.toLong() * 3_600_000 + mi.toLong() * 60_000 + s.toLong() * 1000 + ms.toLong() + offsetMs).coerceAtLeast(0)
                format(total, if (vtt) '.' else ',')
            }
        }
    }

    private fun format(ms: Long, sep: Char): String {
        fun two(v: Long) = v.toString().padStart(2, '0')
        return "${two(ms / 3_600_000)}:${two(ms / 60_000 % 60)}:${two(ms / 1000 % 60)}$sep${(ms % 1000).toString().padStart(3, '0')}"
    }
}
