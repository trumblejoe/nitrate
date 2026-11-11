package dev.nitrate.android

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import dev.nitrate.core.jellyfin.ClientIdentity

/**
 * The device as the server sees it. Matches what the Jellyfin Kotlin SDK sent before Nitrate
 * dropped it (ANDROID_ID, user-set device name), so existing sign-ins stay tied to this device.
 */
@SuppressLint("HardwareIds")
internal fun androidIdentity(context: Context, clientName: String, version: String): ClientIdentity {
    val resolver = context.contentResolver
    val id = Settings.Secure.getString(resolver, Settings.Secure.ANDROID_ID)?.takeIf { it.isNotBlank() } ?: "nitrate-${Build.FINGERPRINT.hashCode()}"
    val userName = if (Build.VERSION.SDK_INT >= 25) Settings.Global.getString(resolver, Settings.Global.DEVICE_NAME) else null
    val name = userName?.takeIf { it.isNotBlank() }
        ?: if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) Build.MODEL else "${Build.MANUFACTURER} ${Build.MODEL}"
    return ClientIdentity(clientName = clientName, version = version, deviceId = id, deviceName = name)
}
