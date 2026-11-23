package dev.nitrate.tv.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Placeholders shown while a page's first answer is on its way: soft blocks where its title, buttons
 * and rows will be, gently pulsing, so the page appears at once in its own shape and nothing jumps
 * when the content arrives. Errors still show their message instead. Never focusable.
 */
private val LocalPulse = staticCompositionLocalOf { 0.10f }

/** A frame for placeholders: one shared pulse, and a screen reader hears "Loading". */
@Composable
private fun Pulsing(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val pulse by rememberInfiniteTransition(label = "placeholder").animateFloat(
        0.07f, 0.14f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse",
    )
    Box(modifier.semantics { contentDescription = "Loading" }) {
        CompositionLocalProvider(LocalPulse provides pulse) { content() }
    }
}

/** One soft block. */
@Composable
fun PlaceholderBlock(width: Dp?, height: Dp, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(10.dp)) {
    Box(
        modifier.then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth()).height(height)
            .clip(shape).background(Color.White.copy(alpha = LocalPulse.current)),
    )
}

/** A row's title and a run of cards ([cardWidth] at [aspect]). */
@Composable
private fun PlaceholderRow(start: Dp, cardWidth: Dp, aspect: Float, titleWidth: Dp = 220.dp) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PlaceholderBlock(titleWidth, 26.dp, Modifier.padding(start = start))
        Row(Modifier.padding(start = start), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            repeat(6) { PlaceholderBlock(cardWidth, cardWidth / aspect, shape = RoundedCornerShape(12.dp)) }
        }
    }
}

/** A details page on its way: title, facts, a few lines, the action row and the first row below. */
@Composable
fun DetailsPlaceholder(start: Dp = 58.dp) = Pulsing(Modifier.fillMaxSize()) {
    Column(Modifier.padding(top = 108.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.padding(start = start), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            PlaceholderBlock(420.dp, 54.dp)
            PlaceholderBlock(300.dp, 20.dp)
            PlaceholderBlock(520.dp, 18.dp)
            PlaceholderBlock(460.dp, 18.dp)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PlaceholderBlock(150.dp, 48.dp, shape = RoundedCornerShape(24.dp))
                repeat(3) { PlaceholderBlock(null, 48.dp, Modifier.size(48.dp), shape = CircleShape) }
            }
        }
        Spacer(Modifier.height(42.dp))
        PlaceholderRow(start, 280.dp, 16f / 9f)
    }
}

/** Rows on their way (Home, a library, a page of rows): [rows] rows of wide cards under room for the tabs. */
@Composable
fun RowsPlaceholder(start: Dp = 58.dp, top: Dp = 120.dp, rows: Int = 3) = Pulsing(Modifier.fillMaxSize()) {
    Column(Modifier.padding(top = top), verticalArrangement = Arrangement.spacedBy(36.dp)) {
        repeat(rows) { i -> PlaceholderRow(start, if (i == 0) 280.dp else 140.dp, if (i == 0) 16f / 9f else 2f / 3f, titleWidth = (240 - i * 40).dp) }
    }
}

/** A list page on its way (a playlist): a heading, its buttons and the first entries. */
@Composable
fun ListPlaceholder(start: Dp = 58.dp) = Pulsing(Modifier.fillMaxSize()) {
    Column(Modifier.padding(start = start, end = start, top = 160.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PlaceholderBlock(360.dp, 54.dp)
        PlaceholderBlock(220.dp, 20.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PlaceholderBlock(180.dp, 48.dp, shape = RoundedCornerShape(24.dp))
            repeat(2) { PlaceholderBlock(null, 48.dp, Modifier.size(48.dp), shape = CircleShape) }
        }
        Spacer(Modifier.height(8.dp))
        repeat(5) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PlaceholderBlock(86.dp, 48.dp, shape = RoundedCornerShape(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlaceholderBlock(280.dp, 18.dp)
                    PlaceholderBlock(140.dp, 14.dp)
                }
            }
        }
    }
}

/** A grid of posters on its way (a library's All, a tab's titles). */
@Composable
fun GridPlaceholder(columns: Int = 6, rows: Int = 2, modifier: Modifier = Modifier) = Pulsing(modifier.fillMaxWidth()) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        repeat(rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                repeat(columns) { Box(Modifier.weight(1f).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = LocalPulse.current))) }
            }
        }
    }
}
