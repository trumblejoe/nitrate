package dev.nitrate.core.settings

/**
 * Where a setting applies, shown with it so it's clear what follows you: this device only
 * (playback settings, never synced), the display profile this device uses (synced to the devices
 * on that profile), or every device signed in as you.
 */
enum class SettingScope {
    DEVICE,
    PROFILE,
    EVERYWHERE,
    ;

    /** [device] is "TV" or "phone"; [profile] the display profile's name. */
    fun label(device: String, profile: String?): String = when (this) {
        DEVICE -> "This $device only"
        PROFILE -> if (profile != null) "Display profile “$profile”" else "Your display profile"
        EVERYWHERE -> "All your devices"
    }
}
