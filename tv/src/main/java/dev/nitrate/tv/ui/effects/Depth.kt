package dev.nitrate.tv.ui.effects

/**
 * How far back a part of a scene is. A page draws its theme in two passes: the back and middle
 * behind the rows (over the title's own picture), the front over everything, under the menu.
 * Scenes mark their parts with [back], [mid] and [front]; nothing in a scene is drawn outside them.
 */
enum class Depth { BACK, MID, FRONT }

/** The depths being drawn on this thread now; null draws everything (previews, tests, pre-rolls). */
internal object Pass {
    private val now = ThreadLocal<Set<Depth>?>()
    fun on(d: Depth): Boolean = now.get()?.contains(d) ?: true
    fun <T> with(depths: Set<Depth>?, block: () -> T): T {
        val before = now.get()
        now.set(depths)
        try { return block() } finally { now.set(before) }
    }
}

/** Sky, far scenery, big lights: right behind the cards, over the title's picture. */
internal inline fun back(block: () -> Unit) { if (Pass.on(Depth.BACK)) block() }

/** The scene's subject (trees, flowers, buildings, animals, ground), still behind the cards. */
internal inline fun mid(block: () -> Unit) { if (Pass.on(Depth.MID)) block() }

/** A few light things passing in front (petals, snow, sparks, a soft glow near the lens), over the cards. */
internal inline fun front(block: () -> Unit) { if (Pass.on(Depth.FRONT)) block() }

/** Behind the rows: back and middle. */
val BEHIND: Set<Depth> = setOf(Depth.BACK, Depth.MID)
/** Over the page. */
val INFRONT: Set<Depth> = setOf(Depth.FRONT)
