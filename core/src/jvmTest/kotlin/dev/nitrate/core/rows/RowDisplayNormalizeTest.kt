package dev.nitrate.core.rows

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RowDisplayNormalizeTest {
    @Test fun retiredStylesShowAsArtwork() {
        assertEquals(RowDisplay(RowStyle.WIDE), RowDisplay(RowStyle.SHOWCASE).normalized())
        val full = RowDisplay(RowStyle.FULL_SHOWCASE, showcase = ShowcaseOptions(rotateArtwork = true)).normalized()
        assertTrue(full.cardsHidden)
        assertTrue(full.showcase.rotateArtwork, "options kept")
        assertEquals(RowDisplay(RowStyle.CRESSIDA), RowDisplay(RowStyle.CRESSIDA).normalized())
    }

    @Test fun pickersOfferNoShowcase() {
        assertFalse(RowStyle.SHOWCASE in RowStyle.choices)
        assertFalse(RowStyle.FULL_SHOWCASE in RowStyle.choices)
    }

    @Test fun steppingPassesThroughHiddenCards() {
        val wide = RowDisplay(RowStyle.WIDE)
        val hidden = wide.stepped(1)
        assertTrue(hidden.cardsHidden)
        assertEquals(RowStyle.FILM_STRIP, hidden.stepped(1).style)
        assertTrue(hidden.stepped(1).cards)
        assertEquals(wide, hidden.stepped(-1))
        assertEquals(RowStyle.CRESSIDA, RowDisplay(RowStyle.POSTER).stepped(-1).style)
        assertEquals(RowStyle.POSTER, RowDisplay(RowStyle.CRESSIDA).stepped(1).style)
        // A saved Full Showcase steps on from where it shows now.
        assertEquals(RowStyle.FILM_STRIP, RowDisplay(RowStyle.FULL_SHOWCASE).stepped(1).style)
    }

    @Test fun latestRowsNoLongerShowcases() {
        val row = MediaRow("home/latest/abc", "Latest", emptyList(), RowDisplay())
        assertEquals(RowStyle.WIDE, ArtworkFirst.display(row).style)
    }
}
