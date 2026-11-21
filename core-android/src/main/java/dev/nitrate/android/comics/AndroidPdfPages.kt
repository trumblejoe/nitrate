package dev.nitrate.android.comics

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File

/** PDF pages drawn by Android's own PdfRenderer (one page open at a time, so calls take turns). */
class AndroidPdfPages : PdfPages {
    private val lock = Any()

    override fun open(path: String): List<Pair<Float, Float>>? = synchronized(lock) {
        runCatching {
            use(path) { r -> (0 until r.pageCount).map { i -> r.openPage(i).use { it.width.toFloat() to it.height.toFloat() } } }
        }.getOrNull()
    }

    override fun render(path: String, index: Int, widthPx: Int, outPath: String) = synchronized(lock) {
        use(path) { r ->
            r.openPage(index).use { page ->
                val height = (widthPx.toFloat() * page.height / page.width).toInt().coerceIn(1, widthPx * 4)
                val bitmap = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                File(outPath).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                bitmap.recycle()
            }
        }
    }

    private fun <T> use(path: String, block: (PdfRenderer) -> T): T =
        ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use(block) }
}
