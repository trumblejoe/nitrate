package dev.nitrate.core.playback

/**
 * The one "Sound" choice over [PlaybackSettings.nightMode] and [PlaybackSettings.dialogueBoost]:
 * Normal, voices lifted, or Night (loud parts evened out and voices lifted). Stored as the two
 * switches so older versions and backups keep reading them.
 */
enum class SoundMode(val label: String, val detail: String) {
    NORMAL("Normal", "Sound as the file has it"),
    CLEARER_DIALOGUE("Clearer dialogue", "Voices lifted over music and effects. For mumbled lines and loud scores"),
    NIGHT("Night", "Quieter explosions, louder voices. For late-night watching"),
    ;

    companion object {
        /** Anything but Normal decodes sound that would otherwise go to a receiver untouched. */
        const val RECEIVER_NOTE = "Clearer dialogue and Night decode sound here that would otherwise go to a receiver as it is (no Atmos while on)."

        fun of(settings: PlaybackSettings): SoundMode = when {
            settings.nightMode -> NIGHT
            settings.dialogueBoost -> CLEARER_DIALOGUE
            else -> NORMAL
        }
    }
}

val PlaybackSettings.soundMode: SoundMode get() = SoundMode.of(this)

fun PlaybackSettings.withSoundMode(mode: SoundMode): PlaybackSettings =
    copy(nightMode = mode == SoundMode.NIGHT, dialogueBoost = mode != SoundMode.NORMAL)
