package dev.nitrate.tv.data

import android.content.Context
import dev.nitrate.tv.BuildConfig
import dev.nitrate.tv.data.playback.DeviceProfileFactory
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo

/** Simple manual dependency container. Swap for Hilt/Koin if the graph grows. */
class AppContainer(context: Context) {
    val jellyfin = createJellyfin {
        clientInfo = ClientInfo(name = "Nitrate", version = BuildConfig.VERSION_NAME)
        this.context = context.applicationContext
    }

    val sessionStore = SessionStore(context.applicationContext)

    val repository = JellyfinRepository(jellyfin, sessionStore, DeviceProfileFactory(context.applicationContext))
}
