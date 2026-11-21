package dev.nitrate.mobile

import dev.nitrate.core.cast.CastStatus
import dev.nitrate.mobile.platform.CastControl
import dev.nitrate.mobile.platform.CastDevice
import dev.nitrate.mobile.platform.CastState
import kotlinx.coroutines.flow.MutableStateFlow

/** A Chromecast for screenshots and tests: one device, connected or not, keeping what was sent. */
class FakeCast(connected: Boolean = true, status: CastStatus? = null, atMs: Long = 0) : CastControl {
    private val device = CastDevice("living-room", "Living room TV", "Chromecast")
    override val state = MutableStateFlow(CastState(listOf(device), device.takeIf { connected }, status = status, statusAtMs = atMs))
    val sent = mutableListOf<String>()
    override fun discover(on: Boolean) = Unit
    override fun connect(deviceId: String) { state.value = state.value.copy(connected = device) }
    override fun disconnect() { state.value = state.value.copy(connected = null, status = null) }
    override fun send(message: String) { sent += message }
}
