package dev.nitrate.tv.data

import android.content.Context
import dev.nitrate.tv.BuildConfig
import dev.nitrate.tv.data.playback.DeviceCapabilities
import dev.nitrate.tv.data.playback.PlaybackSettingsStore
import dev.nitrate.tv.data.rows.DisplaySettingsStore
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo

/** Simple manual dependency container. Swap for Hilt/Koin if the graph grows. */
class AppContainer(context: Context) {
    val jellyfin = createJellyfin {
        clientInfo = ClientInfo(name = "Nitrate", version = BuildConfig.VERSION_NAME)
        this.context = context.applicationContext
    }

    val sessionStore = SessionStore(context.applicationContext)

    val displaySettings = DisplaySettingsStore(context.applicationContext)

    val playbackSettings = PlaybackSettingsStore(context.applicationContext)

    /** Probed once: decoder lists don't change while the app runs. */
    val capabilities: DeviceCapabilities by lazy { DeviceCapabilities.probe(context.applicationContext) }

    val repository = JellyfinRepository(jellyfin, sessionStore, { capabilities }, playbackSettings)

    val settingsSync = SettingsSync(repository, displaySettings)
}
