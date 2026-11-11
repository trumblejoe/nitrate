package dev.nitrate.android

import android.content.Context
import dev.nitrate.android.playback.DeviceProbe
import dev.nitrate.android.storage.DisplaySettingsStore
import dev.nitrate.android.storage.PlaybackSettingsStore
import dev.nitrate.android.storage.PluginStore
import dev.nitrate.android.storage.SessionStore
import dev.nitrate.android.sync.SettingsSync
import dev.nitrate.android.writing.WritingStore
import dev.nitrate.core.MediaServer
import dev.nitrate.core.episodes.PreviousEpisodePlugin
import dev.nitrate.core.jellyfin.JellyfinClient
import dev.nitrate.core.playback.DeviceCapabilities
import dev.nitrate.core.plugins.PluginHost
import dev.nitrate.core.plugins.PluginRegistry
import dev.nitrate.core.writing.WritingPlugin
import io.ktor.client.engine.okhttp.OkHttp

/**
 * Manual dependency container shared by the TV and phone/tablet apps. Each app passes its own
 * [clientName] and [version], which the server shows in its device list.
 */
class AppContainer(
    context: Context,
    clientName: String,
    version: String,
    /** Replaces the Jellyfin server, for screenshot tests and previews. */
    serverOverride: MediaServer? = null,
) {
    private val appContext = context.applicationContext

    val displaySettings = DisplaySettingsStore(appContext)

    val playbackSettings = PlaybackSettingsStore(appContext)

    /** Probed once: decoder lists don't change while the app runs. */
    val capabilities: DeviceCapabilities by lazy { DeviceProbe.probe(appContext) }

    val server: MediaServer = serverOverride ?: JellyfinClient(
        engine = OkHttp.create(),
        identity = androidIdentity(appContext, clientName, version),
        sessionStorage = SessionStore(appContext),
        capabilities = { capabilities },
        playbackSettings = playbackSettings,
    )

    val settingsSync = SettingsSync(server, displaySettings)

    val writing by lazy { WritingStore(appContext, server) }

    val plugins = PluginRegistry(
        all = listOf(PreviousEpisodePlugin, WritingPlugin),
        host = PluginHost(server, PluginStore(appContext)),
    )
}
