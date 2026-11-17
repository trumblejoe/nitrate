package dev.nitrate.android.playback

/**
 * A title's theme music, played quietly behind its details page. Going from a series to a season
 * or an episode keeps the same song; leaving the details pages, or starting playback, fades it out.
 */
interface ThemeMusic {
    /** A details page for [itemId] is showing: play its theme (or its series'), if it has one. */
    fun show(itemId: String)

    /** The details page for [itemId] went away. */
    fun hide(itemId: String)
    fun stop()
    fun onAppHidden()
    fun onAppShown()
    fun release()

    /** Plays nothing (a platform without it yet). */
    object None : ThemeMusic {
        override fun show(itemId: String) = Unit
        override fun hide(itemId: String) = Unit
        override fun stop() = Unit
        override fun onAppHidden() = Unit
        override fun onAppShown() = Unit
        override fun release() = Unit
    }
}
