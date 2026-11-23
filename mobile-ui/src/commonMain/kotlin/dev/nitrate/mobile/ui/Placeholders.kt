package dev.nitrate.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * What a page shows while it loads: quiet blocks in the shape of what's coming (the picture, the
 * title, the buttons, a row of cards), so nothing jumps when it arrives. Still, never animated:
 * a page that loads quickly shouldn't flicker, and tests can settle. Read out as "Loading".
 */

/** One block of a placeholder. */
@Composable
fun PlaceholderBlock(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(10.dp)) {
    Box(modifier.clip(shape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)))
}

private fun Modifier.loading() = semantics { contentDescription = "Loading" }

/** A details page on its way: the picture, title, facts, Play, the round actions and a row. */
@Composable
fun DetailsPlaceholder(modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize().loading()) {
        val wide = maxWidth >= WIDE_DETAILS
        val heroHeight = if (wide) (maxWidth * 0.42f * 0.62f).coerceIn(240.dp, 420.dp) else (maxWidth * 1.05f).coerceIn(340.dp, 560.dp)
        val head: @Composable () -> Unit = {
            Column {
                PlaceholderBlock(Modifier.fillMaxWidth().height(heroHeight), RoundedCornerShape(0.dp))
                Column(Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    PlaceholderBlock(Modifier.fillMaxWidth(0.7f).height(34.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { repeat(3) { PlaceholderBlock(Modifier.width(64.dp).height(26.dp), RoundedCornerShape(8.dp)) } }
                    PlaceholderBlock(Modifier.fillMaxWidth().height(52.dp), RoundedCornerShape(26.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) { repeat(4) { PlaceholderBlock(Modifier.size(50.dp), CircleShape) } }
                    repeat(3) { PlaceholderBlock(Modifier.fillMaxWidth(if (it == 2) 0.6f else 1f).height(14.dp), RoundedCornerShape(4.dp)) }
                }
            }
        }
        if (wide) Row {
            Box(Modifier.weight(0.42f)) { head() }
            Column(Modifier.weight(0.58f).padding(top = 24.dp)) { CardsPlaceholderRow(posterWidth = 140.dp, wide = true); CardsPlaceholderRow(posterWidth = 116.dp) }
        } else Column {
            head()
            CardsPlaceholderRow(posterWidth = 116.dp)
        }
    }
}

/** A row's title and a few cards. */
@Composable
private fun CardsPlaceholderRow(posterWidth: Dp, wide: Boolean = false) {
    Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PlaceholderBlock(Modifier.padding(start = ScreenPadding).width(150.dp).height(22.dp), RoundedCornerShape(6.dp))
        // As wide as its cards (running off the edge, as a row does), never squeezed into the screen.
        Row(Modifier.fillMaxWidth().clipToBounds().padding(start = ScreenPadding).wrapContentWidth(androidx.compose.ui.Alignment.Start, unbounded = true), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(6) {
                if (wide) PlaceholderBlock(Modifier.width(posterWidth * 1.6f).aspectRatio(16f / 9f), RoundedCornerShape(14.dp))
                else PlaceholderBlock(Modifier.width(posterWidth).aspectRatio(2f / 3f), RoundedCornerShape(14.dp))
            }
        }
    }
}

/** A page of rows on its way (Home, a library): a few rows of cards. */
@Composable
fun RowsPlaceholder(modifier: Modifier = Modifier, rows: Int = 3) {
    Column(modifier.fillMaxWidth().loading().padding(top = 12.dp)) {
        repeat(rows) { CardsPlaceholderRow(posterWidth = 116.dp, wide = it == 0) }
    }
}

/** A list page on its way (a playlist): the header's progress and buttons, then lines with a picture each. */
@Composable
fun ListPlaceholder(modifier: Modifier = Modifier, lines: Int = 8) {
    Column(modifier.fillMaxSize().loading().padding(ScreenPadding), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PlaceholderBlock(Modifier.fillMaxWidth().height(8.dp), RoundedCornerShape(4.dp))
        PlaceholderBlock(Modifier.fillMaxWidth().height(52.dp), RoundedCornerShape(26.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) { repeat(3) { PlaceholderBlock(Modifier.size(50.dp), CircleShape) } }
        repeat(lines) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                PlaceholderBlock(Modifier.height(48.dp).aspectRatio(16f / 9f), RoundedCornerShape(6.dp))
                Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlaceholderBlock(Modifier.width(180.dp).height(14.dp), RoundedCornerShape(4.dp))
                    PlaceholderBlock(Modifier.width(90.dp).height(11.dp), RoundedCornerShape(4.dp))
                }
            }
        }
    }
}

/** A grid of titles on its way (a library's All, artists): cards of [aspect] at about [minWidth]. */
@Composable
fun GridPlaceholder(modifier: Modifier = Modifier, minWidth: Dp = 110.dp, aspect: Float = 2f / 3f) {
    BoxWithConstraints(modifier.fillMaxSize().loading().padding(horizontal = ScreenPadding, vertical = 8.dp)) {
        val columns = (maxWidth / (minWidth + 10.dp)).toInt().coerceAtLeast(1)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(4) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(columns) { PlaceholderBlock(Modifier.weight(1f).aspectRatio(aspect), RoundedCornerShape(12.dp)) }
                }
            }
        }
    }
}
