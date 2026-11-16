package dev.nitrate.dovi

/**
 * Rewrites Dolby Vision metadata (RPU NAL units) from dual-layer profile 7 as profile 8.1, with
 * libdovi (MIT). Unavailable when the app was built without it (see tools/build-libdovi.sh).
 */
object RpuConverter {
    /** Whether this app has libdovi built in. */
    val isAvailable: Boolean by lazy { runCatching { System.loadLibrary("doviJNI") }.isSuccess }

    /**
     * [nal]: one RPU NAL unit (HEVC type 62) without its start code. Returns it as profile 8.1, or
     * null when it can't be read or converted (or libdovi isn't available).
     */
    fun toProfile81(nal: ByteArray): ByteArray? = if (isAvailable) nativeToProfile81(nal) else null

    @JvmStatic private external fun nativeToProfile81(nal: ByteArray): ByteArray?
}
