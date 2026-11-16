package dev.nitrate.ass

import android.graphics.Bitmap

/**
 * One ASS/SSA track drawn by libass: load the script and its fonts, size it to the picture, then
 * [render] a moment into a bitmap. Not thread-safe: use it from one thread. [release] when done.
 */
class AssRenderer private constructor(private var handle: Long) {
    /** A font the script uses (from the file's attachments), by its file name. */
    fun addFont(name: String, data: ByteArray) = nativeAddFont(handle, name, data)

    /** After [addFont]: the fallback font for anything the script names but doesn't bring. */
    fun setDefaultFont(path: String?, family: String?) = nativeSetFonts(handle, path, family)

    /** The whole script (UTF-8). False if libass can't read it. */
    fun loadTrack(script: ByteArray): Boolean = nativeLoadTrack(handle, script)

    /** [frameWidth]×[frameHeight]: the bitmap drawn into; storage: the video's own size. */
    fun setSize(frameWidth: Int, frameHeight: Int, storageWidth: Int, storageHeight: Int) =
        nativeSetSize(handle, frameWidth, frameHeight, storageWidth, storageHeight)

    /** Draws [timeMs] into [bitmap] (ARGB_8888) when it changed (or [force]). */
    fun render(timeMs: Long, bitmap: Bitmap, force: Boolean = false): Result = when (nativeRender(handle, timeMs, bitmap, force)) {
        0 -> Result.UNCHANGED
        1 -> Result.DRAWN
        else -> Result.EMPTY
    }

    fun release() {
        if (handle != 0L) nativeRelease(handle)
        handle = 0L
    }

    enum class Result { UNCHANGED, DRAWN, EMPTY }

    companion object {
        /** Whether libass was built into this app (tools/build-libass.sh). */
        val isAvailable: Boolean by lazy { runCatching { System.loadLibrary("assJNI"); true }.getOrDefault(false) }

        fun create(): AssRenderer? = if (!isAvailable) null else nativeCreate().takeIf { it != 0L }?.let(::AssRenderer)

        @JvmStatic private external fun nativeCreate(): Long
        @JvmStatic private external fun nativeAddFont(handle: Long, name: String, data: ByteArray)
        @JvmStatic private external fun nativeSetFonts(handle: Long, path: String?, family: String?)
        @JvmStatic private external fun nativeLoadTrack(handle: Long, script: ByteArray): Boolean
        @JvmStatic private external fun nativeSetSize(handle: Long, frameWidth: Int, frameHeight: Int, storageWidth: Int, storageHeight: Int)
        @JvmStatic private external fun nativeRender(handle: Long, timeMs: Long, bitmap: Bitmap, force: Boolean): Int
        @JvmStatic private external fun nativeRelease(handle: Long)
    }
}
