package dev.nitrate.core.pages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CollageTest {
    private fun overlaps(a: CollageTile, b: CollageTile) =
        a.x < b.x + b.w - 0.001f && b.x < a.x + a.w - 0.001f && a.y < b.y + b.h - 0.001f && b.y < a.y + a.h - 0.001f

    @Test fun tilesNeverOverlapAndTheBoardHoldsThemAll() {
        val board = Collage.layout(120)
        assertEquals(120, board.tiles.size)
        for (i in board.tiles.indices) for (j in i + 1 until board.tiles.size) assertFalse("$i/$j", overlaps(board.tiles[i], board.tiles[j]))
        assertTrue(board.tiles.all { it.x + it.w <= board.width + 0.001f && it.y + it.h <= board.height + 0.001f })
        assertTrue(board.tiles.any { it.wide })
        assertTrue(board.tiles.any { !it.wide && it.w > 1.5f })
    }

    @Test fun moreTitlesOnlyAddToTheBoard() {
        val small = Collage.layout(40)
        val big = Collage.layout(80)
        assertEquals(small.tiles, big.tiles.take(40))
    }

    @Test fun movesGoTheRightWayAndWrapRound() {
        val board = Collage.layout(60)
        val from = board.tiles[10]
        val right = Collage.neighbour(board, 10, CollageMove.RIGHT)!!
        assertTrue(right.x > from.cx)
        val down = Collage.neighbour(board, 10, CollageMove.DOWN)!!
        assertTrue(down.y > from.cy)
        // From the leftmost tile, Left lands across the wrap: further left than the board's edge.
        val leftmost = board.tiles.minBy { it.cx }
        val wrapped = Collage.neighbour(board, leftmost.index, CollageMove.LEFT)!!
        assertTrue(wrapped.x < leftmost.cx)
        assertTrue(wrapped.x < 0f)
        // Every tile can go every way (the board has no edge).
        for (t in board.tiles) for (m in CollageMove.entries) assertNotNull("${t.index} $m", Collage.neighbour(board, t.index, m))
    }

    @Test fun linesJoinWhatTitlesShareRarerFirst() {
        val board = Collage.layout(6)
        val keys = mapOf(
            0 to listOf("Drama", "Nolan"), 1 to listOf("Drama"), 2 to listOf("Drama", "Nolan"),
            3 to listOf("Comedy"), 4 to listOf("Drama"), 5 to emptyList(),
        )
        val links = Collage.links(board, (0..5).toList(), { keys[it].orEmpty() })
        assertEquals("Nolan", links.first().label)
        assertEquals(setOf(0, 2), setOf(links.first().from, links.first().to))
        // Drama's four in a chain (three lines), one already drawn by Nolan's skipped if the same pair.
        assertTrue(links.count { it.label == "Drama" } in 2..3)
        assertTrue(links.none { it.label == "Comedy" })
        assertTrue(links.none { it.from == 5 || it.to == 5 })
        assertEquals(2, Collage.links(board, (0..5).toList(), { keys[it].orEmpty() }, max = 2).size)
    }

    @Test fun randomLinesStayAmongThoseShown() {
        val board = Collage.layout(30)
        val shown = (5..20).toList()
        val links = Collage.randomLinks(board, shown)
        assertTrue(links.isNotEmpty())
        assertTrue(links.all { it.from in shown && it.to in shown && it.label == null })
        assertEquals(links, Collage.randomLinks(board, shown))
    }
}
