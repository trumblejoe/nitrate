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
    /** Full art: ▲▼ step along the row too, changing rows only past its first or last title. */
    val upDownAlong: Boolean? = null,
    /** How the focused card shows. */
    val focusStyle: FocusStyle? = null,
    /** Cards' corners and the gap between them. */
    val cardCorners: CardCorners? = null,
    val cardSpacing: CardSpacing? = null,
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
        upDownAlong = prefs.tvUpDownAlong,
        focusStyle = prefs.tvFocusStyle,
        cardCorners = prefs.tvCardCorners,
        cardSpacing = prefs.tvCardSpacing,
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
            upDownAlong = own.upDownAlong ?: base.upDownAlong,
            focusStyle = own.focusStyle ?: base.focusStyle,
            cardCorners = own.cardCorners ?: base.cardCorners,
            cardSpacing = own.cardSpacing ?: base.cardSpacing,
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
            tvUpDownAlong = look.upDownAlong ?: prefs.tvUpDownAlong,
            tvFocusStyle = look.focusStyle ?: prefs.tvFocusStyle,
            tvCardCorners = look.cardCorners ?: prefs.tvCardCorners,
            tvCardSpacing = look.cardSpacing ?: prefs.tvCardSpacing,
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
            upDownAlong = next.upDownAlong.takeIf { it != base.upDownAlong },
            focusStyle = next.focusStyle.takeIf { it != base.focusStyle },
            cardCorners = next.cardCorners.takeIf { it != base.cardCorners },
            cardSpacing = next.cardSpacing.takeIf { it != base.cardSpacing },
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
            tvUpDownAlong = look.upDownAlong ?: prefs.tvUpDownAlong,
            tvFocusStyle = look.focusStyle ?: prefs.tvFocusStyle,
            tvCardCorners = look.cardCorners ?: prefs.tvCardCorners,
            tvCardSpacing = look.cardSpacing ?: prefs.tvCardSpacing,
            pageLooks = emptyMap(),
        )
    }
}

/**
 * Named layouts ("Looks") that set every layout choice at once, for picking a style without going
 * through each switch; the switches stay below for fine-tuning. Applied to every page
 * ([PageLooks.applyEverywhere]) or one page ([PageLooks.applyToPage]).
 */
enum class LayoutPreset(val label: String, val detail: String, val look: PageLook) {
    CINEMATIC(
        "Cinematic", "One title fills the screen, its name bottom left",
        PageLook(PageArrangement.FULL_ART, CaptionCorner.BOTTOM_LEFT, CaptionSize.NORMAL, RowPlace.BOTTOM, RowExtent.FULL, TabStyle.UNDERLINE, false, FocusStyle.GROW, CardCorners.ROUNDED, CardSpacing.NORMAL),
    ),
    CLASSIC(
        "Classic rows", "Rows stacked down the page under the focused title's summary",
        PageLook(PageArrangement.STACKED, CaptionCorner.TOP_LEFT, CaptionSize.NORMAL, RowPlace.BOTTOM, RowExtent.FULL, TabStyle.CHIPS, false, FocusStyle.GROW, CardCorners.ROUNDED, CardSpacing.NORMAL),
    ),
    SHOWCASE(
        "Showcase", "One row at a time over the picture, captions changing sides, roomy cards",
        PageLook(PageArrangement.ROWS, CaptionCorner.ALTERNATE, CaptionSize.NORMAL, RowPlace.BOTTOM, RowExtent.PARTIAL, TabStyle.PILL, false, FocusStyle.GROW, CardCorners.ROUNDED, CardSpacing.ROOMY),
    ),
    COMPACT(
        "Compact", "Stacked rows, short captions, tight square cards: the most titles on screen",
        PageLook(PageArrangement.STACKED, CaptionCorner.TOP_LEFT, CaptionSize.COMPACT, RowPlace.BOTTOM, RowExtent.FULL, TabStyle.UNDERLINE, false, FocusStyle.OUTLINE, CardCorners.SQUARE, CardSpacing.TIGHT),
    ),
    MINIMAL(
        "Minimal", "One row at a time, logos instead of names, nothing but the picture",
        PageLook(PageArrangement.ROWS, CaptionCorner.BOTTOM_LEFT, CaptionSize.MINIMAL, RowPlace.BOTTOM, RowExtent.FULL, TabStyle.LARGE, false, FocusStyle.GLOW, CardCorners.ROUNDED, CardSpacing.NORMAL),
    ),
    ;

    companion object {
        /** The preset a page's full layout is, if it's exactly one of them (else it's been fine-tuned). */
        fun matching(look: PageLook): LayoutPreset? = entries.firstOrNull { it.look == look }
    }
}

/** Every page takes [preset], and no page keeps a layout of its own. */
fun PageLooks.applyEverywhere(prefs: DisplayPrefs, preset: LayoutPreset): DisplayPrefs {
    val look = preset.look
    return prefs.copy(
        tvArrangement = look.arrangement!!,
        tvLibraryArrangement = null,
        tvCaptionCorner = look.captionCorner!!,
        tvCaptionSize = look.captionSize!!,
        tvRowPlace = look.rowPlace!!,
        tvRowExtent = look.rowExtent!!,
        tvTabStyle = look.tabStyle!!,
        tvUpDownAlong = look.upDownAlong!!,
        tvFocusStyle = look.focusStyle!!,
        tvCardCorners = look.cardCorners!!,
        tvCardSpacing = look.cardSpacing!!,
        pageLooks = emptyMap(),
    )
}

/** Just [pageKey]'s page takes [preset] (kept as what differs from every page's layout). */
fun PageLooks.applyToPage(prefs: DisplayPrefs, pageKey: String, preset: LayoutPreset): DisplayPrefs =
    change(prefs, pageKey) { preset.look }
