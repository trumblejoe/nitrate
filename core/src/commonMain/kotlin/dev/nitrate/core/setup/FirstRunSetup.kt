package dev.nitrate.core.setup

/**
 * One calm setup after the first sign-in on a device, in place of separate prompts: each step is
 * skippable, one screen at a time, and only the ones that apply are shown.
 */
enum class SetupStep(val title: String) {
    /** Who's watching: only with other saved accounts or PINs on this device. */
    PROFILE("Who's watching?"),
    /** Copy another device's settings (the backup offer), or start fresh. */
    COPY_SETTINGS("Copy settings from another device?"),
    /** The speakers on the sound output in use (not for stereo-only outputs). */
    SOUND("Your sound"),
    /** TV: menu group suggestions and what goes in More. */
    MENU("Tidy the menu"),
    /** Where to change all of this later. */
    DONE("All set"),
}

/** What the device and account offer when the setup starts. */
data class SetupFacts(
    val tv: Boolean,
    /** Other accounts saved on this device, or PINs set on any. */
    val profilesToAsk: Boolean,
    /** Other devices' backups to copy from (the backup offer). */
    val backupsOffered: Boolean,
    /** The sound output in use would be asked about its speakers ([dev.nitrate.core.playback.Speakers.shouldAsk]). */
    val askSound: Boolean,
    /** TV: menu groups worth suggesting. */
    val menuSuggestions: Boolean,
)

object FirstRunSetup {
    /** The steps that apply, in order; "All set" always last. */
    fun steps(facts: SetupFacts): List<SetupStep> = buildList {
        if (facts.profilesToAsk) add(SetupStep.PROFILE)
        if (facts.backupsOffered) add(SetupStep.COPY_SETTINGS)
        if (facts.askSound) add(SetupStep.SOUND)
        if (facts.tv && facts.menuSuggestions) add(SetupStep.MENU)
        add(SetupStep.DONE)
    }

    /** "Step 2 of 3" for [step] among [steps] ("All set" isn't counted); null for "All set". */
    fun progress(steps: List<SetupStep>, step: SetupStep): String? {
        if (step == SetupStep.DONE) return null
        val counted = steps.filter { it != SetupStep.DONE }
        val at = counted.indexOf(step)
        return if (at < 0) null else "Step ${at + 1} of ${counted.size}"
    }

    /**
     * Whether the setup runs on this sign-in: never on a device that finished it, and never on one
     * that was already set up for this account before the setup existed (it answered the backup offer).
     */
    fun shouldRun(done: Boolean, accountSettledBefore: Boolean): Boolean = !done && !accountSettledBefore
}
