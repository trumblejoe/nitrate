package dev.nitrate.android.playback

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.TrickplayInfo
import dev.nitrate.core.playback.CreditScenes
import dev.nitrate.core.playback.FrameLuma
import java.net.HttpURLConnection
import java.net.URL

/**
 * The scrub thumbnails of a title's last stretch (from [CreditScenes.CREDITS_FROM]), as small
 * brightness grids for [CreditScenes.fromFrames]. Earlier thumbnails are left empty (never
 * fetched). Null when a sheet couldn't be read.
 */
internal object CreditFrames {
    private val EMPTY = FrameLuma(0, 0, IntArray(0))
    private const val GRID_W = 48
    private const val GRID_H = 27

    fun read(server: MediaServer, itemId: String, info: TrickplayInfo, runtimeMs: Long): List<FrameLuma>? {
        val count = info.thumbnailCount
        val perSheet = info.tileColumns * info.tileRows
        if (count <= 0 || perSheet <= 0 || info.intervalMs <= 0 || info.width <= 0 || info.height <= 0) return null
        val from = (runtimeMs * CreditScenes.CREDITS_FROM / info.intervalMs).toInt().coerceIn(0, count)
        val frames = MutableList(count) { EMPTY }
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
                    frames[index] = sample(bitmap, x, y, info.width, info.height)
                }
            } finally {
                bitmap.recycle()
            }
        }
        return frames
    }

    private fun sample(b: Bitmap, x: Int, y: Int, w: Int, h: Int): FrameLuma {
        val luma = IntArray(GRID_W * GRID_H)
        for (gy in 0 until GRID_H) for (gx in 0 until GRID_W) {
            val p = b.getPixel(x + (gx * w + w / 2) / GRID_W, y + (gy * h + h / 2) / GRID_H)
            luma[gy * GRID_W + gx] = (299 * ((p shr 16) and 0xFF) + 587 * ((p shr 8) and 0xFF) + 114 * (p and 0xFF)) / 1000
        }
        return FrameLuma(GRID_W, GRID_H, luma)
    }

    private fun fetch(url: String, auth: String): Bitmap? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
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
