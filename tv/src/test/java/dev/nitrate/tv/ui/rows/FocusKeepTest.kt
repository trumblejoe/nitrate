package dev.nitrate.tv.ui.rows

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.rows.MediaRow
import dev.nitrate.core.rows.RowDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FocusKeepTest {
    private fun ep(n: Int) = MediaItem("e$n", "Ep $n", ItemKind.EPISODE)
    private fun row(key: String, vararg n: Int) = MediaRow(key, key, n.map(::ep), RowDisplay())

    @Test fun stillThereNeedsNothing() =
        assertNull(FocusKeep.replacement(listOf(row("a", 1, 2)), listOf(row("a", 2, 1)), 0, "e1"))

    @Test fun samePlaceInTheRow() =
        assertEquals(0 to "e4", FocusKeep.replacement(listOf(row("a", 1, 2, 3)), listOf(row("a", 4, 2, 3)), 0, "e1"))

    @Test fun lastCardGoneTakesTheNewLast() =
        assertEquals(0 to "e2", FocusKeep.replacement(listOf(row("a", 1, 2, 3)), listOf(row("a", 1, 2)), 0, "e3"))

    @Test fun rowGoneTakesTheRowInItsPlace() =
        assertEquals(0 to "e9", FocusKeep.replacement(listOf(row("a", 1), row("b", 9)), listOf(row("b", 9)), 0, "e1"))

    @Test fun lastRowGoneTakesTheOneAbove() =
        assertEquals(0 to "e9", FocusKeep.replacement(listOf(row("b", 9), row("a", 1)), listOf(row("b", 9)), 1, "e1"))

    @Test fun emptyRowIsSkipped() =
        assertEquals(2 to "e7", FocusKeep.replacement(listOf(row("a", 1), row("b", 2), row("c", 7)), listOf(row("x"), row("a"), row("c", 7)), 0, "e1"))

    @Test fun nothingLeft() = assertNull(FocusKeep.replacement(listOf(row("a", 1)), emptyList(), 0, "e1"))
}
