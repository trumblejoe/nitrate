package dev.nitrate.core.pages

import kotlinx.serialization.Serializable

/**
 * One page's own layout on TV (Home, a library, a custom page, More from… pages), by its top-level
 * page key ([PageKeys]). Anything left null follows the layout every page uses ([DisplayPrefs]'s
 * own tv fields), so a page only differs where the user made it differ. A library's tabs and Home's
 * pinned pages go with the page they're on.
 */
@Serializable
data class PageLook(
    /** Full-screen artwork (one title at a time) or rows. */
    val arrangement: TvHomeLayout? = null,
    /** Rows stacked down the page, rather than one at a time. */
    val stacked: Boolean? = null,
    val captionCorner: CaptionCorner? = null,
    val captionSize: CaptionSize? = null,
    val rowPlace: RowPlace? = null,
    val rowExtent: RowExtent? = null,
) {
    val isEmpty: Boolean get() = this == PageLook()
}

/** Per-page layouts: reading a page's, changing one, and making one the layout for every page. */
object PageLooks {
    /** The page a [pageKey] (a tab's included) takes its layout from. */
    fun ownerOf(pageKey: String): String = when {
        pageKey.startsWith("library/") -> pageKey.split('/').take(2).joinToString("/")
        else -> pageKey
    }

    /** Every page's layout as it stands, before this page's own choices. */
    fun defaults(prefs: DisplayPrefs, pageKey: String): PageLook = PageLook(
        arrangement = if (pageKey.startsWith("library/")) prefs.tvLibraryLayout ?: prefs.tvHome else prefs.tvHome,
        stacked = prefs.tvRowsStacked,
        captionCorner = prefs.tvCaptionCorner,
        captionSize = prefs.tvCaptionSize,
        rowPlace = prefs.tvRowPlace,
        rowExtent = prefs.tvRowExtent,
    )

    /** The page's layout in full: its own choices over every page's. */
    fun resolved(prefs: DisplayPrefs, pageKey: String): PageLook {
        val base = defaults(prefs, pageKey)
        val own = prefs.pageLooks[ownerOf(pageKey)] ?: return base
        return PageLook(
            arrangement = own.arrangement ?: base.arrangement,
            stacked = own.stacked ?: base.stacked,
            captionCorner = own.captionCorner ?: base.captionCorner,
            captionSize = own.captionSize ?: base.captionSize,
            rowPlace = own.rowPlace ?: base.rowPlace,
            rowExtent = own.rowExtent ?: base.rowExtent,
        )
    }

    /** Whether the page has any layout of its own. */
    fun hasOwn(prefs: DisplayPrefs, pageKey: String): Boolean = prefs.pageLooks[ownerOf(pageKey)]?.isEmpty == false

    /** [prefs] as a page with [pageKey] sees them: its layout in the tv fields every screen reads. */
    fun forPage(prefs: DisplayPrefs, pageKey: String?): DisplayPrefs {
        if (pageKey == null || prefs.pageLooks[ownerOf(pageKey)] == null) {
            // A library with no layout of its own still follows "Library pages" when that's set.
            return if (pageKey?.startsWith("library/") == true && prefs.tvLibraryLayout != null) prefs.copy(tvHome = prefs.tvLibraryLayout) else prefs
        }
        val look = resolved(prefs, pageKey)
        return prefs.copy(
            tvHome = look.arrangement ?: prefs.tvHome,
            tvRowsStacked = look.stacked ?: prefs.tvRowsStacked,
            tvCaptionCorner = look.captionCorner ?: prefs.tvCaptionCorner,
            tvCaptionSize = look.captionSize ?: prefs.tvCaptionSize,
            tvRowPlace = look.rowPlace ?: prefs.tvRowPlace,
            tvRowExtent = look.rowExtent ?: prefs.tvRowExtent,
        )
    }

    /** Changes this page's own layout ([change] gets its full layout and returns the new one). */
    fun change(prefs: DisplayPrefs, pageKey: String, change: (PageLook) -> PageLook): DisplayPrefs {
        val owner = ownerOf(pageKey)
        val base = defaults(prefs, owner)
        val next = change(resolved(prefs, owner))
        // Keep only what differs from every page's layout.
        val own = PageLook(
            arrangement = next.arrangement.takeIf { it != base.arrangement },
            stacked = next.stacked.takeIf { it != base.stacked },
            captionCorner = next.captionCorner.takeIf { it != base.captionCorner },
            captionSize = next.captionSize.takeIf { it != base.captionSize },
            rowPlace = next.rowPlace.takeIf { it != base.rowPlace },
            rowExtent = next.rowExtent.takeIf { it != base.rowExtent },
        )
        return prefs.copy(pageLooks = if (own.isEmpty) prefs.pageLooks - owner else prefs.pageLooks + (owner to own))
    }

    /** This page goes back to the layout every page uses. */
    fun reset(prefs: DisplayPrefs, pageKey: String): DisplayPrefs = prefs.copy(pageLooks = prefs.pageLooks - ownerOf(pageKey))

    /**
     * This page's layout becomes every page's, and no page keeps one of its own (so they all match,
     * as "Use on every page" promises).
     */
    fun useEverywhere(prefs: DisplayPrefs, pageKey: String): DisplayPrefs {
        val look = resolved(prefs, pageKey)
        val arrangement = look.arrangement ?: prefs.tvHome
        return prefs.copy(
            tvHome = arrangement,
            tvLibraryLayout = null,
            tvRowsStacked = look.stacked ?: prefs.tvRowsStacked,
            tvCaptionCorner = look.captionCorner ?: prefs.tvCaptionCorner,
            tvCaptionSize = look.captionSize ?: prefs.tvCaptionSize,
            tvRowPlace = look.rowPlace ?: prefs.tvRowPlace,
            tvRowExtent = look.rowExtent ?: prefs.tvRowExtent,
            pageLooks = emptyMap(),
        )
    }
}
