package dev.nitrate.android.playback

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display

/** The display's current mode (size, refresh rate) and the HDR kinds it takes, for the Info panel. */
internal data class DisplayInfo(val width: Int, val height: Int, val hz: Float, val hdr: Set<String>) {
    companion object {
        fun read(context: Context): DisplayInfo? = runCatching {
            val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
            val mode = display.mode
            @Suppress("DEPRECATION")
            val types = if (Build.VERSION.SDK_INT >= 34) mode.supportedHdrTypes else display.hdrCapabilities?.supportedHdrTypes ?: IntArray(0)
            val hdr = types.toList().mapNotNull {
                @Suppress("DEPRECATION")
                when (it) {
                    Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
                    Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
                    Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
                    Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
                    else -> null
                }
            }.toSet()
            DisplayInfo(mode.physicalWidth, mode.physicalHeight, mode.refreshRate, hdr)
        }.getOrNull()
    }
}
