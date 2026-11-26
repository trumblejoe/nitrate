package dev.nitrate.core.pages

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.roundToInt

/** What Collage's hairlines join. Saved by name in display settings: never rename one. */
@Serializable
enum class CollageLines(val label: String, val note: String) {
    RANDOM("Random", "Lines between neighbours, just for the look"),
    GENRE("Shared genre", "Titles with a genre in common, the genre on the line"),
    COLLECTION("Shared collection", "Films in the same collection, its name on the line"),
    DIRECTOR("Shared director", "Titles by the same director, their name on the line"),
    WRITER("Shared writer", "Titles by the same writer, their name on the line"),
}

/** One picture on the board, in column units (a column is 1 wide); [wide] ones show artwork, the rest posters. */
data class CollageTile(val index: Int, val x: Float, val y: Float, val w: Float, val h: Float, val wide: Boolean) {
    val cx: Float get() = x + w / 2
    val cy: Float get() = y + h / 2
}

/** The board: every tile, and how wide and tall it is before it repeats (it wraps round both ways). */
data class CollageBoard(val tiles: List<CollageTile>, val width: Float, val height: Float)

/** A hairline between two tiles ([label] says what they share; null for Random). */
data class CollageLink(val from: Int, val to: Int, val label: String?)

/** Where a move lands: the tile, and its centre as seen from where the move began (across the wrap if need be). */
data class CollageStep(val index: Int, val x: Float, val y: Float)

enum class CollageMove { LEFT, RIGHT, UP, DOWN }

/**
 * Collage: an endless board of posters and wide artwork in mixed sizes, packed into columns (each new
 * title goes where the board is lowest, so titles loaded later only add to the bottom), repeating in
 * both directions so there's no edge; the D-pad hops to the nearest tile that way.
 */
object Collage {
    const val COLUMNS = 7
    const val GAP = 0.42f

    /** The board for [count] titles. The same count always gives the same board. */
    fun layout(count: Int, columns: Int = COLUMNS): CollageBoard {
        val sky = FloatArray(columns) { rnd(it, 7) * 0.9f }
        val tiles = ArrayList<CollageTile>(count)
        for (i in 0 until count) {
            val r = rnd(i, 1)
            // Mostly posters; some wide artwork, a few big posters.
            val (span, h, wide) = when {
                r < 0.26f -> Triple(2, (2 + GAP) * 9f / 16f, true)
                r < 0.34f -> Triple(2, (2 + GAP) * 1.5f, false)
                else -> Triple(1, 1.5f, false)
            }
            var best = 0
            var bestTop = Float.MAX_VALUE
            for (c in 0..columns - span) {
                var top = 0f
                for (k in c until c + span) top = maxOf(top, sky[k])
                if (top < bestTop - 0.001f) { bestTop = top; best = c }
            }
            val x = best * (1 + GAP)
            val w = span + (span - 1) * GAP
            tiles += CollageTile(i, x, bestTop, w, h, wide)
            for (k in best until best + span) sky[k] = bestTop + h + GAP
        }
        val height = (sky.maxOrNull() ?: 0f).coerceAtLeast(1.5f + GAP)
        return CollageBoard(tiles, columns * (1 + GAP), height)
    }

    /**
     * The tile to go to from [from] moving [move]: the nearest in that direction (within a cone, nearer
     * the line preferred), looking across the wrap, so moving off one edge comes in at the other.
     */
    fun neighbour(board: CollageBoard, from: Int, move: CollageMove): CollageStep? {
        val start = board.tiles.getOrNull(from) ?: return null
        val (dx, dy) = when (move) {
            CollageMove.LEFT -> -1f to 0f
            CollageMove.RIGHT -> 1f to 0f
            CollageMove.UP -> 0f to -1f
            CollageMove.DOWN -> 0f to 1f
        }
        var best: CollageStep? = null
        var bestScore = Float.MAX_VALUE
        for (t in board.tiles) {
            if (t.index == from) continue
            // The copy of t nearest to the start, across the wrap.
            val tx = t.cx + board.width * ((start.cx - t.cx) / board.width).roundToInt()
            val ty = t.cy + board.height * ((start.cy - t.cy) / board.height).roundToInt()
            for (sx in -1..1) for (sy in -1..1) {
                val px = tx + sx * board.width
                val py = ty + sy * board.height
                val vx = px - start.cx
                val vy = py - start.cy
                val along = vx * dx + vy * dy
                if (along <= 0.05f) continue
                val across = abs(vx * dy - vy * dx)
                if (across > along * 1.4f + 0.6f) continue
                val score = along + across * 2f
                if (score < bestScore) { bestScore = score; best = CollageStep(t.index, px, py) }
            }
        }
        return best
    }

    /**
     * The hairlines among [shown] tiles: for each thing they share ([keysOf] a tile's genres, collections,
     * directors or writers; at most [perTile] of each tile's), the tiles having it joined in reading
     * order, the thing on the line. At most [max] lines.
     */
    fun links(board: CollageBoard, shown: Collection<Int>, keysOf: (Int) -> List<String>, perTile: Int = 2, max: Int = 48): List<CollageLink> {
        val byKey = LinkedHashMap<String, MutableList<CollageTile>>()
        for (i in shown) {
            val t = board.tiles.getOrNull(i) ?: continue
            keysOf(i).distinct().take(perTile).forEach { k -> byKey.getOrPut(k) { mutableListOf() } += t }
        }
        val out = mutableListOf<CollageLink>()
        val seen = HashSet<Pair<Int, Int>>()
        // Rarer things first: a shared director says more than "Drama".
        for ((key, members) in byKey.entries.filter { it.value.size > 1 }.sortedBy { it.value.size }) {
            val ordered = members.sortedWith(compareBy({ it.y }, { it.x }))
            for (k in 1 until ordered.size) {
                val a = ordered[k - 1].index
                val b = ordered[k].index
                val pair = minOf(a, b) to maxOf(a, b)
                if (!seen.add(pair)) continue
                out += CollageLink(a, b, key)
                if (out.size >= max) return out
            }
        }
        return out
    }

    /** Random hairlines among [shown]: each tile tied to one or two of its nearest shown neighbours, picked by chance. */
    fun randomLinks(board: CollageBoard, shown: Collection<Int>, max: Int = 48): List<CollageLink> {
        val list = shown.mapNotNull { board.tiles.getOrNull(it) }
        val out = mutableListOf<CollageLink>()
        val seen = HashSet<Pair<Int, Int>>()
        for (t in list) {
            val near = list.filter { it.index != t.index }.sortedBy { (it.cx - t.cx) * (it.cx - t.cx) + (it.cy - t.cy) * (it.cy - t.cy) }.take(3)
            val picks = if (rnd(t.index, 3) < 0.5f) 1 else 2
            near.filterIndexed { k, _ -> rnd(t.index * 7 + k, 5) < 0.6f }.take(picks).forEach { o ->
                val pair = minOf(t.index, o.index) to maxOf(t.index, o.index)
                if (seen.add(pair)) out += CollageLink(t.index, o.index, null)
            }
            if (out.size >= max) break
        }
        return out.take(max)
    }

    /** A steady pseudo-random number in 0..1 for [i] and [salt]. */
    fun rnd(i: Int, salt: Int): Float {
        var x = i * 374761393 + salt * 668265263
        x = (x xor (x ushr 13)) * 1274126177
        return ((x xor (x ushr 16)) and 0x7fffffff) / 2147483648f
    }
}
