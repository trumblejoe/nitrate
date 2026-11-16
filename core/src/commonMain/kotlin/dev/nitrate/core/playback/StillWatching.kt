package dev.nitrate.core.playback

/**
 * "Still watching?": notices when episodes keep rolling on with nobody touching anything, so the
 * player can stop and ask instead of playing on into the night, and puts things back the way they
 * were at the last press ([rewind]): that episode's resume point where it was then, and the
 * episodes that played on by themselves since unwatched again.
 *
 * Lives as long as the app (each episode opens a new player), fed by the players.
 */
class StillWatching(private val now: () -> Long) {
    /** Where the user last did something: the title, how far in, and when. */
    data class Spot(val itemId: String, val positionMs: Long, val atMs: Long, val wasPlayed: Boolean)

    /** What to undo: [back] is where to resume, [unwatch] the episodes that played on by themselves. */
    data class Rewind(val back: Spot, val unwatch: List<String>)

    private var spot: Spot? = null
    private val rolled = mutableListOf<Pair<String, Boolean>>()
    private var expected: String? = null

    /** The user did something while [itemId] was at [positionMs]. */
    fun input(itemId: String, positionMs: Long, wasPlayed: Boolean) {
        val keep = spot?.takeIf { it.itemId == itemId }?.wasPlayed ?: wasPlayed
        spot = Spot(itemId, positionMs, now(), keep)
        rolled.clear()
        expected = null
    }

    /** The next episode is about to start by itself (Up Next's countdown, or the end of one). */
    fun rollingOn(itemId: String) {
        expected = itemId
    }

    /** A player started [itemId]: by itself after [rollingOn], else it was asked for (an input). */
    fun started(itemId: String, positionMs: Long, wasPlayed: Boolean) {
        if (itemId == expected && spot != null) {
            rolled += itemId to wasPlayed
            expected = null
        } else {
            input(itemId, positionMs, wasPlayed)
        }
    }

    /** Before an episode rolls on: ask first? After [ROLL_ONS] in a row, or [IDLE_MS] untouched. */
    fun shouldAsk(): Boolean {
        val s = spot ?: return false
        return rolled.size >= ROLL_ONS || (rolled.isNotEmpty() && now() - s.atMs >= IDLE_MS)
    }

    /** What to put back, when anything has played on by itself since the last press. */
    fun rewind(): Rewind? {
        val s = spot ?: return null
        if (rolled.isEmpty()) return null
        return Rewind(s.copy(positionMs = (s.positionMs - BACK_MS).coerceAtLeast(0)), rolled.filterNot { it.second }.map { it.first })
    }

    /** After answering (or rewinding): start counting again. */
    fun clear() {
        spot = null
        rolled.clear()
        expected = null
    }

    companion object {
        /** Ask before the third episode in a row would start by itself. */
        const val ROLL_ONS = 2
        const val IDLE_MS = 90 * 60_000L
        /** Resume a little before the last press, since that's roughly where attention went. */
        const val BACK_MS = 30_000L
        /** No answer for this long: they've dozed off. Rewind and stop. */
        const val PROMPT_MS = 5 * 60_000L
    }
}
