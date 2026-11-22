package dev.nitrate.core.diagnostics

import kotlinx.serialization.Serializable

/**
 * One thing wrong with how a file was made that can put sound and picture out of step, as the
 * Companion plugin's "Check files' timing" found it: [kind] is "start-offset", "audio-gap",
 * "video-gap", "audio-backwards", "video-backwards" or "variable-frame-rate".
 */
@Serializable
data class TimingIssue(val kind: String, val atMs: Long? = null, val amountMs: Long? = null, val text: String)

/** A title's file and what the server found wrong with its timing. */
data class FileTiming(val itemId: String, val issues: List<TimingIssue>)

object FileTimings {
    /** The Companion feature that checks files' timing. */
    const val FEATURE = "timing"

    /** A short label for an issue, for lists ("Sound starts 0.42 s late"). */
    fun label(issue: TimingIssue): String {
        val amount = issue.amountMs?.let { kotlin.math.abs(it) / 1000.0 }?.let { dev.nitrate.core.model.formatOneDecimal(it) + " s" }
        return when (issue.kind) {
            "start-offset" -> "Sound starts ${amount ?: "out of step"} ${if ((issue.amountMs ?: 0) > 0) "late" else "early"}"
            "audio-gap" -> "Gap in the sound" + (amount?.let { " ($it)" } ?: "")
            "video-gap" -> "Gap in the picture" + (amount?.let { " ($it)" } ?: "")
            "audio-backwards", "video-backwards" -> "Faulty timestamps"
            "variable-frame-rate" -> "Variable frame rate"
            else -> issue.kind
        }
    }
}
