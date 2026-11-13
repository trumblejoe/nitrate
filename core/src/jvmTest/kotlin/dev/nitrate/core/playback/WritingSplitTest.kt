package dev.nitrate.core.playback

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WritingSplitTest {
    @Test
    fun fixedChoicesAreKept() {
        assertTrue(WritingSplit.SIDE_BY_SIDE.sideBySide(400f, 800f, 16f / 9f))
        assertFalse(WritingSplit.STACKED.sideBySide(1280f, 800f, 16f / 9f))
    }

    @Test
    fun uprightPhoneStacks() {
        assertFalse(WritingSplit.AUTO.sideBySide(400f, 860f, 16f / 9f))
        assertFalse(WritingSplit.AUTO.sideBySide(400f, 860f, 4f / 3f))
    }

    @Test
    fun wideScreenPutsWideFilmsOnTopAndSquareOnesBeside() {
        // A landscape tablet: a 2.39:1 film keeps more picture across the top,
        // a 4:3 show more beside the pane.
        assertFalse(WritingSplit.AUTO.sideBySide(1280f, 800f, 2.39f))
        assertTrue(WritingSplit.AUTO.sideBySide(1280f, 800f, 4f / 3f))
    }

    @Test
    fun landscapePhoneGoesSideBySide() {
        assertTrue(WritingSplit.AUTO.sideBySide(860f, 400f, 16f / 9f))
    }
}
