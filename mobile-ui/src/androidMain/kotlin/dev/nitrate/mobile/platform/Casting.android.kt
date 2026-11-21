package dev.nitrate.mobile.platform

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import dev.nitrate.core.cast.Cast
import dev.nitrate.core.cast.CastStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Tells Google's Cast framework to use Jellyfin's receiver app (named in the manifest). */
class NitrateCastOptions : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(Cast.RECEIVER_APP_ID)
            .setStopReceiverApplicationWhenEndingSession(true)
            .build()

    override fun getAdditionalSessionProviders(context: Context) = null
}

/** Casting through Google's framework: devices from the media router, one Cast session at a time. */
internal class AndroidCast private constructor(context: Context, private val cast: CastContext) : CastControl {
    companion object {
        @Volatile private var shared: AndroidCast? = null
        @Volatile private var unavailable = false

        /** The one instance, or null without Google Play services. Main thread only. */
        fun get(context: Context): AndroidCast? {
            shared?.let { return it }
            if (unavailable) return null
            @Suppress("DEPRECATION")
            val cast = runCatching { CastContext.getSharedInstance(context.applicationContext) }.getOrNull()
            if (cast == null) { unavailable = true; return null }
            return AndroidCast(context.applicationContext, cast).also { shared = it }
        }
    }

    /** Reads the receiver's messages (set by the app from its server client). */
    var parse: (String) -> CastStatus? = { null }

    private val router = MediaRouter.getInstance(context)
    private val selector: MediaRouteSelector = cast.mergedSelector
        ?: MediaRouteSelector.Builder().addControlCategory(CastMediaControlIntent.categoryForCast(Cast.RECEIVER_APP_ID)).build()
    private val _state = MutableStateFlow(CastState())
    override val state: StateFlow<CastState> = _state.asStateFlow()

    private val routes = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
    }

    private val sessions = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) = Unit
        override fun onSessionStarted(session: CastSession, sessionId: String) = attach(session)
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = attach(session)
        override fun onSessionStartFailed(session: CastSession, error: Int) = detach()
        override fun onSessionResumeFailed(session: CastSession, error: Int) = detach()
        override fun onSessionEnding(session: CastSession) = Unit
        override fun onSessionEnded(session: CastSession, error: Int) = detach()
        override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
        override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
    }

    init {
        router.addCallback(selector, routes, 0)
        cast.sessionManager.addSessionManagerListener(sessions, CastSession::class.java)
        cast.sessionManager.currentCastSession?.takeIf { it.isConnected }?.let(::attach)
        refresh()
    }

    private fun refresh() {
        val found = router.routes.filter { !it.isDefault && it.isEnabled && it.matchesSelector(selector) }
            .map { CastDevice(it.id, it.name, it.description) }
        _state.value = _state.value.copy(devices = found)
    }

    private fun attach(session: CastSession) {
        runCatching {
            session.setMessageReceivedCallbacks(Cast.NAMESPACE) { _, _, message ->
                parse(message)?.let { _state.value = _state.value.copy(status = it, statusAtMs = System.currentTimeMillis()) }
            }
        }
        val name = session.castDevice?.friendlyName ?: "Chromecast"
        val selected = router.selectedRoute.takeIf { !it.isDefault }
        _state.value = _state.value.copy(connected = CastDevice(selected?.id ?: name, name), connecting = null)
    }

    private fun detach() {
        _state.value = _state.value.copy(connected = null, connecting = null, status = null)
    }

    override fun discover(on: Boolean) {
        router.removeCallback(routes)
        router.addCallback(selector, routes, if (on) MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY else 0)
        refresh()
    }

    override fun connect(deviceId: String) {
        val route = router.routes.firstOrNull { it.id == deviceId } ?: return
        _state.value = _state.value.copy(connecting = CastDevice(route.id, route.name, route.description))
        // The framework starts a session with Jellyfin's receiver on the chosen device.
        router.selectRoute(route)
    }

    override fun disconnect() {
        cast.sessionManager.endCurrentSession(true)
        detach()
    }

    override fun send(message: String) {
        cast.sessionManager.currentCastSession?.sendMessage(Cast.NAMESPACE, message)
    }
}

@Composable
actual fun rememberCast(): CastControl? {
    val context = LocalContext.current
    val container = dev.nitrate.mobile.ui.LocalAppContainer.current
    return remember(context, container) {
        AndroidCast.get(context)?.also { cast -> container?.let { c -> cast.parse = { c.server.castStatus(it) } } }
    }
}
