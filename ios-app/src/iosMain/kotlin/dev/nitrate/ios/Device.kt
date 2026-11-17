package dev.nitrate.ios

import dev.nitrate.core.jellyfin.ClientIdentity
import dev.nitrate.core.playback.AudioCodecCaps
import dev.nitrate.core.playback.DeviceCapabilities
import dev.nitrate.core.playback.PlayerEngine
import dev.nitrate.core.playback.VideoCodecCaps
import kotlinx.cinterop.useContents
import platform.Foundation.NSBundle
import platform.Foundation.NSUUID
import platform.UIKit.UIDevice
import platform.UIKit.UIScreen

/** Who this device is to the server: a lasting id, its name and Nitrate's version. */
internal fun iosIdentity(): ClientIdentity {
    val defaults = platform.Foundation.NSUserDefaults.standardUserDefaults
    val id = defaults.stringForKey(DEVICE_ID)
        ?: (UIDevice.currentDevice.identifierForVendor?.UUIDString ?: NSUUID().UUIDString).also { defaults.setObject(it, forKey = DEVICE_ID) }
    val version = NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "0"
    return ClientIdentity(clientName = "Nitrate", version = version, deviceId = id, deviceName = UIDevice.currentDevice.name)
}

private const val DEVICE_ID = "device_id"

/**
 * What Apple's player (AVPlayer) plays on this device. Every iPhone and iPad Nitrate runs on
 * decodes H.264 and HEVC (10-bit) in hardware up to 4K, and iOS shows HDR10, HLG and Dolby Vision
 * (profiles 5 and 8) itself, tone-mapping them on screens without HDR.
 */
internal fun appleCapabilities(): DeviceCapabilities {
    val (width, height) = UIScreen.mainScreen.nativeBounds.useContents { size.width.toInt() to size.height.toInt() }
    fun video(codec: String, tenBit: Boolean) = VideoCodecCaps(
        codec = codec,
        mime = DeviceCapabilities.VIDEO_CODECS.getValue(codec),
        decoderName = "Apple (hardware)",
        hardware = true,
        maxWidth = 3840,
        maxHeight = 2160,
        tenBit = tenBit,
    )
    fun audio(codec: String) = AudioCodecCaps(codec, decoder = "Apple", passthrough = false)
    val device = UIDevice.currentDevice
    return DeviceCapabilities(
        video = mapOf("h264" to video("h264", tenBit = false), "hevc" to video("hevc", tenBit = true)),
        audio = listOf("aac", "mp3", "ac3", "eac3", "alac", "flac").associateWith(::audio),
        hdr = setOf("HDR10", "HDR10Plus", "HLG", "DOVI"),
        displayWidth = maxOf(width, height),
        displayHeight = minOf(width, height),
        model = "${device.model} (${device.systemName} ${device.systemVersion})",
        dolbyVisionProfiles = setOf(5, 8),
        engine = PlayerEngine.APPLE,
    )
}
