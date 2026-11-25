package dev.nitrate.android.playback

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.TrickplayInfo
import dev.nitrate.core.playback.CreditFeatureReader
import dev.nitrate.core.playback.CreditFeatures
import dev.nitrate.core.playback.CreditScenes
import dev.nitrate.core.playback.FrameLuma

/**
 * The scrub thumbnails of a title's last stretch (from [CreditScenes.CREDITS_FROM]), each read
 * whole (at most [MAX_WIDTH] across: Jellyfin's are 320) into brightness and colour and measured
 * at once ([CreditFeatureReader], the scroll test against the thumbnail before): only the features
 * are kept, for [CreditScenes.fromFeatures]. Earlier thumbnails are null (never fetched). Null
 * when a sheet couldn't be read.
 */
internal object CreditFrames {
    private const val MAX_WIDTH = 320

    fun read(server: MediaServer, itemId: String, info: TrickplayInfo, runtimeMs: Long): List<CreditFeatures?>? {
        val count = info.thumbnailCount
        val perSheet = info.tileColumns * info.tileRows
        if (count <= 0 || perSheet <= 0 || info.intervalMs <= 0 || info.width <= 0 || info.height <= 0) return null
        val from = (runtimeMs * CreditScenes.CREDITS_FROM / info.intervalMs).toInt().coerceIn(0, count)
        val features = MutableList<CreditFeatures?>(count) { null }
        val reader = CreditFeatureReader(CreditScenes.THUMBNAIL_MAX_SHIFT)
        val pixels = IntArray(info.width * info.height)
        for (sheet in from / perSheet..(count - 1) / perSheet) {
            val bitmap = fetch(server.trickplaySheetUrl(itemId, info, sheet), server.authorizationHeader()) ?: return null
            try {
                for (k in 0 until perSheet) {
                    val index = sheet * perSheet + k
                    if (index >= count) break
                    if (index < from) continue
                    val x = (k % info.tileColumns) * info.width
                    val y = (k / info.tileColumns) * info.height
                    if (x + info.width > bitmap.width || y + info.height > bitmap.height) break
                    bitmap.getPixels(pixels, 0, info.width, x, y, info.width, info.height)
                    features[index] = reader.next(frame(pixels, info.width, info.height))
                }
            } finally {
                bitmap.recycle()
            }
        }
        return features
    }

    /** A tile's pixels as a frame, every step-th pixel when it's wider than [MAX_WIDTH]. */
    private fun frame(pixels: IntArray, w: Int, h: Int): FrameLuma {
        val step = (w + MAX_WIDTH - 1) / MAX_WIDTH
        val fw = w / step
        val fh = h / step
        val luma = IntArray(fw * fh)
        val sat = IntArray(fw * fh)
        for (gy in 0 until fh) for (gx in 0 until fw) {
            val p = pixels[gy * step * w + gx * step]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            luma[gy * fw + gx] = CreditFeatureReader.lumaOf(r, g, b)
            sat[gy * fw + gx] = CreditFeatureReader.saturationOf(r, g, b)
        }
        return FrameLuma(fw, fh, luma, sat)
    }

    private fun fetch(url: String, auth: String): Bitmap? = runCatching {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        c.setRequestProperty("Authorization", auth)
        try {
            if (c.responseCode != 200) null else c.inputStream.use { BitmapFactory.decodeStream(it) }
        } finally {
            c.disconnect()
        }
    }.getOrNull()
}
