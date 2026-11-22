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
    /** Full art, rows one at a time, or stacked rows. */
    val arrangement: PageArrangement? = null,
    val captionCorner: CaptionCorner? = null,
    val captionSize: CaptionSize? = null,
    val rowPlace: RowPlace? = null,
    val rowExtent: RowExtent? = null,
    /** How the page's tabs look. */
    val tabStyle: TabStyle? = null,
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
        arrangement = if (pageKey.startsWith("library/")) prefs.tvLibraryArrangement ?: prefs.tvArrangement else prefs.tvArrangement,
        captionCorner = prefs.tvCaptionCorner,
        captionSize = prefs.tvCaptionSize,
        rowPlace = prefs.tvRowPlace,
        rowExtent = prefs.tvRowExtent,
        tabStyle = prefs.tvTabStyle,
    )

    /** The page's layout in full: its own choices over every page's. */
    fun resolved(prefs: DisplayPrefs, pageKey: String): PageLook {
        val base = defaults(prefs, pageKey)
        val own = prefs.pageLooks[ownerOf(pageKey)] ?: return base
        return PageLook(
            arrangement = own.arrangement ?: base.arrangement,
            captionCorner = own.captionCorner ?: base.captionCorner,
            captionSize = own.captionSize ?: base.captionSize,
            rowPlace = own.rowPlace ?: base.rowPlace,
            rowExtent = own.rowExtent ?: base.rowExtent,
            tabStyle = own.tabStyle ?: base.tabStyle,
        )
    }

    /** Whether the page has any layout of its own. */
    fun hasOwn(prefs: DisplayPrefs, pageKey: String): Boolean = prefs.pageLooks[ownerOf(pageKey)]?.isEmpty == false

    /** [prefs] as a page with [pageKey] sees them: its layout in the tv fields every screen reads. */
    fun forPage(prefs: DisplayPrefs, pageKey: String?): DisplayPrefs {
        if (pageKey == null || prefs.pageLooks[ownerOf(pageKey)] == null) {
            // A library with no layout of its own still follows "Library pages" when that's set.
            return if (pageKey?.startsWith("library/") == true && prefs.tvLibraryArrangement != null) prefs.copy(tvArrangement = prefs.tvLibraryArrangement) else prefs
        }
        val look = resolved(prefs, pageKey)
        return prefs.copy(
            tvArrangement = look.arrangement ?: prefs.tvArrangement,
            tvCaptionCorner = look.captionCorner ?: prefs.tvCaptionCorner,
            tvCaptionSize = look.captionSize ?: prefs.tvCaptionSize,
            tvRowPlace = look.rowPlace ?: prefs.tvRowPlace,
            tvRowExtent = look.rowExtent ?: prefs.tvRowExtent,
            tvTabStyle = look.tabStyle ?: prefs.tvTabStyle,
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
            captionCorner = next.captionCorner.takeIf { it != base.captionCorner },
            captionSize = next.captionSize.takeIf { it != base.captionSize },
            rowPlace = next.rowPlace.takeIf { it != base.rowPlace },
            rowExtent = next.rowExtent.takeIf { it != base.rowExtent },
            tabStyle = next.tabStyle.takeIf { it != base.tabStyle },
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
        return prefs.copy(
            tvArrangement = look.arrangement ?: prefs.tvArrangement,
            tvLibraryArrangement = null,
            tvCaptionCorner = look.captionCorner ?: prefs.tvCaptionCorner,
            tvCaptionSize = look.captionSize ?: prefs.tvCaptionSize,
            tvRowPlace = look.rowPlace ?: prefs.tvRowPlace,
            tvRowExtent = look.rowExtent ?: prefs.tvRowExtent,
            tvTabStyle = look.tabStyle ?: prefs.tvTabStyle,
            pageLooks = emptyMap(),
        )
    }
}
