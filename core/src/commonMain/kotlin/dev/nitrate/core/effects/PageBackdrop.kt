package dev.nitrate.core.effects

import kotlinx.serialization.Serializable

/**
 * What a TV page draws behind everything (under its theme, if it has one): the focused title's
 * picture (as always), a colour taken from that picture (as a gradient or flat), or a fixed gradient
 * or colour of the user's choosing. Colours are ARGB.
 */
@Serializable
enum class PageBackdrop(val label: String, val detail: String, val kind: Kind, val colors: List<Long> = emptyList()) {
    ARTWORK("Title's picture", "The focused title's artwork across the page", Kind.ARTWORK),
    SMART_GRADIENT("Smart gradient", "A gradient in the focused title's own colour", Kind.SMART_GRADIENT),
    SMART_COLOUR("Smart colour", "A flat colour taken from the focused title", Kind.SMART_COLOUR),
    MIDNIGHT("Midnight", "Deep blue into black", Kind.GRADIENT, listOf(0xFF0B1A3A, 0xFF02040A)),
    SUNSET("Sunset", "Amber, rose and violet", Kind.GRADIENT, listOf(0xFF3A1606, 0xFF4A1230, 0xFF140A2A)),
    OCEAN("Ocean", "Teal into navy", Kind.GRADIENT, listOf(0xFF063A40, 0xFF061A33)),
    FOREST("Forest", "Moss into night green", Kind.GRADIENT, listOf(0xFF1C3315, 0xFF061208)),
    EMBER("Ember", "Dark red into black", Kind.GRADIENT, listOf(0xFF3D0E06, 0xFF0A0303)),
    AURORA("Aurora", "Green and violet light", Kind.GRADIENT, listOf(0xFF07322A, 0xFF1A1040, 0xFF050510)),
    CHARCOAL("Charcoal", "Flat dark grey", Kind.SOLID, listOf(0xFF1A1B1F)),
    NAVY("Navy", "Flat dark blue", Kind.SOLID, listOf(0xFF0C1630)),
    PLUM("Plum", "Flat dark purple", Kind.SOLID, listOf(0xFF22102A)),
    BLACK("Black", "Pure black, for OLED screens", Kind.SOLID, listOf(0xFF000000));

    enum class Kind { ARTWORK, SMART_GRADIENT, SMART_COLOUR, GRADIENT, SOLID }

    /** Whether it follows the focused title (and so needs its picture). */
    val followsTitle get() = kind == Kind.ARTWORK || kind == Kind.SMART_GRADIENT || kind == Kind.SMART_COLOUR

    companion object {
        /** A picked colour made dark enough to sit behind white text, keeping its hue: ARGB. */
        fun darkened(argb: Long, keep: Float = 0.38f): Long {
            val r = ((argb shr 16) and 0xFF) * keep
            val g = ((argb shr 8) and 0xFF) * keep
            val b = (argb and 0xFF) * keep
            return (0xFFL shl 24) or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
        }
    }
}
