package dev.nitrate.tv.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * Room the page frame keeps clear at the top end for its clock ([PageClock]), so the tabs top right
 * on full art (and a caption in that corner) move aside for it. Zero without the clock.
 */
val LocalClockRoom = staticCompositionLocalOf { 0.dp }

/** How much room the clock takes at the top end of a page. */
val ClockRoom: Dp = 132.dp

/**
 * Which row this is, for rows one at a time and full art: a slim track with the row's place on it
 * and "3 / 9" above. Bright for a moment after the row changes, quiet otherwise. Nothing for a
 * page of one row.
 */
@Composable
fun RowMarker(index: Int, count: Int, modifier: Modifier = Modifier) {
    if (count <= 1 || index !in 0 until count) return
    var fresh by remember { mutableStateOf(true) }
    LaunchedEffect(index) { fresh = true; kotlinx.coroutines.delay(2_000); fresh = false }
    val alpha by animateFloatAsState(if (fresh) 1f else 0.45f, label = "marker")
    val track = 150.dp
    val thumb = (track / count).coerceAtLeast(14.dp)
    Column(
        modifier.graphicsLayer { this.alpha = alpha }.clearAndSetSemantics { contentDescription = "Row ${index + 1} of $count" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("${index + 1} / $count", style = MaterialTheme.typography.labelMedium, color = Color.White)
        Box(Modifier.width(4.dp).height(track).background(Color.White.copy(alpha = 0.22f), RoundedCornerShape(2.dp))) {
            Box(
                Modifier.fillMaxWidth().height(thumb)
                    .offset(y = (track - thumb) * (index.toFloat() / (count - 1)))
                    .background(Color.White, RoundedCornerShape(2.dp)),
            )
        }
    }
}

/** A small clock for a page's top corner: the device's own 12/24-hour format, moved on each minute. */
@Composable
fun PageClock(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val format = remember(context) { android.text.format.DateFormat.getTimeFormat(context) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000 - System.currentTimeMillis() % 60_000 + 50)
            now = System.currentTimeMillis()
        }
    }
    Text(
        format.format(java.util.Date(now)),
        style = MaterialTheme.typography.titleLarge,
        color = Color.White.copy(alpha = 0.9f),
        modifier = modifier
            .background(Color(0x66000000), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}
