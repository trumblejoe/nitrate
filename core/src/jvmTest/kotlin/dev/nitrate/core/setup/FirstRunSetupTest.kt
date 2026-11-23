package dev.nitrate.core.setup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FirstRunSetupTest {
    private val none = SetupFacts(tv = true, profilesToAsk = false, backupsOffered = false, askSound = false, menuSuggestions = false)

    @Test fun `only the steps that apply, all set last`() {
        assertEquals(listOf(SetupStep.DONE), FirstRunSetup.steps(none))
        assertEquals(
            listOf(SetupStep.PROFILE, SetupStep.COPY_SETTINGS, SetupStep.SOUND, SetupStep.MENU, SetupStep.DONE),
            FirstRunSetup.steps(SetupFacts(tv = true, profilesToAsk = true, backupsOffered = true, askSound = true, menuSuggestions = true)),
        )
    }

    @Test fun `the menu step is the TV's only`() {
        val phone = none.copy(tv = false, menuSuggestions = true, askSound = true)
        assertEquals(listOf(SetupStep.SOUND, SetupStep.DONE), FirstRunSetup.steps(phone))
    }

    @Test fun `progress counts the steps before all set`() {
        val steps = FirstRunSetup.steps(none.copy(backupsOffered = true, askSound = true))
        assertEquals("Step 1 of 2", FirstRunSetup.progress(steps, SetupStep.COPY_SETTINGS))
        assertEquals("Step 2 of 2", FirstRunSetup.progress(steps, SetupStep.SOUND))
        assertNull(FirstRunSetup.progress(steps, SetupStep.DONE))
    }

    @Test fun `runs once, and never on a device set up before it existed`() {
        assertTrue(FirstRunSetup.shouldRun(done = false, accountSettledBefore = false))
        assertFalse(FirstRunSetup.shouldRun(done = true, accountSettledBefore = false))
        assertFalse(FirstRunSetup.shouldRun(done = false, accountSettledBefore = true))
    }
}
