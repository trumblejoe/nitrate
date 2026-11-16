package dev.nitrate.android.playback

import android.graphics.Color
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import dev.nitrate.core.playback.SubtitleBackground
import dev.nitrate.core.playback.SubtitleEdge
import dev.nitrate.core.playback.SubtitleStyle

/** Applies the user's subtitle look to a player view (both apps). */
@OptIn(UnstableApi::class)
fun PlayerView.applySubtitleStyle(style: SubtitleStyle, styledTrack: Boolean = false) {
    val view = subtitleView ?: return
    val background = when (style.background) {
        SubtitleBackground.NONE -> Color.TRANSPARENT
        SubtitleBackground.SHADED -> 0x99000000.toInt()
        SubtitleBackground.SOLID -> Color.BLACK
    }
    val edge = when (style.edge) {
        SubtitleEdge.NONE -> CaptionStyleCompat.EDGE_TYPE_NONE
        SubtitleEdge.OUTLINE -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
        SubtitleEdge.SHADOW -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
    }
    view.setStyle(CaptionStyleCompat(style.textColor.toInt(), background, Color.TRANSPARENT, edge, Color.BLACK, null))
    view.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * style.scale)
    view.setBottomPaddingFraction(style.bottomMargin)
    // An ASS/SSA track keeps its own colours, sizes and positions unless the user wants their style.
    val fileStyles = styledTrack && style.styled != dev.nitrate.core.playback.StyledSubtitles.MINE
    view.setApplyEmbeddedStyles(fileStyles)
    view.setApplyEmbeddedFontSizes(fileStyles)
}
