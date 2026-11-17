package dev.nitrate.android.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import dev.nitrate.core.playback.OutputKind
import dev.nitrate.core.playback.SoundOutput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Where media sound goes right now (HDMI to a receiver, the TV, headphones, Bluetooth...), kept
 * up to date as outputs come and go (a soundbar turning on or off, headphones plugged in).
 */
object SoundOutputs {
    private val state = MutableStateFlow<SoundOutput?>(null)
    @Volatile private var started = false

    fun current(context: Context): StateFlow<SoundOutput?> {
        if (!started) start(context.applicationContext)
        return state
    }

    @Synchronized
    private fun start(context: Context) {
        if (started) return
        started = true
        val manager = context.getSystemService(AudioManager::class.java) ?: return
        fun refresh() { state.value = runCatching { pick(manager) }.getOrNull() }
        refresh()
        manager.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = refresh()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = refresh()
        }, Handler(Looper.getMainLooper()))
    }

    /** The output media plays through: Android says on 13+, else the likeliest one connected. */
    private fun pick(manager: AudioManager): SoundOutput? {
        val device = if (Build.VERSION.SDK_INT >= 33) {
            manager.getAudioDevicesForAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()).firstOrNull()
        } else {
            null
        } ?: manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).minByOrNull { rank(it.type) }
        return device?.let(::describe)
    }

    private fun rank(type: Int) = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, 26 /* BLE headset */, 27 /* BLE speaker */ -> 0
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET -> 1
        AudioDeviceInfo.TYPE_USB_DEVICE -> 2
        29 /* HDMI eARC */, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI -> 3
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 4
        else -> 5
    }

    private fun describe(d: AudioDeviceInfo): SoundOutput {
        val kind = when (d.type) {
            AudioDeviceInfo.TYPE_HDMI -> OutputKind.HDMI
            AudioDeviceInfo.TYPE_HDMI_ARC -> OutputKind.HDMI_ARC
            29 -> OutputKind.HDMI_EARC
            AudioDeviceInfo.TYPE_USB_DEVICE -> OutputKind.USB
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> OutputKind.SPEAKER
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET -> OutputKind.HEADPHONES
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, 26, 27 -> OutputKind.BLUETOOTH
            else -> OutputKind.OTHER
        }
        val counts = d.channelCounts.toList() + d.channelMasks.map { Integer.bitCount(it) }
        return SoundOutput(kind, d.productName?.toString().orEmpty(), counts.maxOrNull())
    }
}
