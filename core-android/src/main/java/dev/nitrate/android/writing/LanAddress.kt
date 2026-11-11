package dev.nitrate.android.writing

import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet4Address

/** This device's IPv4 address on the active network (Wi-Fi or Ethernet), for the transfer page link. */
fun lanAddress(context: Context): String? {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
    val props = cm.getLinkProperties(cm.activeNetwork) ?: return null
    return props.linkAddresses.map { it.address }
        .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
}
