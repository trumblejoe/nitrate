package dev.nitrate.ios

import dev.nitrate.mobile.platform.IosCast
import dev.nitrate.mobile.platform.NitrateCastBridge
import dev.nitrate.mobile.platform.NitrateCastListener

/**
 * What the Swift app implements over Google's Cast SDK (drafted, not yet built, in
 * iosApp/Drafts/NitrateCast.swift). Mirrors mobile-ui's [NitrateCastBridge], kept here so the
 * framework's header names it plainly for Swift, as [NitrateMpv] is.
 */
interface NitrateCastSdk {
    fun start(events: NitrateCastEvents)
    fun discover(on: Boolean)
    fun connect(deviceId: String)
    fun disconnect()
    fun send(message: String)
}

/** What the Swift side tells Kotlin (any thread). */
interface NitrateCastEvents {
    fun devices(ids: List<String>, names: List<String>, descriptions: List<String>)
    fun connecting(deviceId: String, name: String)
    fun connected(deviceId: String, name: String)
    fun disconnected()
    fun message(text: String)
}

/** Called once by the Swift app at start, when it ships the Cast SDK: "Play on…" then lists Chromecasts. */
fun useCast(sdk: NitrateCastSdk) {
    IosCast.useCast(object : NitrateCastBridge {
        override fun start(listener: NitrateCastListener) = sdk.start(object : NitrateCastEvents {
            override fun devices(ids: List<String>, names: List<String>, descriptions: List<String>) = listener.devices(ids, names, descriptions)
            override fun connecting(deviceId: String, name: String) = listener.connecting(deviceId, name)
            override fun connected(deviceId: String, name: String) = listener.connected(deviceId, name)
            override fun disconnected() = listener.disconnected()
            override fun message(text: String) = listener.message(text)
        })
        override fun discover(on: Boolean) = sdk.discover(on)
        override fun connect(deviceId: String) = sdk.connect(deviceId)
        override fun disconnect() = sdk.disconnect()
        override fun send(message: String) = sdk.send(message)
    })
}
