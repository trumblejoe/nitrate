package dev.nitrate.ios

import dev.nitrate.core.MediaServer
import dev.nitrate.core.jellyfin.JellyfinClient
import io.ktor.client.engine.darwin.Darwin

/** The iOS app's shared pieces: the server client and this device's settings. */
class IosContainer {
    internal val playbackSettings = IosPlaybackSettings()
    internal val capabilities by lazy { appleCapabilities() }

    val server: MediaServer = JellyfinClient(
        engine = Darwin.create(),
        identity = iosIdentity(),
        sessionStorage = IosSessionStore(),
        capabilities = { capabilities },
        playbackSettings = playbackSettings,
    )

    companion object {
        val shared by lazy { IosContainer() }
    }
}
