package dev.nitrate.android

import android.content.Context
import dev.nitrate.android.jellyfin.JellyfinServer
import dev.nitrate.android.playback.DeviceProbe
import dev.nitrate.android.storage.DisplaySettingsStore
import dev.nitrate.android.storage.PlaybackSettingsStore
import dev.nitrate.android.storage.PluginStore
import dev.nitrate.android.storage.SessionStore
import dev.nitrate.android.sync.SettingsSync
import dev.nitrate.android.writing.WritingStore
import dev.nitrate.core.MediaServer
import dev.nitrate.core.episodes.PreviousEpisodePlugin
import dev.nitrate.core.playback.DeviceCapabilities
import dev.nitrate.core.plugins.PluginHost
import dev.nitrate.core.plugins.PluginRegistry
import dev.nitrate.core.writing.WritingPlugin
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo

/**
 * Manual dependency container shared by the TV and phone/tablet apps. Each app passes its own
 * [clientName] and [version], which the server shows in its device list.
 */
class AppContainer(context: Context, clientName: String, version: String) {
    private val appContext = context.applicationContext

    private val jellyfin = createJellyfin {
        clientInfo = ClientInfo(name = clientName, version = version)
        this.context = appContext
    }

    val displaySettings = DisplaySettingsStore(appContext)

    val playbackSettings = PlaybackSettingsStore(appContext)

    /** Probed once: decoder lists don't change while the app runs. */
    val capabilities: DeviceCapabilities by lazy { DeviceProbe.probe(appContext) }

    val server: MediaServer = JellyfinServer(jellyfin, SessionStore(appContext), { capabilities }, playbackSettings)

    val settingsSync = SettingsSync(server, displaySettings)

    val writing by lazy { WritingStore(appContext, server) }

    val plugins = PluginRegistry(
        all = listOf(PreviousEpisodePlugin, WritingPlugin),
        host = PluginHost(server, PluginStore(appContext)),
    )
}
