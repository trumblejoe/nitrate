package dev.nitrate.core.playback

import dev.nitrate.core.model.formatClock

/** A stretch that looked like credits and wasn't counted, and why (the server's words). */
data class VetoedStretch(val startMs: Long, val endMs: Long, val why: String)

/**
 * The Companion's Credits check for one title (admins; `GET /Nitrate/Credits/{itemId}/Report`,
 * feature `creditsreport`): what its credits reader decided, for a summary in a few words, and
 * the whole report as the server sent it ([json], one row of features per second) to copy.
 */
data class CreditsReport(
    val runtimeMs: Long,
    /** Where the scanned part starts. */
    val startMs: Long,
    val skips: List<CreditSkip>,
    /** Where the final credits start, when it ends on them. */
    val outroStartMs: Long?,
    /** Viewers marked the final credits wrong: they're not handed out. */
    val outroRejected: Boolean,
    /** Subtitle lines that voted; null when the title has none indexed (pictures decided alone). */
    val dialogueLines: Int?,
    val vetoed: List<VetoedStretch>,
    /** Frames in the report. */
    val rows: Int,
    /** Being read on the server now: ask again in a few minutes. */
    val reading: Boolean,
    /** Why there are no rows, when there aren't. */
    val note: String?,
    val json: String,
) {
    /** In a few words: where the credits start, any scene during or after them, and why. */
    fun summary(): String {
        if (reading) return "Reading the end of the title on the server. This takes a few minutes; this page checks again by itself."
        if (rows == 0) return note ?: "Not read yet."
        val parts = mutableListOf<String>()
        val creditsStart = (skips.map { it.creditsStartMs } + listOfNotNull(outroStartMs)).minOrNull()
        parts += if (creditsStart == null) "No credits found after ${formatClock(startMs)}." else "Credits start at ${formatClock(creditsStart)}."
        skips.forEach { s ->
            val heard = if (s.spoken) " (someone speaks in it)" else ""
            parts += if (s.afterCredits) "A scene after them at ${formatClock(s.sceneStartMs)}$heard." else "A scene during them at ${formatClock(s.sceneStartMs)}$heard."
        }
        if (skips.isNotEmpty() && outroStartMs != null && outroStartMs > skips.maxOf { it.sceneStartMs }) parts += "Final credits from ${formatClock(outroStartMs)}."
        if (outroRejected) parts += "Viewers marked the final credits wrong, so they aren't used."
        vetoed.forEach { parts += "Not credits: ${formatClock(it.startMs)}–${formatClock(it.endMs)}, ${it.why}." }
        parts += when (dialogueLines) {
            null -> "No subtitles indexed: the pictures decided alone."
            0 -> "No subtitle lines in this part."
            else -> "$dialogueLines subtitle lines voted."
        }
        return parts.joinToString(" ")
    }

    companion object {
        /** The Companion's feature for the report. */
        const val FEATURE = "creditsreport"

        /** While the server reads the title, ask again this often. */
        const val RECHECK_MS = 15_000L
    }
}
