package dev.nitrate.core.playback

import kotlinx.serialization.Serializable

/** How a 3D video packs its two views, as Jellyfin reports it (`Video3DFormat`). */
@Serializable
enum class Video3D(val label: String) {
    /** Two views squeezed side by side into one normal-width frame. */
    HALF_SIDE_BY_SIDE("3D side by side"),
    /** Two full-width views side by side (a double-width frame). */
    FULL_SIDE_BY_SIDE("3D side by side (full)"),
    HALF_TOP_AND_BOTTOM("3D top and bottom"),
    FULL_TOP_AND_BOTTOM("3D top and bottom (full)"),
    /** Blu-ray 3D: a normal 2D picture plus a second view players without MVC skip. */
    MVC("3D (Blu-ray MVC)");

    companion object {
        fun fromJellyfin(value: String?): Video3D? = when (value) {
            "HalfSideBySide" -> HALF_SIDE_BY_SIDE
            "FullSideBySide" -> FULL_SIDE_BY_SIDE
            "HalfTopAndBottom" -> HALF_TOP_AND_BOTTOM
            "FullTopAndBottom" -> FULL_TOP_AND_BOTTOM
            "MVC" -> MVC
            else -> null
        }
    }
}

/** What to do with side-by-side and top-and-bottom 3D. Per device. */
@Serializable
enum class ThreeDMode(val label: String, val detail: String) {
    TWO_D("Show in 2D", "One eye's view, at its proper shape, for screens without 3D"),
    AS_FILE("As the file is", "Both views as recorded, for a TV or projector switched to its 3D mode"),
}

/**
 * The part of the frame to show for 3D in 2D: one eye's view, as a crop in normalised device
 * coordinates (-1..1, y up), and the stretch that gives it its proper shape back.
 */
data class ThreeDView(val left: Float, val right: Float, val bottom: Float, val top: Float, val scaleX: Float, val scaleY: Float)

object ThreeD {
    /** The view to show for [format] in [mode], or null to show the frame as it is. */
    fun view(format: Video3D?, mode: ThreeDMode): ThreeDView? {
        if (mode == ThreeDMode.AS_FILE) return null
        return when (format) {
            // The left eye: the left half of the frame (the top half for top and bottom).
            Video3D.HALF_SIDE_BY_SIDE -> ThreeDView(-1f, 0f, -1f, 1f, scaleX = 2f, scaleY = 1f)
            Video3D.FULL_SIDE_BY_SIDE -> ThreeDView(-1f, 0f, -1f, 1f, scaleX = 1f, scaleY = 1f)
            Video3D.HALF_TOP_AND_BOTTOM -> ThreeDView(-1f, 1f, 0f, 1f, scaleX = 1f, scaleY = 2f)
            Video3D.FULL_TOP_AND_BOTTOM -> ThreeDView(-1f, 1f, 0f, 1f, scaleX = 1f, scaleY = 1f)
            // MVC already plays as its 2D picture; nothing else needs changing.
            Video3D.MVC, null -> null
        }
    }
}
