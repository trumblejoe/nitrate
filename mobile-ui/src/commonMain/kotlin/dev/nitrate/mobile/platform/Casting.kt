package dev.nitrate.mobile.platform

import androidx.compose.runtime.Composable
import dev.nitrate.core.cast.CastStatus
import kotlinx.coroutines.flow.StateFlow

/** A Chromecast (or a TV with Chromecast built in) on the network. */
data class CastDevice(val id: String, val name: String, val description: String? = null)

/** Casting as it stands: the devices found, the one connected (or connecting), and what it last said. */
data class CastState(
    val devices: List<CastDevice> = emptyList(),
    val connected: CastDevice? = null,
    val connecting: CastDevice? = null,
    val status: CastStatus? = null,
    /** When [status] arrived (`nowMs()`), to move its position along while playing. */
    val statusAtMs: Long = 0,
)

/**
 * The platform's casting: Google's Cast framework on Android (talking to Jellyfin's receiver), none
 * elsewhere yet. Messages are built and read by the server client; this only finds devices,
 * connects and carries them.
 */
interface CastControl {
    val state: StateFlow<CastState>
    /** Looks actively for devices while a list of them is showing. */
    fun discover(on: Boolean)
    fun connect(deviceId: String)
    /** Stops casting: the receiver closes and the device is let go. */
    fun disconnect()
    fun send(message: String)
}

/** Casting on this device, or null where it isn't available (no Google Play services, iOS for now). */
@Composable
expect fun rememberCast(): CastControl?

/**
 * Keeps lock-screen and notification controls for what another device ([sessionId]) is playing,
 * while it plays, even with the app closed (Android); nothing elsewhere yet.
 */
@Composable
expect fun rememberRemoteFollower(): (sessionId: String) -> Unit
