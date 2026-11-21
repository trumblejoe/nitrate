package dev.nitrate.ios

import dev.nitrate.android.comics.PictureLuma
import dev.nitrate.android.comics.PictureReader
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceGray
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIImage

/** Comic pages read small and grey with Core Graphics, for finding panels. */
@OptIn(ExperimentalForeignApi::class)
class IosPictureReader : PictureReader {
    override fun luma(path: String, maxSide: Int): PictureLuma? {
        val image = UIImage.imageWithContentsOfFile(path)?.CGImage ?: return null
        val iw = CGImageGetWidth(image).toDouble()
        val ih = CGImageGetHeight(image).toDouble()
        if (iw <= 0 || ih <= 0) return null
        val scale = minOf(1.0, maxSide / maxOf(iw, ih))
        val w = (iw * scale).toInt().coerceAtLeast(1)
        val h = (ih * scale).toInt().coerceAtLeast(1)
        val bytes = ByteArray(w * h)
        val space = CGColorSpaceCreateDeviceGray()
        bytes.usePinned { pin ->
            val ctx = CGBitmapContextCreate(pin.addressOf(0), w.toULong(), h.toULong(), 8u, w.toULong(), space, CGImageAlphaInfo.kCGImageAlphaNone.value)
            CGContextDrawImage(ctx, CGRectMake(0.0, 0.0, w.toDouble(), h.toDouble()), image)
            CGContextRelease(ctx)
        }
        CGColorSpaceRelease(space)
        return PictureLuma(IntArray(w * h) { bytes[it].toInt() and 0xFF }, w, h)
    }
}
