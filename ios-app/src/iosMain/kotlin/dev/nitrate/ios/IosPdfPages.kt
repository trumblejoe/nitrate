package dev.nitrate.ios

import dev.nitrate.android.comics.PdfPages
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSURL
import platform.Foundation.writeToFile
import platform.PDFKit.PDFDocument
import platform.PDFKit.kPDFDisplayBoxCropBox
import platform.UIKit.UIImageJPEGRepresentation

/** PDF pages drawn by Apple's PDFKit. */
@OptIn(ExperimentalForeignApi::class)
class IosPdfPages : PdfPages {
    override fun open(path: String): List<Pair<Float, Float>>? {
        val doc = PDFDocument(uRL = NSURL.fileURLWithPath(path))
        if (doc.isLocked()) return null
        return (0 until doc.pageCount.toInt()).map { i ->
            doc.pageAtIndex(i.toULong())?.boundsForBox(kPDFDisplayBoxCropBox)?.useContents { size.width.toFloat() to size.height.toFloat() } ?: (1f to 1f)
        }
    }

    override fun render(path: String, index: Int, widthPx: Int, outPath: String) {
        val doc = PDFDocument(uRL = NSURL.fileURLWithPath(path))
        val page = doc.pageAtIndex(index.toULong()) ?: error("No page $index")
        val (w, h) = page.boundsForBox(kPDFDisplayBoxCropBox).useContents { size.width to size.height }
        // Points are drawn at the screen's scale, so ask in points for the pixels wanted.
        val scale = platform.UIKit.UIScreen.mainScreen.scale
        val widthPt = widthPx / scale
        val image = page.thumbnailOfSize(CGSizeMake(widthPt, widthPt * h / w), kPDFDisplayBoxCropBox)
        val data = UIImageJPEGRepresentation(image, 0.9) ?: error("Page $index couldn't be drawn")
        if (!data.writeToFile(outPath, atomically = true)) error("Page $index couldn't be saved")
    }
}
