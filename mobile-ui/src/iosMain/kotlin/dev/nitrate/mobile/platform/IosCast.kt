package dev.nitrate.mobile.platform

import dev.nitrate.core.cast.CastStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * What the Swift app implements over Google's Cast SDK for iOS (one session with Jellyfin's receiver,
 * `Cast.RECEIVER_APP_ID`, on the `Cast.NAMESPACE` channel). Nothing implements it yet: the Swift
 * side is drafted in iosApp/Drafts/NitrateCast.swift and not built (see its TODO), so [IosCast.bridge]
 * stays null and [rememberCast] gives null as before.
 */
interface NitrateCastBridge {
    /** Starts the SDK once and tells [listener] what happens from then on. */
    fun start(listener: NitrateCastListener)
    fun discover(on: Boolean)
    fun connect(deviceId: String)
    fun disconnect()
    fun send(message: String)
}

/** What the Swift side reports (any thread). */
interface NitrateCastListener {
    fun devices(ids: List<String>, names: List<String>, descriptions: List<String>)
    fun connecting(deviceId: String, name: String)
    fun connected(deviceId: String, name: String)
    fun disconnected()
    fun message(text: String)
}

/** [CastControl] over the Swift [NitrateCastBridge]; messages read by the server client, as on Android. */
class IosCast private constructor(private val bridge: NitrateCastBridge) : CastControl, NitrateCastListener {
    private val _state = MutableStateFlow(CastState())
    override val state: StateFlow<CastState> = _state.asStateFlow()
    var parse: (String) -> CastStatus? = { null }

    override fun discover(on: Boolean) = bridge.discover(on)
    override fun connect(deviceId: String) = bridge.connect(deviceId)
    override fun disconnect() = bridge.disconnect()
    override fun send(message: String) = bridge.send(message)

    private fun onMain(work: () -> Unit) = dispatch_async(dispatch_get_main_queue()) { work() }

    override fun devices(ids: List<String>, names: List<String>, descriptions: List<String>) = onMain {
        _state.value = _state.value.copy(devices = ids.indices.map { CastDevice(ids[it], names.getOrElse(it) { ids[it] }, descriptions.getOrNull(it)?.ifEmpty { null }) })
    }

    override fun connecting(deviceId: String, name: String) = onMain {
        _state.value = _state.value.copy(connecting = CastDevice(deviceId, name))
    }

    override fun connected(deviceId: String, name: String) = onMain {
        _state.value = _state.value.copy(connected = CastDevice(deviceId, name), connecting = null)
    }

    override fun disconnected() = onMain {
        _state.value = _state.value.copy(connected = null, connecting = null, status = null)
    }

    override fun message(text: String) = onMain {
        parse(text)?.let { _state.value = _state.value.copy(status = it, statusAtMs = dev.nitrate.android.nowMs()) }
    }

    companion object {
        /** Set once by the Swift app at start ([useCast]); null: no casting on this build. */
        var bridge: NitrateCastBridge? = null
            private set
        private var instance: IosCast? = null

        /** Called once by the Swift app at start, when it ships Google's Cast SDK. */
        fun useCast(bridge: NitrateCastBridge) {
            this.bridge = bridge
        }

        fun get(): IosCast? = instance ?: bridge?.let { b -> IosCast(b).also { instance = it; b.start(it) } }
    }
}
